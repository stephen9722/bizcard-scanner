package tw.pentamaster.bizcard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.util.AccountGroup
import tw.pentamaster.bizcard.util.OrgAnalyzer
import tw.pentamaster.bizcard.util.OrgLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyAccountScreen(
    vm: CardViewModel,
    onBack: () -> Unit,
    onOpenCard: (Long) -> Unit
) {
    val cards by vm.allCards.collectAsStateWithLifecycle()
    val accounts = remember(cards) { OrgAnalyzer.accounts(cards) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = accounts.firstOrNull { it.key == selectedKey }

    BackHandler(enabled = selected != null) { selectedKey = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.displayName ?: "Key Account 組織圖") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selectedKey = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (selected == null) {
            AccountList(
                modifier = Modifier.padding(padding),
                accounts = accounts,
                query = query,
                onQueryChange = { query = it },
                onSelect = { selectedKey = it.key }
            )
        } else {
            AccountOrganization(
                modifier = Modifier.padding(padding),
                account = selected,
                onOpenCard = onOpenCard
            )
        }
    }
}

@Composable
private fun AccountList(
    modifier: Modifier,
    accounts: List<AccountGroup>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (AccountGroup) -> Unit
) {
    val filtered = remember(accounts, query) {
        val q = query.trim()
        if (q.isBlank()) accounts else accounts.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.cards.any { card ->
                    card.department.contains(q, ignoreCase = true) ||
                        card.name.contains(q, ignoreCase = true)
                }
        }
    }

    Column(modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                "離線智慧整理：依公司、部門與職稱推定組織層級。名片通常沒有正式 reporting line，因此畫面只代表推定階層，不會宣稱誰實際向誰報告。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp)
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("搜尋公司、部門或聯絡人") },
            singleLine = true
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (accounts.isEmpty()) "目前沒有可建立 Key Account 的公司資料" else "沒有符合的公司",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.key }) { account ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(account) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${account.cards.size} 位聯絡人",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val departments = OrgAnalyzer.departments(account.cards)
                                .map { it.name }
                                .filter { it != "未標示部門" }
                                .take(4)
                            if (departments.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    departments.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountOrganization(
    modifier: Modifier,
    account: AccountGroup,
    onOpenCard: (Long) -> Unit
) {
    val departments = remember(account.cards) { OrgAnalyzer.departments(account.cards) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${account.cards.size} 位聯絡人",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(onClick = {}, label = { Text("推定組織") })
                }
                Text(
                    "上下層級依名片職稱關鍵字推定；點任何人可開啟完整名片。電話與 Email 可直接從節點操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        departments.forEach { department ->
            item(key = "dept-${department.name}") {
                Column {
                    Text(
                        department.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))

                    OrgLevel.values().forEach { level ->
                        val people = department.cards.filter { OrgAnalyzer.levelFor(it.title) == level }
                        if (people.isNotEmpty()) {
                            OrgLevelRow(level = level, people = people, onOpenCard = onOpenCard)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgLevelRow(
    level: OrgLevel,
    people: List<BusinessCard>,
    onOpenCard: (Long) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    level.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(people, key = { it.id }) { card ->
                OrgPersonCard(card = card, onOpen = { onOpenCard(card.id) })
            }
        }
    }
}

@Composable
private fun OrgPersonCard(card: BusinessCard, onOpen: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier.width(228.dp).clickable(onClick = onOpen)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                card.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (card.title.isNotBlank()) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    "職稱未填寫",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (card.department.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    card.department,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (card.mobile.isNotBlank() || card.phone.isNotBlank() || card.email.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        // Keep the card screen usable even when the device has no matching app.
    }
}
