package io.legado.app.ui.book.manage.duplicate

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityBookDuplicateBinding
import io.legado.app.help.book.BookMergeHistory
import java.text.DateFormat
import java.util.Date
import io.legado.app.help.book.BookDuplicateHelp.DuplicateGroup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 查找重复书籍
 */
class BookDuplicateActivity :
    VMBaseActivity<ActivityBookDuplicateBinding, BookDuplicateViewModel>(),
    BookDuplicateAdapter.CallBack {

    override val binding by viewBinding(ActivityBookDuplicateBinding::inflate)
    override val viewModel by viewModels<BookDuplicateViewModel>()
    private val adapter by lazy { BookDuplicateAdapter(this, this) }
    private val waitDialog by lazy { WaitDialog(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        waitDialog.setOnCancelListener {
            viewModel.cancelScan()
        }
        if (savedInstanceState == null || viewModel.groupsLiveData.value == null) {
            viewModel.scan()
        }
    }

    override fun observeLiveBus() {
        viewModel.progressLiveData.observe(this) { updateWaitDialog() }
        viewModel.operationLiveData.observe(this) { updateWaitDialog() }
        viewModel.historyLiveData.observe(this) { records ->
            if (records != null) {
                viewModel.consumeHistory()
                showHistory(records)
            }
        }
        viewModel.mergingLiveData.observe(this) { adapter.merging = it }
        viewModel.groupsLiveData.observe(this) { groups ->
            adapter.setItems(groups)
            upEmptyView()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_duplicate, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_merge_history -> viewModel.loadHistory()
            R.id.menu_rescan -> viewModel.scan()
            R.id.menu_rebuild_fingerprints -> viewModel.scan(forceRefresh = true)
            R.id.menu_clear_ignore -> viewModel.clearIgnored()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    override fun ignore(group: DuplicateGroup) {
        viewModel.ignore(group)
    }

    override fun merge(group: DuplicateGroup, keep: Book) {
        if (viewModel.mergingLiveData.value == true) return
        val candidates = group.books.filter { it.bookUrl != keep.bookUrl }
        val checked = BooleanArray(candidates.size)
        val labels = candidates.map { book ->
            val reasons = group.matches[setOf(keep.bookUrl, book.bookUrl)].orEmpty()
            val evidence = if (reasons.isEmpty()) {
                getString(R.string.book_duplicate_indirect)
            } else {
                reasons.sortedBy { it.ordinal }.joinToString(" · ") {
                    getString(adapter.reasonRes(it))
                }
            }
            val count = group.contentMatches[setOf(keep.bookUrl, book.bookUrl)] ?: 0
            val contentEvidence = if (count > 0) {
                "\n" + getString(R.string.book_duplicate_content_matches, count)
            } else ""
            "${book.name} · ${book.author} (${book.originName})\n$evidence$contentEvidence"
        }.toTypedArray()
        alert(getString(R.string.book_duplicate_select, keep.name)) {
            multiChoiceItems(labels, checked) { _, index, selected -> checked[index] = selected }
            okButton {
                val removes = candidates.filterIndexed { index, _ -> checked[index] }
                if (removes.isNotEmpty()) confirmMerge(group, keep, removes)
            }
            cancelButton()
        }
    }

    private fun confirmMerge(group: DuplicateGroup, keep: Book, removes: List<Book>) {
        alert(
            getString(R.string.book_duplicate_merge),
            getString(R.string.book_duplicate_merge_confirm, keep.name, removes.size) +
                "\n\n" + removes.joinToString("\n") { "${it.name} · ${it.author} (${it.originName})" }
        ) {
            okButton { viewModel.merge(group, keep, removes) }
            cancelButton()
        }
    }

    private fun updateWaitDialog() {
        val operation = viewModel.operationLiveData.value
        val text = operation ?: viewModel.progressLiveData.value
        if (text == null) {
            waitDialog.dismiss()
        } else {
            waitDialog.setCancelable(operation == null)
            waitDialog.setText(text)
            if (!waitDialog.isShowing) waitDialog.show()
        }
    }

    private fun showHistory(records: List<BookMergeHistory.Record>) {
        if (records.isEmpty()) {
            alert(getString(R.string.book_duplicate_history), getString(R.string.book_duplicate_history_empty)) {
                okButton()
            }
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = records.map { record ->
            getString(
                R.string.book_duplicate_history_item, record.keepName, record.removedCount,
                format.format(Date(record.createdAt)),
                format.format(Date(record.createdAt + 7L * 24 * 60 * 60 * 1000))
            )
        }
        alert(getString(R.string.book_duplicate_history)) {
            items(labels) { _, index ->
                val record = records[index]
                alert(
                    getString(R.string.book_duplicate_undo),
                    getString(R.string.book_duplicate_undo_confirm, record.keepName)
                ) {
                    okButton { viewModel.undoMerge(record) }
                    cancelButton()
                }
            }
            cancelButton()
        }
    }

    private fun upEmptyView() {
        binding.tvEmpty.isVisible = adapter.getItems().isEmpty()
    }

}
