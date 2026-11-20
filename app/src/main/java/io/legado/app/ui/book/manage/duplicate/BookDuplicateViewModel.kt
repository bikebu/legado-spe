package io.legado.app.ui.book.manage.duplicate

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookDuplicateHelp
import io.legado.app.help.book.BookDuplicateHelp.DuplicateGroup
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.toastOnUi

class BookDuplicateViewModel(application: Application) : BaseViewModel(application) {
    val groupsLiveData = MutableLiveData<List<DuplicateGroup>>()
    val progressLiveData = MutableLiveData<String?>()
    val mergingLiveData = MutableLiveData(false)
    private var scanVersion = 0
    private var scanCoroutine: Coroutine<List<DuplicateGroup>>? = null

    fun scan() {
        if (mergingLiveData.value == true) return
        val version = ++scanVersion
        scanCoroutine?.cancel()
        progressLiveData.value = context.getString(R.string.book_duplicate_scanning, 0, 0)
        scanCoroutine = execute {
            BookDuplicateHelp.findDuplicates(appDb.bookDao.all) { current, total ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (version == scanVersion) {
                        progressLiveData.value = context.getString(
                            R.string.book_duplicate_scanning, current, total
                        )
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
        execute {
            BookDuplicateHelp.merge(keep, removes)
        }.onSuccess {
            // 部分合并后重新扫描，不能沿用原分组关系。
            groupsLiveData.value = emptyList()
        }.onError {
            AppLog.put("合并重复书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            mergingLiveData.value = false
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
