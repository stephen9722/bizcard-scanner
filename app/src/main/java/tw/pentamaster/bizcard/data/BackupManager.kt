package tw.pentamaster.bizcard.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tw.pentamaster.bizcard.util.ImageStore
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ExportResult(val cards: Int, val images: Int, val bytes: Long)
data class ImportResult(val added: Int, val skipped: Int, val images: Int, val error: String? = null)

/**
 * All reading and writing goes through a Uri the user picked in the system file picker,
 * so the app needs no storage permission and the backup lands wherever they chose —
 * Drive, Files, a USB stick, an email draft.
 */
class BackupManager(private val context: Context) {

    private val dao = AppDatabase.get(context).cardDao()

    fun suggestedName(ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "名片備份-$stamp.$ext"
    }

    // ---- export -----------------------------------------------------------

    /**
     * Full backup: JSON (lossless), CSV (readable), and every card photo.
     * This is the one to restore from; the other two formats lose the images.
     */
    suspend fun exportZip(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val cards = dao.allOnce()
        var images = 0
        var bytes = 0L

        val raw = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("無法建立匯出檔")
        raw.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->

                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(
                    JSONObject().apply {
                        put("app", "tw.pentamaster.bizcard")
                        put("formatVersion", 1)
                        put("exportedAt", System.currentTimeMillis())
                        put("cardCount", cards.size)
                    }.toString(2).toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("cards.json"))
                zip.write(CardCodec.toJsonArray(cards).toString(2).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("cards.csv"))
                zip.write(csvBytes(cards))
                zip.closeEntry()

                cards.forEach { card ->
                    listOf(card.frontImage, card.backImage).forEach { fileName ->
                        ImageStore.resolve(context, fileName)?.let { f ->
                            zip.putNextEntry(ZipEntry("images/$fileName"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            images++
                            bytes += f.length()
                        }
                    }
                }
            }
        }

        ExportResult(cards.size, images, bytes)
    }

    /** Spreadsheet export. Text only — images are not in a CSV. */
    suspend fun exportCsv(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val cards = dao.allOnce()
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("無法建立 CSV 匯出檔")
        out.use { it.write(csvBytes(cards)) }
        ExportResult(cards.size, 0, 0)
    }

    /** vCard, for loading into the phone's contacts app or another card app. */
    suspend fun exportVCard(uri: Uri, includePhotos: Boolean): ExportResult =
        withContext(Dispatchers.IO) {
            val cards = dao.allOnce()
            var images = 0
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("無法建立 vCard 匯出檔")
            stream.use { out ->
                cards.forEach { card ->
                    val photo = if (includePhotos) {
                        ImageStore.resolve(context, card.frontImage)?.let { f ->
                            images++
                            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                        }
                    } else null
                    out.write(CardCodec.toVCard(card, photo).toByteArray(Charsets.UTF_8))
                }
            }
            ExportResult(cards.size, images, 0)
        }

    /**
     * UTF-8 BOM up front. Without it, Excel on a Chinese Windows opens the file as Big5
     * and every name turns into mojibake — the single most common complaint about
     * CSV exports from Chinese-language apps.
     */
    private fun csvBytes(cards: List<BusinessCard>): ByteArray {
        val sb = StringBuilder()
        sb.append(CardCodec.CSV_HEADERS.joinToString(",")).append("\r\n")
        cards.forEach { sb.append(CardCodec.toCsvRow(it)).append("\r\n") }
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ---- import -----------------------------------------------------------

    /**
     * Restores a ZIP backup by adding to what's already there, never wiping it.
     * Cards whose email or mobile already exist are skipped, so restoring the same
     * backup twice does not double every contact.
     */
    suspend fun importZip(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        var imageCount = 0

        val stageDir = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var cardsJson: String? = null
            val staged = mutableMapOf<String, File>()
            var entries = 0
            var totalUncompressed = 0L

            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        entries++
                        if (entries > MAX_ZIP_ENTRIES) throw IOException("備份檔項目過多")

                        val name = entry.name
                        when {
                            name == "cards.json" -> {
                                val bytes = readLimited(zip, MAX_CARDS_JSON_BYTES)
                                totalUncompressed += bytes.size
                                cardsJson = bytes.toString(Charsets.UTF_8)
                            }

                            name.startsWith("images/") && !entry.isDirectory -> {
                                val base = File(name).name
                                // Guard against a crafted zip escaping the staging dir.
                                if (base.isNotBlank() && !base.contains("..")) {
                                    val tmp = File(stageDir, base)
                                    val written = tmp.outputStream().use {
                                        copyLimited(zip, it, MAX_IMAGE_BYTES)
                                    }
                                    totalUncompressed += written
                                    staged[base] = tmp
                                }
                            }
                        }
                        if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw IOException("備份檔解壓後過大")
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext ImportResult(0, 0, 0, "無法讀取這個檔案")

            val json = cardsJson
                ?: return@withContext ImportResult(0, 0, 0, "這不是名片簿的備份檔(找不到 cards.json)")

            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val incoming = CardCodec.fromJson(arr.getJSONObject(i))

                if (dao.findDuplicate(0, incoming.email.trim(), CardRepository.mobileDuplicateKey(incoming.mobile)) != null) {
                    skipped++
                    continue
                }

                // Copy images in under fresh names so a restore can never overwrite
                // a photo belonging to a card already on this phone.
                val front = staged[incoming.frontImage]?.let { copyIn(it, "front") } ?: ""
                val back = staged[incoming.backImage]?.let { copyIn(it, "back") } ?: ""
                if (front.isNotBlank()) imageCount++
                if (back.isNotBlank()) imageCount++

                dao.insert(incoming.copy(frontImage = front, backImage = back))
                added++
            }
            ImportResult(added, skipped, imageCount)
        } catch (e: Exception) {
            ImportResult(added, skipped, imageCount, e.message ?: "還原失敗")
        } finally {
            stageDir.deleteRecursively()
        }
    }

    /** Import a .vcf exported from another card app or a phone's contacts. */
    suspend fun importVCard(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        var images = 0
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext ImportResult(0, 0, 0, "無法讀取這個檔案")

            val parsed = VCardReader.parse(text)
            if (parsed.isEmpty()) {
                return@withContext ImportResult(0, 0, 0, "檔案裡沒有找到名片資料")
            }

            parsed.forEach { p ->
                if (p.card.name.isBlank() && p.card.company.isBlank()) { skipped++; return@forEach }
                if (dao.findDuplicate(0, p.card.email.trim(), CardRepository.mobileDuplicateKey(p.card.mobile)) != null) { skipped++; return@forEach }

                val photoName = p.photoBytes?.let { bytes ->
                    val f = ImageStore.newFile(context, "front")
                    f.writeBytes(bytes)
                    images++
                    f.name
                }.orEmpty()

                dao.insert(p.card.copy(frontImage = photoName))
                added++
            }
            ImportResult(added, skipped, images)
        } catch (e: Exception) {
            ImportResult(added, skipped, images, e.message ?: "匯入失敗")
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        copyLimited(input, out, maxBytes)
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("備份檔中的單一項目過大")
            output.write(buffer, 0, read)
        }
        return total
    }

    private companion object {
        const val MAX_ZIP_ENTRIES = 5_000
        const val MAX_CARDS_JSON_BYTES = 20L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 500L * 1024 * 1024
    }

    private fun copyIn(source: File, side: String): String {
        val target = ImageStore.newFile(context, side)
        source.copyTo(target, overwrite = true)
        return target.name
    }
}
