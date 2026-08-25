package tw.pentamaster.bizcard.ocr

import tw.pentamaster.bizcard.data.BusinessCard

/**
 * Turns OCR lines into structured fields, tuned for Taiwanese business cards.
 *
 * Deliberately conservative: when a line can't be classified with confidence it is left
 * alone rather than guessed into the wrong field. Everything stays in rawText anyway, so
 * search still finds it and the user can correct it on the edit screen.
 */
object CardParser {

    // 09xx-xxx-xxx, +886-9xx-xxx-xxx, 0912 345 678
    private val MOBILE = Regex("""(?:\+?886[-\s]?)?0?9\d{2}[-\s.]?\d{3}[-\s.]?\d{3}""")

    // (03)5xx-xxxx, 03-5731234, 02-2345-6789, +886-3-5731234
    // International format drops the domestic leading zero after +886.
    private val LANDLINE = Regex(
        """(?:(?:\+?886[-\s.]?\(?\d{1,2}\)?)|(?:\(?0\d{1,2}\)?))[-\s.]?\d{3,4}[-\s.]?\d{3,4}"""
    )

    private val EXTENSION = Regex("""(?:ext|EXT|Ext|分機|轉)\.?\s*#?\s*(\d{1,6})""")

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    // Generic TLD support (.ai, .tech, .biz, country domains, etc.) instead of a fixed allow-list.
    private val WEBSITE = Regex(
        """(?i)(?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)*\.[a-z]{2,63}(?:[/:?#]\S*)?"""
    )

    private val COMPANY_HINTS = listOf(
        "股份有限公司", "有限公司", "企業社", "工作室", "事務所", "實業", "工業", "科技",
        "國際", "集團", "企業", "公司", "電子", "設備", "機械", "貿易", "顧問",
        "Co.", "Ltd", "Inc", "Corp", "Company", "Technolog", "Industr", "Group"
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
        // Prefer whichever side produced more text as the "primary" side for field extraction
        val primary = if (back.lines.sumOf { it.text.length } > front.lines.sumOf { it.text.length }) back else front
        val lines = (primary.lines + (if (primary === front) back.lines else front.lines))

        var email = ""
        var website = ""
        var mobile = ""
        var phone = ""
        var fax = ""
        var address = ""
        var company = ""
        var title = ""
        var department = ""

        val consumed = mutableSetOf<Int>()

        lines.forEachIndexed { idx, line ->
            val t = line.text
            var contactMatched = false

            // A single OCR line often contains several contact fields, e.g.
            // "T: 03-...  F: 03-...  M: 09...". Extract each labelled value from
            // the text after its own label instead of consuming the line after the first match.
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

            // Only use the generic first-number fallback on an unlabelled line. Otherwise a
            // fax-only line could accidentally fill the phone field with the same number.
            val hasPhoneLabel = labelledFax != null || labelledMobile != null || labelledTel != null ||
                FAX_LABEL.any { t.contains(it) } || MOBILE_LABEL.any { t.contains(it) } ||
                TEL_LABEL.any { t.contains(it) }
            if (!hasPhoneLabel) {
                val mobileMatch = MOBILE.find(t)?.value
                val landlineMatch = LANDLINE.find(t)?.value
                when {
                    mobile.isBlank() && mobileMatch != null && isMobileShape(mobileMatch) -> {
                        mobile = clean(mobileMatch)
                        contactMatched = true
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

            if (company.isBlank() && COMPANY_HINTS.any { t.contains(it, ignoreCase = true) }) {
                company = t; consumed += idx; return@forEachIndexed
            }

            if (title.isBlank() && TITLE_HINTS.any { t.contains(it, ignoreCase = true) } && t.length <= 24) {
                title = t; consumed += idx; return@forEachIndexed
            }

            if (department.isBlank() && DEPT_HINTS.any { t.contains(it, ignoreCase = true) } && t.length <= 24) {
                department = t; consumed += idx
            }
        }

        val name = guessName(lines, consumed)

        return BusinessCard(
            name = name,
            company = company,
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

    /**
     * The person's name is almost always the largest text on a Taiwanese card, and it is
     * almost always 2–4 Chinese characters. Combining "tall" with "short and CJK" is far
     * more reliable than either signal alone — a company name is often equally tall but
     * much longer, and a 3-character department label is short but small.
     */
    private fun guessName(lines: List<OcrLine>, consumed: Set<Int>): String {
        val candidates = lines.filterIndexed { i, _ -> i !in consumed }
            .filter { it.text.length in 2..18 }
            .filter { !it.text.any { c -> c.isDigit() } }
            .filter { line -> COMPANY_HINTS.none { line.text.contains(it, ignoreCase = true) } }
            .filter { line -> TITLE_HINTS.none { line.text.contains(it, ignoreCase = true) } }

        if (candidates.isEmpty()) return ""

        val maxHeight = lines.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: 1

        return candidates.maxByOrNull { line ->
            var score = (line.height.toDouble() / maxHeight) * 100
            val cjk = line.text.count { it.code in 0x4E00..0x9FFF }
            if (cjk in 2..4 && cjk == line.text.replace(" ", "").length) score += 60
            else if (cjk in 2..4) score += 30
            if (line.text.length <= 4) score += 15
            score
        }!!.text.trim()
    }


    private fun findAfterLabel(text: String, labels: List<String>, pattern: Regex): String? {
        labels.forEach { label ->
            val index = text.indexOf(label)
            if (index >= 0) {
                pattern.find(text.substring(index + label.length))?.let { return it.value }
            }
        }
        return null
    }

    /** Extension belongs to the number before it, not to another number earlier on the line. */
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

    private fun clean(s: String) = s.trim().replace(Regex("""\s+"""), "-").trim('-')
}
