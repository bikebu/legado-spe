package io.legado.app.ui.book.manage.duplicate

import android.app.Application
import androidx.lifecycle.MutableLiveData
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

    // 扫描进度文字，null表示扫描结束
    val progressLiveData = MutableLiveData<String?>()

    private var scanCoroutine: Coroutine<List<DuplicateGroup>>? = null

    fun scan() {
        scanCoroutine?.cancel()
        progressLiveData.value = context.getString(R.string.book_duplicate_scanning, 0, 0)
        scanCoroutine = execute {
            BookDuplicateHelp.findDuplicates(appDb.bookDao.all) { current, total ->
                progressLiveData.postValue(
                    context.getString(R.string.book_duplicate_scanning, current, total)
                )
            }
        }.onSuccess {
            groupsLiveData.value = it
        }.onError {
            AppLog.put("查找重复书籍出错\n${it.localizedMessage}", it, true)
        }.onFinally {
            progressLiveData.value = null
        }
    }

    fun cancelScan() {
        scanCoroutine?.cancel()
        progressLiveData.value = null
    }

    fun merge(group: DuplicateGroup, keep: Book, success: () -> Unit) {
        execute {
            BookDuplicateHelp.merge(keep, group.books.filter { it.bookUrl != keep.bookUrl })
        }.onSuccess {
            success.invoke()
        }.onError {
            AppLog.put("合并重复书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun ignore(group: DuplicateGroup) {
        execute {
            BookDuplicateHelp.ignore(group)
        }
    }

    fun clearIgnored() {
        execute {
            BookDuplicateHelp.clearIgnored()
        }.onSuccess {
            scan()
        }
    }

}
