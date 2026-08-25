package tw.pentamaster.bizcard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(vm: CardViewModel, onBack: () -> Unit) {

    val total by vm.total.collectAsStateWithLifecycle()
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var includePhotos by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val saveZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportZip(it) { r -> message = r } } }

    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportCsv(it) { r -> message = r } } }

    val saveVcf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-vcard")
    ) { uri -> uri?.let { vm.exportVCard(it, includePhotos) { r -> message = r } } }

    val openZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> confirmRestore = uri }

    val openVcf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importVCard(it) { r -> message = r } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("備份與匯出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "目前有 $total 張名片,照片佔用 ${formatBytes(vm.storageBytes())}。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("匯出")

            ActionCard(
                title = "完整備份",
                body = "所有欄位加上正反面照片,打包成一個 ZIP。這是唯一能完整還原的格式。",
                button = "存成 ZIP",
                enabled = !busy && total > 0,
                onClick = { saveZip.launch(vm.suggestedName("zip")) }
            )

            ActionCard(
                title = "試算表",
                body = "CSV 檔,Excel 和 Google 試算表都能開。只有文字,不含照片。",
                button = "存成 CSV",
                enabled = !busy && total > 0,
                onClick = { saveCsv.launch(vm.suggestedName("csv")) }
            )

            ActionCard(
                title = "通訊錄格式",
                body = "vCard(.vcf),可以匯入手機通訊錄或其他名片 App。",
                button = "存成 vCard",
                enabled = !busy && total > 0,
                onClick = { saveVcf.launch(vm.suggestedName("vcf")) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includePhotos, onCheckedChange = { includePhotos = it })
                    Column {
                        Text("附上名片正面照片", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "檔案會大很多,有些通訊錄 App 會讀不進去",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("匯入")

            ActionCard(
                title = "還原備份",
                body = "從 ZIP 備份加回名片。已經存在的(Email 或手機相同)會自動略過,不會蓋掉現有資料。",
                button = "選擇 ZIP",
                enabled = !busy,
                onClick = { openZip.launch(arrayOf("application/zip", "application/octet-stream")) }
            )

            ActionCard(
                title = "從其他 App 匯入",
                body = "匯入 vCard(.vcf)。從舊的名片 App 或手機通訊錄匯出的檔案都可以。",
                button = "選擇 vCard",
                enabled = !busy,
                onClick = { openVcf.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "*/*")) }
            )

            Spacer(Modifier.height(24.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    "名片資料不會自動上傳到任何地方,也已經排除在 Google 自動備份之外。" +
                        "換句話說,手機掉了就沒了——定期存一份 ZIP 到雲端硬碟。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.height(40.dp))
        }

        if (busy) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("完成") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("還原這個備份?") },
            text = { Text("名片會加進現有的資料裡,不會覆蓋。重複的會自動略過。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    vm.importZip(uri) { r -> message = r }
                }) { Text("開始還原") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ActionCard(
    title: String,
    body: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
    extra: @Composable (() -> Unit)? = null
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            extra?.let { Spacer(Modifier.height(8.dp)); it() }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
