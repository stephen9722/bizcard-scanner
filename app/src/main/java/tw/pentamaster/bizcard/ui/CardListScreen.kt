package tw.pentamaster.bizcard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.ImageStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardListScreen(
    vm: CardViewModel,
    onOpen: (Long) -> Unit,
    onScan: () -> Unit,
    onAddManually: () -> Unit,
    onBackup: () -> Unit,
    onKeyAccounts: () -> Unit
) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val activeTag by vm.activeTag.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("名片簿")
                        if (total > 0) {
                            Text(
                                "$total 張",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onAddManually) {
                        Icon(Icons.Default.Add, contentDescription = "手動新增")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Key Account 組織圖") },
                            onClick = { menuOpen = false; onKeyAccounts() }
                        )
                        DropdownMenuItem(
                            text = { Text("備份與匯出") },
                            onClick = { menuOpen = false; onBackup() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                text = { Text("掃描名片") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜尋中英文姓名、公司、電話、備註…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            if (tags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tags) { tag ->
                        FilterChip(
                            selected = activeTag == tag,
                            onClick = { vm.toggleTag(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            when {
                cards.isEmpty() && (query.isNotEmpty() || activeTag != null) ->
                    EmptyState(
                        headline = "沒有符合的名片",
                        body = "換個關鍵字試試,或清掉目前的篩選條件。",
                        actionLabel = "清除篩選",
                        onAction = vm::clearFilters
                    )

                cards.isEmpty() ->
                    EmptyState(
                        headline = "還沒有名片",
                        body = "拍下第一張名片,正反面都會存起來,文字自動辨識後就能用關鍵字找。",
                        actionLabel = "掃描名片",
                        onAction = onScan
                    )

                else -> {
                    val sections = remember(cards) {
                        cards
                            .sortedByDescending { it.createdAt }
                            .groupBy { createdDateLabel(it.createdAt) }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        sections.forEach { (dateLabel, dayCards) ->
                            item(key = "date-$dateLabel") {
                                DateSectionHeader(dateLabel)
                            }
                            items(dayCards, key = { it.id }) { card ->
                                CardRow(card) { onOpen(card.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateSectionHeader(date: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            text = date,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)
        )
    }
}

private fun createdDateLabel(createdAt: Long): String {
    if (createdAt <= 0L) return "未設定日期"
    return Instant.ofEpochMilli(createdAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}

@Composable
private fun CardRow(card: BusinessCard, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb = ImageStore.resolve(context, card.frontImage)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(64.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        card.displayName.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    card.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = listOf(card.displayCompany, card.title)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (card.backImage.isNotBlank()) {
                    Text(
                        "正反面",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val number = card.mobile.ifBlank { card.phone }
            if (number.isNotBlank()) {
                IconButton(onClick = { dial(context, number) }) {
                    Icon(Icons.Default.Phone, contentDescription = "撥打 ${card.displayName}")
                }
            }
            if (card.email.isNotBlank()) {
                IconButton(onClick = { mail(context, card.email) }) {
                    Icon(Icons.Default.Email, contentDescription = "寄信給 ${card.displayName}")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    headline: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(headline, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

private fun dial(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    safeStart(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")))
}

private fun mail(context: Context, address: String) {
    if (address.isBlank()) return
    safeStart(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address.trim())}")))
}

private fun safeStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Keep search/results usable even if the device has no dialer or email app.
    }
}
