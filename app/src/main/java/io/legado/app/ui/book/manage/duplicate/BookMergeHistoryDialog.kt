package io.legado.app.ui.book.manage.duplicate

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemBookMergeRecordBinding
import io.legado.app.help.book.BookMergeHistory
import io.legado.app.help.book.BookMergeHistoryPolicy
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 合并记录：7天内可撤回的书籍合并
 */
class BookMergeHistoryDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by activityViewModels<BookDuplicateViewModel>()
    private val adapter by lazy { RecordAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.book_duplicate_history_title)
            toolBar.subtitle = getString(R.string.book_duplicate_history_subtitle)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
            tvFooterLeft.isVisible = true
            tvFooterLeft.setText(R.string.book_duplicate_history_note)
            tvOk.isVisible = true
            tvOk.setOnClickListener { dismissAllowingStateLoss() }
        }
        viewModel.historyLiveData.observe(viewLifecycleOwner) { state ->
            upView(state)
        }
        viewModel.loadHistory()
    }

    private fun upView(state: BookDuplicateViewModel.HistoryState) {
        binding.run {
            val records = state.records
            if (records == null && state.error == null) {
                rotateLoading.visible()
                tvMsg.isVisible = false
                adapter.setItems(emptyList())
                return
            }
            rotateLoading.gone()
            val list = records.orEmpty()
            adapter.setItems(list)
            tvMsg.isVisible = list.isEmpty()
            tvMsg.text = state.error?.let {
                getString(R.string.book_duplicate_history_load_failed, it)
            } ?: getString(R.string.book_duplicate_history_empty)
        }
    }

    private fun confirmUndo(record: BookMergeHistory.Record) {
        alert(
            getString(R.string.book_duplicate_undo),
            getString(R.string.book_duplicate_undo_confirm, record.keepName)
        ) {
            okButton {
                viewModel.undoMerge(record)
                dismissAllowingStateLoss()
            }
            cancelButton()
        }
    }

    private inner class RecordAdapter(context: Context) :
        RecyclerAdapter<BookMergeHistory.Record, ItemBookMergeRecordBinding>(context) {

        private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        override fun getViewBinding(parent: ViewGroup): ItemBookMergeRecordBinding {
            return ItemBookMergeRecordBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookMergeRecordBinding,
            item: BookMergeHistory.Record,
            payloads: MutableList<Any>
        ) {
            val labels = item.bookLabels
            binding.tvKeepName.text = getString(R.string.book_duplicate_history_book, item.keepName)
            val keepInfo = labels?.firstOrNull()?.let { infoWithoutName(it, item.keepName) }
            binding.tvKeepInfo.isVisible = !keepInfo.isNullOrEmpty()
            binding.tvKeepInfo.text = getString(R.string.book_duplicate_history_kept, keepInfo.orEmpty())
            binding.tvMergedTitle.text =
                getString(R.string.book_duplicate_history_merged, item.removedCount)
            // 旧记录没有书籍说明，只能列出不同于保留书的书名
            val merged = labels?.drop(1)
                ?: item.bookNames.filter { it != item.keepName }
            binding.tvMergedBooks.isVisible = merged.isNotEmpty()
            binding.tvMergedBooks.text = merged.joinToString("\n") { "• $it" }
            binding.tvTime.text = timeFormat.format(Date(item.createdAt))
            binding.tvExpire.text = expireText(item.createdAt)
            binding.tvUndo.isSelected = true
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBookMergeRecordBinding) {
            binding.tvExpire.setTextColor(context.accentColor)
            binding.tvUndo.setOnClickListener {
                getItem(holder.layoutPosition)?.let { confirmUndo(it) }
            }
        }

        private fun infoWithoutName(label: String, name: String): String {
            return label.removePrefix(name).removePrefix(" · ").trim()
        }

        private fun expireText(createdAt: Long): String {
            val remaining = createdAt + BookMergeHistoryPolicy.RETENTION_MILLIS - System.currentTimeMillis()
            val hour = 60 * 60 * 1000L
            return when {
                remaining >= 24 * hour -> getString(
                    R.string.book_duplicate_history_expire_days, (remaining / (24 * hour)).toInt()
                )

                remaining >= hour -> getString(
                    R.string.book_duplicate_history_expire_hours, (remaining / hour).toInt()
                )

                else -> getString(R.string.book_duplicate_history_expire_soon)
            }
        }
    }

}
