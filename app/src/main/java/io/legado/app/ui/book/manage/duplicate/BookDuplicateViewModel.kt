package io.legado.app.ui.book.manage.duplicate

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookMergeHistory
import io.legado.app.help.book.BookDuplicateHelp
import io.legado.app.help.book.BookDuplicateHelp.DuplicateGroup
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.toastOnUi

class BookDuplicateViewModel(application: Application) : BaseViewModel(application) {
    val groupsLiveData = MutableLiveData<List<DuplicateGroup>>()
    val progressLiveData = MutableLiveData<String?>()
    val mergingLiveData = MutableLiveData(false)
    val operationLiveData = MutableLiveData<String?>()
    val historyLiveData = MutableLiveData<List<BookMergeHistory.Record>?>()
    private var scanVersion = 0
    private var scanCoroutine: Coroutine<List<DuplicateGroup>>? = null

    fun scan(forceRefresh: Boolean = false) {
        if (mergingLiveData.value == true) return
        val version = ++scanVersion
        scanCoroutine?.cancel()
        progressLiveData.value = context.getString(R.string.book_duplicate_scanning_metadata)
        scanCoroutine = execute {
            var lastPhase: BookDuplicateHelp.ScanPhase? = null
            var lastUpdate = 0L
            BookDuplicateHelp.findDuplicates(appDb.bookDao.all, forceRefresh) { phase, current, total ->
                val now = SystemClock.elapsedRealtime()
                if (phase != lastPhase || current == total || now - lastUpdate >= 100L) {
                    lastPhase = phase
                    lastUpdate = now
                    val text = when (phase) {
                        BookDuplicateHelp.ScanPhase.METADATA -> context.getString(R.string.book_duplicate_scanning_metadata)
                        BookDuplicateHelp.ScanPhase.FINGERPRINTS -> context.getString(R.string.book_duplicate_scanning_content, current, total)
                        BookDuplicateHelp.ScanPhase.CONTENT -> context.getString(R.string.book_duplicate_comparing_content, current, total)
                        BookDuplicateHelp.ScanPhase.GROUPING -> context.getString(R.string.book_duplicate_grouping)
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        if (version == scanVersion) progressLiveData.value = text
                    }
                }
            }
        }.onSuccess {
            if (version == scanVersion) groupsLiveData.value = it
        }.onError {
            AppLog.put("查找重复书籍出错\n${it.localizedMessage}", it, true)
        }.onFinally {
            if (version == scanVersion) {
                ++scanVersion
                progressLiveData.value = null
            }
        }
    }

    fun cancelScan() {
        ++scanVersion
        scanCoroutine?.cancel()
        progressLiveData.value = null
    }

    fun merge(group: DuplicateGroup, keep: Book, removes: List<Book>) {
        if (mergingLiveData.value == true || removes.isEmpty()) return
        if (groupsLiveData.value?.none { it.key == group.key } != false) return
        cancelScan()
        mergingLiveData.value = true
        operationLiveData.value = context.getString(R.string.book_duplicate_merging)
        execute {
            BookDuplicateHelp.merge(keep, removes)
        }.onSuccess {
            context.toastOnUi(R.string.book_duplicate_merged)
            // 部分合并后重新扫描，不能沿用原分组关系。
            groupsLiveData.value = emptyList()
        }.onError {
            AppLog.put("合并重复书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
            operationLiveData.value = null
            scan()
        }
    }

    fun loadHistory() {
        if (mergingLiveData.value == true) return
        mergingLiveData.value = true
        operationLiveData.value = context.getString(R.string.book_duplicate_loading_history)
        execute {
            BookMergeHistory.records()
        }.onSuccess {
            historyLiveData.value = it
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
            operationLiveData.value = null
        }
    }

    fun consumeHistory() {
        historyLiveData.value = null
    }

    fun undoMerge(record: BookMergeHistory.Record) {
        if (mergingLiveData.value == true) return
        cancelScan()
        mergingLiveData.value = true
        operationLiveData.value = context.getString(R.string.book_duplicate_restoring)
        execute {
            BookMergeHistory.restore(record.id)
        }.onSuccess {
            groupsLiveData.value = emptyList()
            context.toastOnUi(R.string.book_duplicate_restored)
        }.onError {
            AppLog.put("撤回书籍合并失败", it)
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
            operationLiveData.value = null
            scan()
        }
    }

    fun ignore(group: DuplicateGroup) {
        if (mergingLiveData.value == true) return
        cancelScan()
        mergingLiveData.value = true
        execute {
            BookDuplicateHelp.ignore(group)
        }.onSuccess {
            groupsLiveData.value = groupsLiveData.value.orEmpty().filterNot { it.key == group.key }
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
        }
    }

    fun clearIgnored() {
        if (mergingLiveData.value == true) return
        cancelScan()
        mergingLiveData.value = true
        execute {
            BookDuplicateHelp.clearIgnored()
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
            scan()
        }
    }
}
