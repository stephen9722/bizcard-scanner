package tw.pentamaster.bizcard.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/** Parser for CamCard / 全能名片王 and generic contact CSV files. */
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
            var name = value(row, "姓名(中文)", "姓名（中文）", "中文姓名")
            var nameEn = value(row, "姓名(英文)", "姓名（英文）", "英文姓名", "English Name", "Name English", "Name (English)")

            val genericName = value(row, "姓名", "全名", "名稱", "Name", "Full Name", "Display Name")
            if (genericName.isNotBlank()) {
                val (zh, en) = localized(genericName)
                if (name.isBlank()) name = zh
                if (nameEn.isBlank()) nameEn = en
            }

            if (name.isBlank()) {
                val family = value(row, "姓", "姓氏", "Last Name", "Family Name")
                val given = value(row, "名字", "名", "First Name", "Given Name")
                val combined = (family + given).trim()
                if (combined.any(Char::isCjk)) name = combined
                else if (nameEn.isBlank()) nameEn = listOf(given, family).filter { it.isNotBlank() }.joinToString(" ")
            }

            if (nameEn.isBlank()) {
                val givenPhonetic = value(row, "名字拚音或音標", "名字拼音或音標", "Given Name Pinyin", "First Name Pinyin")
                val familyPhonetic = value(row, "姓氏拚音或音標", "姓氏拼音或音標", "Family Name Pinyin", "Last Name Pinyin")
                nameEn = listOf(givenPhonetic, familyPhonetic).filter { it.isNotBlank() }.joinToString(" ").trim()
            }

            var company = value(row, "公司(中文)", "公司（中文）", "中文公司", "公司中文")
            var companyEn = value(row, "公司(英文)", "公司（英文）", "英文公司", "Company English", "Company (English)")
            val extraCompanies = mutableListOf<String>()

            fun consumeCompany(raw: String) {
                if (raw.isBlank()) return
                val (zh, en) = localized(raw)
                if (zh.isNotBlank()) {
                    if (company.isBlank()) company = zh
                    else if (!company.equals(zh, ignoreCase = true)) extraCompanies += zh
                }
                if (en.isNotBlank()) {
                    if (companyEn.isBlank()) companyEn = en
                    else if (!companyEn.equals(en, ignoreCase = true)) extraCompanies += en
                }
            }

            consumeCompany(value(row, "公司1", "公司", "公司名稱", "Company1", "Company", "Organization", "Organisation"))
            consumeCompany(value(row, "公司2", "Company2", "第二公司"))

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

            if (name.isBlank() && nameEn.isBlank() && company.isBlank() && companyEn.isBlank()) return@mapNotNull null

            val createdAt = parseDate(
                value(row, "創建日期", "建立日期", "新增日期", "掃描日期", "Created At", "Created", "Create Time")
            ) ?: System.currentTimeMillis()
            val updatedAt = parseDate(
                value(row, "更新日期", "修改日期", "Updated At", "Updated", "Modify Time")
            ) ?: createdAt

            val sourceText = originalHeaders.mapIndexedNotNull { index, header ->
                val v = row.getOrNull(index)?.trim().orEmpty()
                if (header.isBlank() || v.isBlank()) null else "$header: $v"
            }.joinToString("\n")

            val notes = buildList {
                if (sourceNotes.isNotBlank()) add(sourceNotes)
                extraCompanies.distinct().forEach { add("其他公司/單位：$it") }
            }.joinToString("\n")

            BusinessCard(
                name = name,
                nameEn = nameEn,
                company = company,
                companyEn = companyEn,
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

    /** Classifies/splits one source cell into local/CJK and English/Latin variants. */
    private fun localized(raw: String): Pair<String, String> {
        val text = raw.trim()
        if (text.isBlank()) return "" to ""
        val firstCjk = text.indexOfFirst { it.isCjk() }
        val firstLatin = text.indexOfFirst { it.isAsciiLetter() }
        return when {
            firstCjk >= 0 && firstLatin >= 0 && firstCjk < firstLatin ->
                text.substring(0, firstLatin).trimBoundary() to text.substring(firstLatin).trimBoundary()
            firstCjk >= 0 && firstLatin >= 0 ->
                text.substring(firstCjk).trimBoundary() to text.substring(0, firstCjk).trimBoundary()
            firstCjk >= 0 -> text to ""
            firstLatin >= 0 -> "" to text
            else -> text to ""
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
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
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm", "yyyy.MM.dd"
        )
        patterns.forEach { pattern ->
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                parser.parse(raw.trim())?.time?.let { return it }
            } catch (_: Exception) {
                // Try next layout.
            }
        }
        raw.trim().toLongOrNull()?.let { epoch ->
            return if (epoch in 1_000_000_000L..9_999_999_999L) epoch * 1000L else epoch
        }
        return null
    }

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
                    field.append('"'); i++
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

    private fun Char.isCjk(): Boolean = code in 0x4E00..0x9FFF
    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
    private fun String.trimBoundary(): String = trim().trim(' ', '/', '|', '·', ':', '：').trim()
}
