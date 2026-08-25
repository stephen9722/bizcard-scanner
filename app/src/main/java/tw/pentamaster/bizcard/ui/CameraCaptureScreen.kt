package tw.pentamaster.bizcard.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.pentamaster.bizcard.ocr.CardOcr
import tw.pentamaster.bizcard.ocr.CardParser
import tw.pentamaster.bizcard.ocr.OcrResult
import tw.pentamaster.bizcard.util.CardImageProcessor
import tw.pentamaster.bizcard.util.ImageStore
import java.io.File
import java.util.concurrent.Executors

private enum class Side { FRONT, BACK }

/**
 * Two-step capture: front, then optionally back. Both photos are OCR'd and merged into
 * one draft card before handing off to the edit screen.
 */
@Composable
fun CameraCaptureScreen(
    vm: CardViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onManualEntry: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var side by remember { mutableStateOf(Side.FRONT) }
    var busy by remember { mutableStateOf(false) }
    var frontOcr by remember { mutableStateOf(OcrResult.EMPTY) }
    var keepDraft by remember { mutableStateOf(false) }
    val keepDraftState = rememberUpdatedState(keepDraft)

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            if (!keepDraftState.value) vm.discardNewCard()
        }
    }

    if (!hasPermission) {
        PermissionPrompt(
            onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onManualEntry = {
                keepDraft = true
                onManualEntry()
            }
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (_: Exception) {
                        // Device has no usable back camera; the shutter will simply do nothing.
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Framing guide roughly matching a 91×55mm card in landscape
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .aspectRatio(91f / 55f)
                .border(2.dp, Color.White.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
        )

        Text(
            text = if (side == Side.FRONT) "拍攝正面" else "拍攝背面",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text("取消", color = Color.White)
            }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    val target = ImageStore.newFile(
                        context,
                        if (side == Side.FRONT) "front" else "back"
                    )
                    takePhoto(imageCapture, target, executor,
                        onError = {
                            target.delete()
                            busy = false
                        },
                        onSaved = {
                            scope.launch {
                                val ocr = withContext(Dispatchers.Default) {
                                    // Straighten/crop the card first. If edge confidence is low the
                                    // processor leaves the original photo untouched, so OCR still works.
                                    CardImageProcessor.rectifyInPlace(target)
                                    CardOcr.read(context, target)
                                }
                                if (side == Side.FRONT) {
                                    frontOcr = ocr
                                    vm.updateDraft { it.copy(frontImage = target.name) }
                                    vm.mergeParsed(CardParser.parse(ocr))
                                    side = Side.BACK
                                    busy = false
                                } else {
                                    vm.updateDraft { it.copy(backImage = target.name) }
                                    vm.mergeParsed(CardParser.parse(frontOcr, ocr))
                                    busy = false
                                    keepDraft = true
                                    onDone()
                                }
                            }
                        }
                    )
                }
            ) {
                Text(if (side == Side.FRONT) "拍正面" else "拍背面")
            }

            TextButton(
                enabled = !busy,
                onClick = {
                    if (side == Side.FRONT) {
                        side = Side.BACK
                    } else {
                        keepDraft = true
                        onDone()
                    }
                }
            ) {
                Text(if (side == Side.FRONT) "略過" else "完成", color = Color.White)
            }
        }

        if (busy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("校正與辨識中…", color = Color.White)
                }
            }
        }
    }
}

private fun takePhoto(
    imageCapture: ImageCapture,
    target: File,
    executor: java.util.concurrent.Executor,
    onSaved: () -> Unit,
    onError: (Exception) -> Unit
) {
    val options = ImageCapture.OutputFileOptions.Builder(target).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onSaved() }
            }

            override fun onError(exception: ImageCaptureException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onError(exception) }
            }
        }
    )
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onManualEntry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要相機權限", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "掃描名片要用到相機。照片只存在這支手機裡,不會上傳。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text("允許使用相機") }
        TextButton(onClick = onManualEntry) { Text("改成手動輸入") }
    }
}
