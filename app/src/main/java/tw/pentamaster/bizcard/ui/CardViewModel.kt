package tw.pentamaster.bizcard.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.pentamaster.bizcard.data.BackupManager
import tw.pentamaster.bizcard.data.BusinessCard
import tw.pentamaster.bizcard.data.CardRepository
import tw.pentamaster.bizcard.util.ImageStore

data class TransferPackage(
    val uri: Uri,
    val fileName: String,
    val cards: Int,
    val images: Int
)

@OptIn(ExperimentalCoroutinesApi::class)
class CardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CardRepository(app)
    private val backup = BackupManager(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _activeTag = MutableStateFlow<String?>(null)
    val activeTag: StateFlow<String?> = _activeTag.asStateFlow()

    /** Card being scanned or edited. Held here so it survives screen changes. */
    private val _draft = MutableStateFlow(BusinessCard())
    val draft: StateFlow<BusinessCard> = _draft.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val cards: StateFlow<List<BusinessCard>> =
        combine(_query, _activeTag) { q, tag -> q to tag }
            .flatMapLatest { (q, tag) ->
                when {
                    tag != null -> repo.byTag(tag)
                    else -> repo.search(q)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allCards: StateFlow<List<BusinessCard>> = repo.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<String>> = repo.allTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val total: StateFlow<Int> = repo.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotEmpty()) _activeTag.value = null
    }

    fun toggleTag(tag: String) {
        _activeTag.value = if (_activeTag.value == tag) null else tag
        if (_activeTag.value != null) _query.value = ""
    }

    fun clearFilters() {
        _query.value = ""
        _activeTag.value = null
    }

    // ---- draft / editing -------------------------------------------------

    fun startNewCard() {
        _draft.value = BusinessCard()
    }

    fun discardNewCard() {
        val card = _draft.value
        if (card.id != 0L) return
        ImageStore.delete(getApplication(), card.frontImage)
        ImageStore.delete(getApplication(), card.backImage)
        _draft.value = BusinessCard()
    }

    fun loadForEdit(id: Long, onLoaded: () -> Unit = {}) = viewModelScope.launch {
        _draft.value = if (id == 0L) BusinessCard() else repo.byId(id) ?: BusinessCard()
        onLoaded()
    }

    fun updateDraft(transform: (BusinessCard) -> BusinessCard) {
        _draft.value = transform(_draft.value)
    }

    fun setScanning(value: Boolean) {
        _scanning.value = value
    }

    /** Merge OCR into blanks only, including both bilingual name/company fields. */
    fun mergeParsed(parsed: BusinessCard) {
        _draft.value = _draft.value.let { d ->
            d.copy(
                name = d.name.ifBlank { parsed.name },
                nameEn = d.nameEn.ifBlank { parsed.nameEn },
                company = d.company.ifBlank { parsed.company },
                companyEn = d.companyEn.ifBlank { parsed.companyEn },
                title = d.title.ifBlank { parsed.title },
                department = d.department.ifBlank { parsed.department },
                phone = d.phone.ifBlank { parsed.phone },
                mobile = d.mobile.ifBlank { parsed.mobile },
                fax = d.fax.ifBlank { parsed.fax },
                email = d.email.ifBlank { parsed.email },
                website = d.website.ifBlank { parsed.website },
                address = d.address.ifBlank { parsed.address },
                rawTextFront = parsed.rawTextFront.ifBlank { d.rawTextFront },
                rawTextBack = parsed.rawTextBack.ifBlank { d.rawTextBack }
            )
        }
    }

    fun save(onSaved: (Long) -> Unit = {}) = viewModelScope.launch {
        val current = _draft.value
        val id = repo.save(current)
        if (current.id == 0L) _draft.value = current.copy(id = id)
        onSaved(id)
    }

    fun delete(card: BusinessCard, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        repo.delete(card)
        onDeleted()
    }

    fun checkDuplicate(onResult: (BusinessCard?) -> Unit) = viewModelScope.launch {
        onResult(repo.findDuplicate(_draft.value))
    }

    fun byId(id: Long, onResult: (BusinessCard?) -> Unit) = viewModelScope.launch {
        onResult(repo.byId(id))
    }

    // ---- backup / export -------------------------------------------------

    fun suggestedName(ext: String): String = backup.suggestedName(ext)

    fun prepareTransfer(onReady: (TransferPackage?, String?) -> Unit) =
        viewModelScope.launch {
            _backupBusy.value = true
            val app = getApplication<Application>()
            val shareDir = File(app.cacheDir, "share")
            try {
                if (shareDir.exists()) shareDir.deleteRecursively()
                if (!shareDir.mkdirs() && !shareDir.isDirectory) {
                    throw IOException("無法建立暫存資料夾")
                }

                val file = File(shareDir, backup.suggestedName("zip"))
                if (!file.createNewFile()) throw IOException("無法建立轉移檔")

                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    file
                )
                val result = backup.exportZip(uri)
                onReady(TransferPackage(uri, file.name, result.cards, result.images), null)
            } catch (e: Exception) {
                shareDir.deleteRecursively()
                onReady(null, "建立轉移檔失敗:${e.message ?: "未知錯誤"}")
            } finally {
                _backupBusy.value = false
            }
        }

    fun exportZip(uri: Uri, onDone: (String) -> Unit) = runBackup(onDone) {
        val r = backup.exportZip(uri)
        "已匯出 ${r.cards} 張名片和 ${r.images} 張照片。"
    }

    fun exportCsv(uri: Uri, onDone: (String) -> Unit) = runBackup(onDone) {
        val r = backup.exportCsv(uri)
        "已匯出 ${r.cards} 張名片的文字資料。照片不包含在 CSV 裡。"
    }

    fun exportVCard(uri: Uri, includePhotos: Boolean, onDone: (String) -> Unit) = runBackup(onDone) {
        val r = backup.exportVCard(uri, includePhotos)
        if (includePhotos) "已匯出 ${r.cards} 張名片,含 ${r.images} 張照片。"
        else "已匯出 ${r.cards} 張名片。"
    }

    fun importZip(uri: Uri, onDone: (String) -> Unit) = runBackup(onDone) {
        val r = backup.importZip(uri)
        r.error?.let { return@runBackup "還原失敗:$it" }
        buildString {
            append("已加入 ${r.added} 張名片")
            if (r.images > 0) append("、${r.images} 張照片")
            if (r.skipped > 0) append(",略過 ${r.skipped} 張重複的")
            append("。")
        }
    }

    fun importVCard(uri: Uri, onDone: (String) -> Unit) = runBackup(onDone) {
        val r = backup.importVCard(uri)
        r.error?.let { return@runBackup "匯入失敗:$it" }
        buildString {
            append("已匯入 ${r.added} 張名片")
            if (r.images > 0) append("、${r.images} 張照片")
            if (r.skipped > 0) append(",略過 ${r.skipped} 張重複或空白的")
            append("。")
        }
    }

    private fun runBackup(onDone: (String) -> Unit, work: suspend () -> String) =
        viewModelScope.launch {
            _backupBusy.value = true
            val message = try {
                work()
            } catch (e: Exception) {
                "操作失敗:${e.message ?: "未知錯誤"}"
            } finally {
                _backupBusy.value = false
            }
            onDone(message)
        }

    fun storageBytes(): Long = ImageStore.totalBytes(getApplication())
}
