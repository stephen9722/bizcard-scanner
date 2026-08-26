package tw.pentamaster.bizcard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.ContactActions
import tw.pentamaster.bizcard.util.ImageStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(
    vm: CardViewModel,
    cardId: Long,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit
) {
    // id 0 means "use the draft the camera just filled in" — reloading would wipe it.
    LaunchedEffect(cardId) {
        if (cardId != 0L) vm.loadForEdit(cardId)
    }

    val card by vm.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var duplicate by remember { mutableStateOf<BusinessCard?>(null) }
    var showRawText by remember { mutableStateOf(false) }
    var syncToContacts by remember(cardId) { mutableStateOf(cardId == 0L) }

    val finishSave: (Long) -> Unit = { id ->
        if (cardId == 0L && syncToContacts) {
            ContactActions.insert(context, card.copy(id = id))
        }
        onSaved(id)
    }

    val abandonAndBack = {
        if (cardId == 0L) vm.discardNewCard()
        onBack()
    }
    BackHandler(enabled = cardId == 0L, onBack = abandonAndBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (cardId == 0L) "確認名片內容" else "編輯名片") },
                navigationIcon = {
                    IconButton(onClick = abandonAndBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        vm.checkDuplicate { dup ->
                            if (dup != null && dup.id != card.id) duplicate = dup
                            else vm.save { id -> finishSave(id) }
                        }
                    }) { Text("儲存") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (card.frontImage.isNotBlank() || card.backImage.isNotBlank()) {
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ThumbBox(card.frontImage, "正面", Modifier.weight(1f))
                    ThumbBox(card.backImage, "背面", Modifier.weight(1f))
                }
            }

            Field("姓名", card.name) { v -> vm.updateDraft { it.copy(name = v) } }
            Field("公司", card.company) { v -> vm.updateDraft { it.copy(company = v) } }
            Field("職稱", card.title) { v -> vm.updateDraft { it.copy(title = v) } }
            Field("部門", card.department) { v -> vm.updateDraft { it.copy(department = v) } }
            Field("手機", card.mobile, KeyboardType.Phone) { v -> vm.updateDraft { it.copy(mobile = v) } }
            Field("電話", card.phone, KeyboardType.Phone) { v -> vm.updateDraft { it.copy(phone = v) } }
            Field("傳真", card.fax, KeyboardType.Phone) { v -> vm.updateDraft { it.copy(fax = v) } }
            Field("Email", card.email, KeyboardType.Email) { v -> vm.updateDraft { it.copy(email = v) } }
            Field("網站", card.website, KeyboardType.Uri) { v -> vm.updateDraft { it.copy(website = v) } }
            Field("地址", card.address, singleLine = false) { v -> vm.updateDraft { it.copy(address = v) } }
            Field("標籤(用逗號分隔)", card.tags) { v -> vm.updateDraft { it.copy(tags = v) } }
            Field("備註", card.notes, singleLine = false) { v -> vm.updateDraft { it.copy(notes = v) } }

            if (cardId == 0L) {
                Spacer(Modifier.height(12.dp))
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(
                            checked = syncToContacts,
                            onCheckedChange = { syncToContacts = it }
                        )
                        Column(Modifier.weight(1f)) {
                            Text("儲存後加入 Google / 手機聯絡人")
                            Text(
                                "會開啟 Android 系統聯絡人確認畫面。若儲存帳號是 Google，系統會再同步到 Google 聯絡人。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val raw = listOf(card.rawTextFront, card.rawTextBack)
                .filter { it.isNotBlank() }
                .joinToString("\n———\n")

            if (raw.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showRawText = !showRawText }) {
                    Text(if (showRawText) "隱藏辨識原文" else "顯示辨識原文")
                }
                if (showRawText) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "這是 OCR 讀到的全部文字。就算欄位分錯了,搜尋一樣找得到這裡的內容。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    duplicate?.let { dup ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("這張可能重複了") },
            text = {
                Text(
                    "「${dup.displayName}」的 Email 或手機跟這張一樣。" +
                        "要另存一張,還是回去修改?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicate = null
                    vm.save { id -> finishSave(id) }
                }) { Text("還是另存一張") }
            },
            dismissButton = {
                TextButton(onClick = { duplicate = null }) { Text("回去修改") }
            }
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ThumbBox(fileName: String, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file = ImageStore.resolve(context, fileName)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(91f / 55f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "未拍攝",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
