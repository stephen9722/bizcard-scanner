package tw.pentamaster.bizcard.util

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Card images live in filesDir/cards/ — app-private storage, so they are excluded from
 * the media scanner (your card photos will not show up in Google Photos) and are removed
 * cleanly when the app is uninstalled.
 */
object ImageStore {

    private const val DIR = "cards"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun newFile(context: Context, side: String): File =
        File(dir(context), "card_${System.currentTimeMillis()}_${side}_${UUID.randomUUID().toString().take(6)}.jpg")

    /** Resolves a stored file name back to a File, or null when the field is blank/missing. */
    fun resolve(context: Context, fileName: String): File? {
        if (fileName.isBlank()) return null
        val f = File(dir(context), fileName)
        return if (f.exists()) f else null
    }

    fun delete(context: Context, fileName: String) {
        resolve(context, fileName)?.delete()
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
