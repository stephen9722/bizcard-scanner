package tw.pentamaster.bizcard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pentamaster.bizcard.data.CsvImportManager

@Composable
fun CsvImportAction(
    enabled: Boolean,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = CsvImportManager(context.applicationContext).importCsv(uri)
                onMessage(
                    result.error?.let { "CSV 匯入失敗：$it" } ?: buildString {
                        append("已匯入 ${result.added} 筆聯絡人")
                        if (result.skipped > 0) append("，略過 ${result.skipped} 筆重複或空白資料")
                        append("。原檔若有建立日期，會保留為加入日期。")
                    }
                )
            }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("從 CSV 匯入", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "支援全能名片王 / CamCard 與一般聯絡人 CSV。會依欄位名稱自動對應姓名、公司、部門、職稱、手機、電話、Email、地址與建立日期。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "application/csv",
                            "text/plain",
                            "*/*"
                        )
                    )
                },
                enabled = enabled
            ) { Text("選擇 CSV") }
        }
    }
}
