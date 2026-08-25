package tw.pentamaster.bizcard.data

/**
 * Reads vCard 2.1 / 3.0 / 4.0 well enough to migrate a contact export out of another
 * card app.
 *
 * The two things that break naive parsers on real Chinese exports are handled here:
 * line folding (a long ADR wrapped onto continuation lines starting with a space) and
 * QUOTED-PRINTABLE, which is what vCard 2.1 exporters use for non-ASCII text — without
 * decoding it, every Chinese name arrives as "=E9=99=B3=E5=BF=97=E6=98=8E".
 */
object VCardReader {

    class ParsedCard(val card: BusinessCard, val photoBytes: ByteArray?)

    private data class Prop(val name: String, val params: String, val value: String)

    fun parse(text: String): List<ParsedCard> {
        val out = mutableListOf<ParsedCard>()
        var props: MutableList<Prop>? = null
        var photoB64: String? = null

        for (line in unfold(text)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                props = mutableListOf(); photoB64 = null; continue
            }
            if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                props?.let { out += ParsedCard(build(it), photoB64?.let(::decodeBase64)) }
                props = null; photoB64 = null; continue
            }
            val p = props ?: continue

            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue

            val left = trimmed.substring(0, colon)
            val value = trimmed.substring(colon + 1)
            val name = left.substringBefore(';').substringAfter('.').uppercase()
            val params = left.substringAfter(';', "")

            if ((name == "PHOTO" || name == "LOGO") && photoB64 == null &&
                (params.contains("BASE64", true) || params.contains("ENCODING=b", true))
            ) {
                photoB64 = value.filterNot { it.isWhitespace() }
            } else {
                p += Prop(name, params, decodeValue(value, params))
            }
        }
        return out
    }

    /**
     * Joins continuation lines. Two different mechanisms have to be handled: RFC folding
     * (next line starts with a space) and QUOTED-PRINTABLE soft breaks (previous line
     * ends with '='). Real exports from older apps use both.
     */
    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = mutableListOf<String>()
        for (line in raw) {
            val continuation = line.startsWith(" ") || line.startsWith("\t")
            val softBreak = result.lastOrNull()?.endsWith("=") == true
            when {
                result.isNotEmpty() && continuation ->
                    result[result.lastIndex] = result.last() + line.substring(1)
                result.isNotEmpty() && softBreak ->
                    result[result.lastIndex] = result.last().dropLast(1) + line
                else -> result += line
            }
        }
        return result
    }

    private fun decodeValue(value: String, params: String): String {
        var v = value
        if (params.contains("QUOTED-PRINTABLE", true)) {
            val charset = Regex("CHARSET=([^;:]+)", RegexOption.IGNORE_CASE)
                .find(params)?.groupValues?.get(1)?.trim() ?: "UTF-8"
            v = decodeQuotedPrintable(v, charset)
        }
        return v
            .replace("\\n", "\n").replace("\\N", "\n")
            .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
            .trim()
    }

    private fun decodeQuotedPrintable(s: String, charset: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { bytes.write(b); i += 3; continue }
            }
            bytes.write(c.code)
            i++
        }
        return try {
            String(bytes.toByteArray(), charset(charset))
        } catch (_: Exception) {
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun decodeBase64(s: String): ByteArray? = try {
        android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    } catch (_: Exception) { null }

    private fun build(props: List<Prop>): BusinessCard {
        fun value(vararg names: String): String =
            props.firstOrNull { p -> names.any { it == p.name } && p.value.isNotBlank() }?.value.orEmpty()

        val tels = props.filter { it.name == "TEL" }
        fun tel(vararg types: String): String =
            tels.firstOrNull { t -> types.any { t.params.contains(it, ignoreCase = true) } }?.value.orEmpty()

        val fax = tel("FAX")
        val cell = tel("CELL", "MOBILE").ifBlank {
            tels.map { it.value }
                .firstOrNull { it != fax && it.filter(Char::isDigit).startsWith("09") }
                .orEmpty()
        }
        val work = tels.map { it.value }.firstOrNull { it != fax && it != cell }.orEmpty()

        val org = value("ORG").split(';')
        val fn = value("FN")
        val n = value("N").split(';').filter { it.isNotBlank() }
        // vCard N is Family;Given;... — Chinese names read family-first, western given-first.
        val fromN = if (n.isNotEmpty() && n.all { it.all { c -> c.code in 0x4E00..0x9FFF } }) {
            n.joinToString("")
        } else {
            n.asReversed().joinToString(" ")
        }

        return BusinessCard(
            name = fn.ifBlank { fromN },
            company = org.getOrNull(0)?.trim().orEmpty(),
            department = org.getOrNull(1)?.trim().orEmpty(),
            title = value("TITLE", "ROLE"),
            mobile = cell,
            phone = work,
            fax = fax,
            email = value("EMAIL"),
            website = value("URL"),
            address = value("ADR").split(';').filter { it.isNotBlank() }.joinToString(" "),
            tags = value("CATEGORIES").split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
            notes = value("NOTE")
        )
    }
}
