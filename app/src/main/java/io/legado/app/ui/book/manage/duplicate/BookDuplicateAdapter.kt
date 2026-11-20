package io.legado.app.ui.book.manage.duplicate

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookDuplicateBookBinding
import io.legado.app.databinding.ItemBookDuplicateGroupBinding
import io.legado.app.help.book.BookDuplicateHelp.DuplicateGroup
import io.legado.app.help.book.BookDuplicateHelp.Reason
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor

class BookDuplicateAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<DuplicateGroup, ItemBookDuplicateGroupBinding>(context) {

    // 分组key -> 保留的书籍bookUrl
    private val keepMap = hashMapOf<String, String>()

    fun getKeep(group: DuplicateGroup): Book {
        return group.books.firstOrNull { it.bookUrl == keepMap[group.key] }
            ?: group.books.first()
    }

    override fun getViewBinding(parent: ViewGroup): ItemBookDuplicateGroupBinding {
        return ItemBookDuplicateGroupBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookDuplicateGroupBinding,
        item: DuplicateGroup,
        payloads: MutableList<Any>
    ) {
        binding.tvReasons.text = item.reasons.sortedBy { it.ordinal }.joinToString(" · ") {
            context.getString(reasonRes(it))
        }
        binding.llBooks.removeAllViews()
        val keep = getKeep(item)
        item.books.forEach { book ->
            val bookBinding = ItemBookDuplicateBookBinding.inflate(inflater, binding.llBooks, false)
            bookBinding.tvName.text = if (book.author.isBlank()) {
                book.name
            } else {
                "${book.name} · ${book.author}"
            }
            val origin = if (book.isLocal) {
                context.getString(R.string.local_book)
            } else {
                book.originName
            }
            val progress = context.getString(
                R.string.book_duplicate_progress,
                book.durChapterTitle ?: "-",
                book.totalChapterNum
            )
            bookBinding.tvInfo.text = "$origin\n$progress"
            val isKeep = book.bookUrl == keep.bookUrl
            bookBinding.rbKeep.isChecked = isKeep
            bookBinding.tvState.setText(
                if (isKeep) R.string.book_duplicate_keep else R.string.book_duplicate_remove
            )
            bookBinding.tvState.setTextColor(
                if (isKeep) context.accentColor else context.getCompatColor(R.color.secondaryText)
            )
            bookBinding.root.setOnClickListener {
                keepMap[item.key] = book.bookUrl
                notifyItemChanged(holder.layoutPosition)
            }
            binding.llBooks.addView(bookBinding.root)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookDuplicateGroupBinding) {
        binding.tvIgnore.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.ignore(it)
            }
        }
        binding.tvMerge.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.merge(it, getKeep(it))
            }
        }
    }

    private fun reasonRes(reason: Reason): Int {
        return when (reason) {
            Reason.SAME_NAME_AUTHOR -> R.string.book_duplicate_reason_same_name_author
            Reason.SAME_NAME -> R.string.book_duplicate_reason_same_name
            Reason.SIMILAR_NAME -> R.string.book_duplicate_reason_similar_name
            Reason.SIMILAR_CONTENT -> R.string.book_duplicate_reason_similar_content
        }
    }

    interface CallBack {
        fun ignore(group: DuplicateGroup)
        fun merge(group: DuplicateGroup, keep: Book)
    }

}
