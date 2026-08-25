package tw.pentamaster.bizcard.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** One recognised line, plus how tall it was drawn on the card. */
data class OcrLine(
    val text: String,
    val height: Int,
    val top: Int,
    val left: Int
)

data class OcrResult(
    val rawText: String,
    val lines: List<OcrLine>
) {
    companion object {
        val EMPTY = OcrResult("", emptyList())
    }
}

/**
 * On-device text recognition. The Chinese recognizer bundle handles Traditional Chinese,
 * Simplified and Latin in one pass, which is what a Taiwanese business card actually
 * contains — a separate Latin recognizer would lose the Chinese half.
 *
 * The model ships inside the APK, so this works in aeroplane mode and nothing is uploaded.
 */
object CardOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun read(context: Context, file: File): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = try {
                // fromFilePath reads EXIF, so a sideways photo is still recognised correctly
                InputImage.fromFilePath(context, Uri.fromFile(file))
            } catch (e: Exception) {
                cont.resume(OcrResult.EMPTY)
                return@suspendCancellableCoroutine
            }

            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            val t = line.text.trim()
                            if (t.isEmpty()) null
                            else OcrLine(t, box.height(), box.top, box.left)
                        }
                        .sortedBy { it.top }
                    cont.resume(OcrResult(text.text, lines))
                }
                .addOnFailureListener {
                    cont.resume(OcrResult.EMPTY)
                }
        }
}
