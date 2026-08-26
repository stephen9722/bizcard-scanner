package tw.pentamaster.bizcard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.ContactActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CardMetaActions(card: BusinessCard) {
    val context = LocalContext.current
    var contactMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "加入日期",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(formatCardTime(card.createdAt), style = MaterialTheme.typography.bodyLarge)

        if (card.updatedAt > card.createdAt + 60_000L) {
            Spacer(Modifier.height(8.dp))
            Text(
                "最後更新",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(formatCardTime(card.updatedAt), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                contactMessage = if (ContactActions.insert(context, card)) {
                    "已開啟系統聯絡人。若儲存帳號是 Google，Android 會自動同步到 Google 聯絡人。"
                } else {
                    "找不到可新增聯絡人的 App。"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("加入 Google / 手機聯絡人")
        }
        contactMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

private fun formatCardTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(value))
