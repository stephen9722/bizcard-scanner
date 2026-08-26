package tw.pentamaster.bizcard.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parser for contact CSV files exported by CamCard / 全能名片王 and other address-book tools.
 *
 * The importer intentionally maps by header name rather than by column position. This lets it
 * accept a small CamCard export like:
 * 創建日期,姓名,名字,名字拚音或音標,姓,姓氏拚音或音標,公司1,部門1,職位1,公司2
 * as well as richer exports that also contain phone, email, address, notes, etc.
 */
object CsvCardImporter {

    private const val MAX_ROWS = 20_000

    fun parse(bytes: ByteArray): List<BusinessCard> {
        val text = decode(bytes).removePrefix("\uFEFF")
        val rows = parseRows(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) return emptyList()
        if (rows.size - 1 > MAX_ROWS) throw IllegalArgumentException("CSV 筆數過多")

        val originalHeaders = rows.first().map { it.trim().removePrefix("\uFEFF") }
        val indexes = mutableMapOf<String, Int>()
        originalHeaders.forEachIndexed { index, header ->
            val key = normalizeHeader(header)
            if (key.isNotBlank() && key !in indexes) indexes[key] = index
        }

        fun value(row: List<String>, vararg aliases: String): String {
            aliases.forEach { alias ->
                val index = indexes[normalizeHeader(alias)] ?: return@forEach
                val v = row.getOrNull(index)?.trim().orEmpty()
                if (v.isNotBlank()) return v
            }
            return ""
        }

        return rows.drop(1).mapNotNull { row ->
            var name = value(row, "姓名", "全名", "名稱", "Name", "Full Name", "Display Name")
            if (name.isBlank()) {
                val family = value(row, "姓", "姓氏", "Last Name", "Family Name")
                val given = value(row, "名字", "名", "First Name", "Given Name")
                name = (family + given).trim()
            }

            val company1 = value(row, "公司1", "公司", "公司名稱", "Company1", "Company", "Organization", "Organisation")
            val company2 = value(row, "公司2", "Company2", "第二公司")
            val department = value(row, "部門1", "部門", "Department1", "Department")
            val title = value(row, "職位1", "職稱", "職位", "Title1", "Title", "Job Title")
            val mobile = value(row, "手機1", "手機", "行動電話1", "行動電話", "Mobile1", "Mobile", "Cell Phone", "Cell")
            val phone = value(row, "電話1", "電話", "辦公室電話1", "辦公室電話", "公司電話", "Phone1", "Phone", "Telephone", "Work Phone")
            val fax = value(row, "傳真1", "傳真", "Fax1", "Fax")
            val email = value(row, "Email1", "Email", "電子郵件1", "電子郵件", "E-mail")
            val website = value(row, "網站1", "網站", "Website1", "Website", "URL")
            val address = value(row, "地址1", "地址", "公司地址", "Address1", "Address", "Work Address")
            val tags = value(row, "標籤", "分類", "Tags", "Categories")
            val sourceNotes = value(row, "備註", "備忘", "註記", "Notes", "Note", "Memo")

            if (name.isBlank() && company1.isBlank() && company2.isBlank()) return@mapNotNull null

            val createdAt = parseDate(
                value(row, "創建日期", "建立日期", "新增日期", "掃描日期", "Created At", "Created", "Create Time")
            ) ?: System.currentTimeMillis()
            val updatedAt = parseDate(
                value(row, "更新日期", "修改日期", "Updated At", "Updated", "Modify Time")
            ) ?: createdAt

            // Preserve every source column in rawTextFront so unsupported CamCard fields remain
            // searchable and are not silently discarded during migration.
            val sourceText = originalHeaders.mapIndexedNotNull { index, header ->
                val v = row.getOrNull(index)?.trim().orEmpty()
                if (header.isBlank() || v.isBlank()) null else "$header: $v"
            }.joinToString("\n")

            val notes = buildList {
                if (sourceNotes.isNotBlank()) add(sourceNotes)
                if (company2.isNotBlank() && !company2.equals(company1, ignoreCase = true)) {
                    add("其他公司/單位：$company2")
                }
            }.joinToString("\n")

            BusinessCard(
                name = name,
                company = company1.ifBlank { company2 },
                title = title,
                department = department,
                phone = phone,
                mobile = mobile,
                fax = fax,
                email = email,
                website = website,
                address = address,
                tags = tags,
                notes = notes,
                rawTextFront = sourceText,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            // A number of older Chinese Windows exports are Big5/CP950 rather than UTF-8.
            Charset.forName("Big5").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun normalizeHeader(raw: String): String = raw
        .trim()
        .removePrefix("\uFEFF")
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_\\-（）()]+"), "")

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy.MM.dd HH:mm",
            "yyyy.MM.dd"
        )
        patterns.forEach { pattern ->
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                parser.parse(raw.trim())?.time?.let { return it }
            } catch (_: Exception) {
                // Try the next known layout.
            }
        }
        raw.trim().toLongOrNull()?.let { epoch ->
            return if (epoch in 1_000_000_000L..9_999_999_999L) epoch * 1000L else epoch
        }
        return null
    }

    /** RFC 4180-style CSV reader with quoted commas, quotes and embedded newlines. */
    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            row += field.toString()
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows += row.toList()
            row.clear()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> endField()
                (ch == '\r' || ch == '\n') && !inQuotes -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(ch)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
