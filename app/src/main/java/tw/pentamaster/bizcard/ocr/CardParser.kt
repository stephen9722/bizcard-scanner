package tw.pentamaster.bizcard.ocr

import tw.pentamaster.bizcard.data.BusinessCard

/**
 * Turns OCR lines into structured fields, tuned for Taiwanese bilingual business cards.
 * Local/CJK and English names/companies are intentionally kept in separate fields.
 */
object CardParser {

    private val MOBILE = Regex("""(?:\+?886[-\s]?)?0?9\d{2}[-\s.]?\d{3}[-\s.]?\d{3}""")
    private val LANDLINE = Regex(
        """(?:(?:\+?886[-\s.]?\(?\d{1,2}\)?)|(?:\(?0\d{1,2}\)?))[-\s.]?\d{3,4}[-\s.]?\d{3,4}"""
    )
    private val EXTENSION = Regex("""(?:ext|EXT|Ext|分機|轉)\.?\s*#?\s*(\d{1,6})""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val WEBSITE = Regex(
        """(?i)(?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)*\.[a-z]{2,63}(?:[/:?#]\S*)?"""
    )

    private val COMPANY_HINTS = listOf(
        "股份有限公司", "有限公司", "企業社", "工作室", "事務所", "實業", "工業", "科技",
        "國際", "集團", "企業", "公司", "電子", "設備", "機械", "貿易", "顧問",
        "Co.", " Co ", "Ltd", "Inc", "Corp", "Company", "Technolog", "Industr", "Group",
        "Equipment", "Electronics", "Semiconductor", "Automation"
    )

    private val TITLE_HINTS = listOf(
        "董事長", "總經理", "執行長", "副總", "協理", "襄理", "副理", "經理", "處長",
        "廠長", "課長", "組長", "主任", "主管", "特助", "助理", "專員", "工程師",
        "技術員", "業務", "顧問", "總監", "負責人", "研究員", "設計師",
        "CEO", "CTO", "COO", "CFO", "President", "Director", "Manager", "Engineer",
        "Sales", "Supervisor", "Specialist", "Consultant", "Assistant"
    )

    private val DEPT_HINTS = listOf(
        "事業群", "事業部", "研發部", "業務部", "管理部", "財務部", "採購部", "品保部",
        "製造部", "工程部", "中心", "處", "部門", "Department", "Dept", "Division"
    )

    private val ADDRESS_HINTS = listOf("市", "縣", "區", "鄉", "鎮", "路", "街", "段", "巷", "弄", "號", "樓", "F")
    private val FAX_LABEL = listOf("傳真", "FAX", "Fax", "fax", "F:")
    private val MOBILE_LABEL = listOf("手機", "行動", "Mobile", "MOBILE", "Cell", "M:", "M：")
    private val TEL_LABEL = listOf("電話", "TEL", "Tel", "tel", "Phone", "T:", "T：")

    fun parse(front: OcrResult, back: OcrResult = OcrResult.EMPTY): BusinessCard {
        val primary = if (back.lines.sumOf { it.text.length } > front.lines.sumOf { it.text.length }) back else front
        val lines = primary.lines + (if (primary === front) back.lines else front.lines)

        var email = ""
        var website = ""
        var mobile = ""
        var phone = ""
        var fax = ""
        var address = ""
        var company = ""
        var companyEn = ""
        var title = ""
        var department = ""

        val consumed = mutableSetOf<Int>()

        lines.forEachIndexed { idx, line ->
            val t = line.text.trim()
            if (t.isBlank()) return@forEachIndexed
            var contactMatched = false

            if (email.isBlank()) {
                EMAIL.find(t)?.let { email = it.value; contactMatched = true }
            }

            val labelledFax = findAfterLabel(t, FAX_LABEL, LANDLINE)
            val labelledMobile = findAfterLabel(t, MOBILE_LABEL, MOBILE)
                ?: findAfterLabel(t, MOBILE_LABEL, LANDLINE)
            val labelledTel = findAfterLabel(t, TEL_LABEL, LANDLINE)
                ?: findAfterLabel(t, TEL_LABEL, MOBILE)

            labelledFax?.let { value ->
                if (fax.isBlank()) { fax = clean(value); contactMatched = true }
            }
            labelledMobile?.let { value ->
                if (mobile.isBlank()) { mobile = clean(value); contactMatched = true }
            }
            labelledTel?.let { value ->
                if (phone.isBlank()) {
                    val ext = extensionAfter(t, value)
                    phone = clean(value) + (ext?.let { " 分機 $it" } ?: "")
                    contactMatched = true
                }
            }

            val hasPhoneLabel = labelledFax != null || labelledMobile != null || labelledTel != null ||
                FAX_LABEL.any { t.contains(it) } || MOBILE_LABEL.any { t.contains(it) } ||
                TEL_LABEL.any { t.contains(it) }
            if (!hasPhoneLabel) {
                val mobileMatch = MOBILE.find(t)?.value
                val landlineMatch = LANDLINE.find(t)?.value
                when {
                    mobile.isBlank() && mobileMatch != null && isMobileShape(mobileMatch) -> {
                        mobile = clean(mobileMatch); contactMatched = true
                    }
                    phone.isBlank() && landlineMatch != null -> {
                        val ext = extensionAfter(t, landlineMatch)
                        phone = clean(landlineMatch) + (ext?.let { " 分機 $it" } ?: "")
                        contactMatched = true
                    }
                }
            }

            if (website.isBlank() && !t.contains("@")) {
                WEBSITE.find(t)?.let { website = it.value; contactMatched = true }
            }

            if (contactMatched) {
                consumed += idx
                return@forEachIndexed
            }

            if (address.isBlank() && looksLikeAddress(t)) {
                address = stripLabel(t, listOf("地址", "Address", "ADD", "Add"))
                consumed += idx
                return@forEachIndexed
            }

            // Capture both language variants, even when OCR merges them into one line.
            if ((company.isBlank() || companyEn.isBlank()) && looksLikeCompany(t)) {
                val split = splitBilingual(t)
                when {
                    split != null -> {
                        if (company.isBlank()) company = split.first
                        if (companyEn.isBlank()) companyEn = split.second
                    }
                    t.any(Char::isCjk) && company.isBlank() -> company = t
                    t.any(Char::isAsciiLetter) && companyEn.isBlank() -> companyEn = t
                }
                consumed += idx
                return@forEachIndexed
            }

            if (title.isBlank() && TITLE_HINTS.any { t.contains(it, ignoreCase = true) } && t.length <= 40) {
                title = t; consumed += idx; return@forEachIndexed
            }

            if (department.isBlank() && DEPT_HINTS.any { t.contains(it, ignoreCase = true) } && t.length <= 40) {
                department = t; consumed += idx
            }
        }

        val (name, nameEn) = guessNames(lines, consumed)

        return BusinessCard(
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
            rawTextFront = front.rawText,
            rawTextBack = back.rawText
        )
    }

    /** Returns local/CJK name to English/Latin name. */
    private fun guessNames(lines: List<OcrLine>, consumed: Set<Int>): Pair<String, String> {
        val candidates = lines.filterIndexed { i, line ->
            i !in consumed && line.text.isNotBlank() && !line.text.any { it.isDigit() }
        }.filterNot { line ->
            looksLikeCompany(line.text) ||
                TITLE_HINTS.any { line.text.contains(it, ignoreCase = true) } ||
                DEPT_HINTS.any { line.text.contains(it, ignoreCase = true) }
        }

        var zh = ""
        var en = ""

        // Strongest case: OCR merged "王大明 David Wang" into a single line.
        candidates.sortedByDescending { it.height }.forEach { line ->
            val split = splitBilingual(line.text) ?: return@forEach
            if (zh.isBlank() && isLikelyChineseName(split.first)) zh = split.first
            if (en.isBlank() && isLikelyEnglishName(split.second)) en = split.second
        }

        val maxHeight = lines.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: 1

        if (zh.isBlank()) {
            zh = candidates
                .filter { isLikelyChineseName(it.text.trim()) }
                .maxByOrNull { line ->
                    val compact = line.text.replace(" ", "")
                    (line.height.toDouble() / maxHeight) * 100 +
                        if (compact.length in 2..4) 60 else 0
                }
                ?.text?.trim().orEmpty()
        }

        if (en.isBlank()) {
            en = candidates
                .filter { isLikelyEnglishName(it.text.trim()) }
                .maxByOrNull { line ->
                    val words = englishWords(line.text)
                    var score = (line.height.toDouble() / maxHeight) * 100
                    if (words.size in 2..3) score += 45
                    if (words.all { w -> w.firstOrNull()?.isUpperCase() == true }) score += 15
                    if (line.text.count(Char::isUpperCase) == line.text.count(Char::isLetter)) score -= 10
                    score
                }
                ?.text?.trim().orEmpty()
        }

        return zh to en
    }

    private fun looksLikeCompany(text: String): Boolean {
        val t = text.trim()
        if (COMPANY_HINTS.any { t.contains(it, ignoreCase = true) }) return true
        if (!t.any(Char::isAsciiLetter)) return false
        val words = englishWords(t)
        val upper = t.count(Char::isUpperCase)
        val letters = t.count(Char::isLetter).coerceAtLeast(1)
        return words.size >= 2 && upper.toDouble() / letters >= 0.65 && t.length >= 8
    }

    /** Splits a mixed CJK/Latin line at the first script boundary. */
    private fun splitBilingual(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val firstCjk = text.indexOfFirst(Char::isCjk)
        val firstLatin = text.indexOfFirst(Char::isAsciiLetter)
        if (firstCjk < 0 || firstLatin < 0) return null

        val zh: String
        val en: String
        if (firstCjk < firstLatin) {
            zh = text.substring(0, firstLatin).trimLanguageBoundary()
            en = text.substring(firstLatin).trimLanguageBoundary()
        } else {
            en = text.substring(0, firstCjk).trimLanguageBoundary()
            zh = text.substring(firstCjk).trimLanguageBoundary()
        }
        return if (zh.any(Char::isCjk) && en.any(Char::isAsciiLetter)) zh to en else null
    }

    private fun isLikelyChineseName(text: String): Boolean {
        val compact = text.replace(" ", "")
        return compact.length in 2..4 && compact.all(Char::isCjk)
    }

    private fun isLikelyEnglishName(text: String): Boolean {
        if (!text.any(Char::isAsciiLetter) || text.any { it.isDigit() }) return false
        if (COMPANY_HINTS.any { text.contains(it, ignoreCase = true) }) return false
        if (TITLE_HINTS.any { text.contains(it, ignoreCase = true) }) return false
        if (DEPT_HINTS.any { text.contains(it, ignoreCase = true) }) return false
        val words = englishWords(text)
        if (words.size !in 1..4) return false
        return text.all { it.isAsciiLetter() || it == ' ' || it == '-' || it == '\'' || it == '.' }
    }

    private fun englishWords(text: String): List<String> = text
        .split(Regex("[\\s./]+"))
        .map { it.trim('-', '\'', '.') }
        .filter { it.any(Char::isAsciiLetter) }

    private fun findAfterLabel(text: String, labels: List<String>, pattern: Regex): String? {
        labels.forEach { label ->
            val index = text.indexOf(label)
            if (index >= 0) pattern.find(text.substring(index + label.length))?.let { return it.value }
        }
        return null
    }

    private fun extensionAfter(text: String, phoneValue: String): String? {
        val start = text.indexOf(phoneValue).takeIf { it >= 0 } ?: return null
        return EXTENSION.find(text.substring(start + phoneValue.length))?.groupValues?.get(1)
    }

    private fun isMobileShape(s: String): Boolean {
        val digits = s.filter { it.isDigit() }
        return digits.length >= 9 && (digits.startsWith("09") || digits.startsWith("8869"))
    }

    private fun looksLikeAddress(t: String): Boolean {
        val hits = ADDRESS_HINTS.count { t.contains(it) }
        val hasStreet = listOf("路", "街", "大道", "Rd", "St.", "Road").any { t.contains(it) }
        val hasNumber = t.contains("號") || Regex("""\d""").containsMatchIn(t)
        return (hits >= 3 && hasNumber) || (hasStreet && hasNumber && t.length >= 8)
    }

    private fun stripLabel(t: String, labels: List<String>): String {
        var out = t
        labels.forEach { out = out.replace(it, "") }
        return out.trimStart(':', '：', ' ', '.', '-').trim()
    }

    private fun String.trimLanguageBoundary(): String =
        trim().trim(' ', '/', '|', '·', ':', '：').trim()

    private fun Char.isCjk(): Boolean = code in 0x4E00..0x9FFF
    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private fun clean(s: String) = s.trim().replace(Regex("""\s+"""), "-").trim('-')
}
