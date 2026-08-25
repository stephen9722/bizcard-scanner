package tw.pentamaster.bizcard.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.ImageStore
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CardDetailScreen(
    vm: CardViewModel,
    cardId: Long,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    var card by remember { mutableStateOf<BusinessCard?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var zoomFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(cardId) { vm.byId(cardId) { card = it } }

    val c = card
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.displayName ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "編輯")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除")
                    }
                }
            )
        }
    ) { padding ->
        if (c == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            val images = listOfNotNull(
                ImageStore.resolve(context, c.frontImage)?.let { "正面" to it },
                ImageStore.resolve(context, c.backImage)?.let { "背面" to it }
            )

            if (images.isNotEmpty()) {
                val pager = rememberPagerState { images.size }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(91f / 55f)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) { page ->
                    val (label, file) = images[page]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { zoomFile = file }
                    ) {
                        AsyncImage(
                            model = file,
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (images.size > 1) {
                    Text(
                        "左右滑動看背面 · 點一下放大",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 8.dp)
                    )
                }
            }

            DetailRow("公司", c.company)
            DetailRow("職稱", c.title)
            DetailRow("部門", c.department)
            DetailRow("手機", c.mobile) { dial(context, c.mobile) }
            DetailRow("電話", c.phone) { dial(context, c.phone) }
            DetailRow("傳真", c.fax)
            DetailRow("Email", c.email) { mail(context, c.email) }
            DetailRow("網站", c.website) { open(context, c.website) }
            DetailRow("地址", c.address) { map(context, c.address) }
            DetailRow("備註", c.notes)

            if (c.tagList.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    c.tagList.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && c != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除這張名片?") },
            text = { Text("「${c.displayName}」的資料和正反面照片都會一起刪除,無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(c) { onDeleted() }
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    zoomFile?.let { file ->
        Dialog(onDismissRequest = { zoomFile = null }) {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { zoomFile = null }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    if (value.isBlank()) return
    val base = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp)

    Column(base) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

private fun dial(context: android.content.Context, number: String) =
    safeStart(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}")))

private fun mail(context: android.content.Context, address: String) =
    safeStart(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))

private fun open(context: android.content.Context, url: String) {
    val normalized = if (url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)) url else "https://$url"
    safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
}

private fun map(context: android.content.Context, address: String) =
    safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}")))

private fun safeStart(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No app installed to handle it — silently ignore rather than crash.
    }
}
