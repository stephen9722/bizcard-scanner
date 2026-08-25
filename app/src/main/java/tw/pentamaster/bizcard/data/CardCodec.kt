package tw.pentamaster.bizcard.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversions between BusinessCard and the three portable formats.
 *
 * Kept free of Android and IO types so it can be unit-tested on the JVM.
 */
object CardCodec {

    // ---- JSON (full fidelity, used inside the ZIP backup) ----------------

    fun toJson(card: BusinessCard): JSONObject = JSONObject().apply {
        put("id", card.id)
        put("name", card.name)
        put("company", card.company)
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
        id = 0, // always inserted as a new row; the old id is meaningless in this DB
        name = o.optString("name"),
        company = o.optString("company"),
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
        "姓名", "公司", "職稱", "部門", "手機", "電話", "傳真",
        "Email", "網站", "地址", "標籤", "備註",
        "正面圖檔", "背面圖檔", "建立時間", "更新時間"
    )

    fun toCsvRow(card: BusinessCard): String = listOf(
        card.name, card.company, card.title, card.department,
        card.mobile, card.phone, card.fax,
        card.email, card.website, card.address, card.tags, card.notes,
        card.frontImage, card.backImage,
        card.createdAt.toString(), card.updatedAt.toString()
    ).joinToString(",") { csvEscape(it) }

    /**
     * RFC 4180 quoting. Note the leading-symbol guard: a card whose name starts with
     * = + - or @ would otherwise be interpreted as a formula when the CSV is opened in
     * Excel, which is both a correctness bug and a well-known injection vector.
     */
    fun csvEscape(raw: String): String {
        val v = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    // ---- vCard 3.0 --------------------------------------------------------

    fun toVCard(card: BusinessCard, photoBase64: String? = null): String = buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("N:${vEscape(card.name)};;;;")
        appendLine("FN:${vEscape(card.displayName)}")
        if (card.company.isNotBlank() || card.department.isNotBlank()) {
            appendLine("ORG:${vEscape(card.company)};${vEscape(card.department)}")
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
            // Folded to 75 chars per line as the spec requires; some readers choke otherwise.
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
