package tw.pentamaster.bizcard.data

import org.json.JSONArray
import org.json.JSONObject

/** Portable JSON/CSV/vCard representations. */
object CardCodec {

    // ---- JSON (full fidelity, used inside the ZIP backup) ----------------

    fun toJson(card: BusinessCard): JSONObject = JSONObject().apply {
        put("id", card.id)
        put("name", card.name)
        put("nameEn", card.nameEn)
        put("company", card.company)
        put("companyEn", card.companyEn)
        put("title", card.title)
        put("department", card.department)
        put("phone", card.phone)
        put("mobile", card.mobile)
        put("fax", card.fax)
        put("email", card.email)
        put("website", card.website)
        put("address", card.address)
        put("tags", card.tags)
        put("notes", card.notes)
        put("rawTextFront", card.rawTextFront)
        put("rawTextBack", card.rawTextBack)
        put("frontImage", card.frontImage)
        put("backImage", card.backImage)
        put("createdAt", card.createdAt)
        put("updatedAt", card.updatedAt)
    }

    fun fromJson(o: JSONObject): BusinessCard = BusinessCard(
        id = 0,
        name = o.optString("name"),
        nameEn = o.optString("nameEn"),
        company = o.optString("company"),
        companyEn = o.optString("companyEn"),
        title = o.optString("title"),
        department = o.optString("department"),
        phone = o.optString("phone"),
        mobile = o.optString("mobile"),
        fax = o.optString("fax"),
        email = o.optString("email"),
        website = o.optString("website"),
        address = o.optString("address"),
        tags = o.optString("tags"),
        notes = o.optString("notes"),
        rawTextFront = o.optString("rawTextFront"),
        rawTextBack = o.optString("rawTextBack"),
        frontImage = o.optString("frontImage"),
        backImage = o.optString("backImage"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    fun toJsonArray(cards: List<BusinessCard>): JSONArray =
        JSONArray().apply { cards.forEach { put(toJson(it)) } }

    // ---- CSV --------------------------------------------------------------

    val CSV_HEADERS = listOf(
        "姓名(中文)", "姓名(英文)", "公司(中文)", "公司(英文)",
        "職稱", "部門", "手機", "電話", "傳真",
        "Email", "網站", "地址", "標籤", "備註",
        "正面圖檔", "背面圖檔", "建立時間", "更新時間"
    )

    fun toCsvRow(card: BusinessCard): String = listOf(
        card.name, card.nameEn, card.company, card.companyEn,
        card.title, card.department, card.mobile, card.phone, card.fax,
        card.email, card.website, card.address, card.tags, card.notes,
        card.frontImage, card.backImage,
        card.createdAt.toString(), card.updatedAt.toString()
    ).joinToString(",") { csvEscape(it) }

    fun csvEscape(raw: String): String {
        val v = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    // ---- vCard 3.0 --------------------------------------------------------

    fun toVCard(card: BusinessCard, photoBase64: String? = null): String = buildString {
        val primaryName = card.name.ifBlank { card.nameEn }
        val primaryCompany = card.company.ifBlank { card.companyEn }

        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("N:${vEscape(primaryName)};;;;")
        appendLine("FN:${vEscape(card.displayName)}")
        if (card.nameEn.isNotBlank() && card.nameEn != primaryName) {
            appendLine("X-BIZCARD-NAME-EN:${vEscape(card.nameEn)}")
        }
        if (primaryCompany.isNotBlank() || card.department.isNotBlank()) {
            appendLine("ORG:${vEscape(primaryCompany)};${vEscape(card.department)}")
        }
        if (card.companyEn.isNotBlank() && card.companyEn != primaryCompany) {
            appendLine("X-BIZCARD-COMPANY-EN:${vEscape(card.companyEn)}")
        }
        if (card.title.isNotBlank()) appendLine("TITLE:${vEscape(card.title)}")
        if (card.mobile.isNotBlank()) appendLine("TEL;TYPE=CELL:${card.mobile}")
        if (card.phone.isNotBlank()) appendLine("TEL;TYPE=WORK,VOICE:${card.phone}")
        if (card.fax.isNotBlank()) appendLine("TEL;TYPE=WORK,FAX:${card.fax}")
        if (card.email.isNotBlank()) appendLine("EMAIL;TYPE=INTERNET,WORK:${card.email}")
        if (card.website.isNotBlank()) appendLine("URL:${card.website}")
        if (card.address.isNotBlank()) appendLine("ADR;TYPE=WORK:;;${vEscape(card.address)};;;;")
        if (card.tags.isNotBlank()) appendLine("CATEGORIES:${card.tagList.joinToString(",")}")
        if (card.notes.isNotBlank()) appendLine("NOTE:${vEscape(card.notes)}")
        if (photoBase64 != null) {
            appendLine("PHOTO;ENCODING=b;TYPE=JPEG:")
            photoBase64.chunked(74).forEach { appendLine(" $it") }
        }
        appendLine("END:VCARD")
    }

    private fun vEscape(s: String) = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}
