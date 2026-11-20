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
        viewModel.progressLiveData.observe(this) { text ->
            if (text == null) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText(text)
                if (!waitDialog.isShowing) {
                    waitDialog.show()
                }
            }
        }
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
            R.id.menu_rescan -> viewModel.scan()
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
        removeGroup(group)
    }

    override fun merge(group: DuplicateGroup, keep: Book) {
        alert(
            getString(R.string.book_duplicate_merge),
            getString(R.string.book_duplicate_merge_confirm, keep.name, group.books.size - 1)
        ) {
            okButton {
                viewModel.merge(group, keep) {
                    removeGroup(group)
                }
            }
            cancelButton()
        }
    }

    private fun removeGroup(group: DuplicateGroup) {
        adapter.removeItem(group)
        upEmptyView()
    }

    private fun upEmptyView() {
        binding.tvEmpty.isVisible = adapter.getItems().isEmpty()
    }

}
