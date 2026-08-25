package tw.pentamaster.bizcard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One business card.
 *
 * Images are stored as *file names* only (e.g. "card_1712345678_a1b2c3.jpg"), never as BLOBs.
 * The bytes live in filesDir/cards/. Keeping the DB small keeps search fast and backups sane.
 */
@Entity(tableName = "cards")
data class BusinessCard(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "",
    val company: String = "",
    val title: String = "",
    val department: String = "",
    val phone: String = "",
    val mobile: String = "",
    val fax: String = "",
    val email: String = "",
    val website: String = "",
    val address: String = "",

    /** Comma-separated, e.g. "客戶,台積電,2026展會" */
    val tags: String = "",
    val notes: String = "",

    /** Full OCR output, kept so search can fall back to anything the parser missed. */
    val rawTextFront: String = "",
    val rawTextBack: String = "",

    val frontImage: String = "",
    val backImage: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** What to show in the list when OCR found no name. */
    val displayName: String
        get() = name.ifBlank { company }.ifBlank { "(未命名名片)" }
}
