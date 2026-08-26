package tw.pentamaster.bizcard.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class CsvImportManager(private val context: Context) {

    private val dao = AppDatabase.get(context).cardDao()

    suspend fun importCsv(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var skipped = 0
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                readLimited(it, MAX_CSV_BYTES)
            } ?: return@withContext ImportResult(0, 0, 0, "無法讀取這個 CSV")

            val incomingCards = CsvCardImporter.parse(bytes)
            if (incomingCards.isEmpty()) {
                return@withContext ImportResult(0, 0, 0, "CSV 裡沒有找到可匯入的聯絡人")
            }

            val existing = dao.allOnce()
            val emailKeys = existing.mapNotNull { emailKey(it.email) }.toMutableSet()
            val mobileKeys = existing.mapNotNull { mobileKey(it.mobile) }.toMutableSet()
            val nameCompanyKeys = existing.mapNotNull { nameCompanyKey(it) }.toMutableSet()

            incomingCards.forEach { card ->
                val eKey = emailKey(card.email)
                val mKey = mobileKey(card.mobile)
                val ncKey = nameCompanyKey(card)

                val duplicate =
                    (eKey != null && eKey in emailKeys) ||
                    (mKey != null && mKey in mobileKeys) ||
                    (eKey == null && mKey == null && ncKey != null && ncKey in nameCompanyKeys)

                if (duplicate) {
                    skipped++
                    return@forEach
                }

                dao.insert(card.copy(id = 0))
                added++
                eKey?.let(emailKeys::add)
                mKey?.let(mobileKeys::add)
                ncKey?.let(nameCompanyKeys::add)
            }

            ImportResult(added, skipped, 0)
        } catch (e: Exception) {
            ImportResult(added, skipped, 0, e.message ?: "CSV 匯入失敗")
        }
    }

    private fun emailKey(raw: String): String? = raw
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() }

    private fun mobileKey(raw: String): String? = CardRepository.mobileDuplicateKey(raw)
        .takeIf { it.isNotBlank() }

    private fun nameCompanyKey(card: BusinessCard): String? {
        val name = normalize(card.name)
        val company = normalize(card.company)
        if (name.isBlank() || company.isBlank()) return null
        return "$name|$company"
    }

    private fun normalize(raw: String): String = raw
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), "")

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("CSV 檔案過大")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_CSV_BYTES = 20L * 1024 * 1024
    }
}
