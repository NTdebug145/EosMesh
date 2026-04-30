package com.nt.eosmesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nt.eosmesh.model.MessageItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val currentUid: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var messages = mutableListOf<MessageItem>()
    
    /** 公开的当前消息列表（只读） */
    val currentList: List<MessageItem> get() = messages.toList()

    companion object {
        private const val VIEW_TYPE_MINE = 1
        private const val VIEW_TYPE_OTHER = 2
    }

    /**
     * 替换整个消息列表
     */
    fun submitList(list: List<MessageItem>) {
        messages = list.toMutableList()
        notifyDataSetChanged()
    }

    /**
     * 在列表头部添加旧消息（用于加载历史记录）
     */
    fun addOldMessages(list: List<MessageItem>) {
        if (list.isEmpty()) return
        messages.addAll(0, list)
        notifyItemRangeInserted(0, list.size)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].from == currentUid) {
            VIEW_TYPE_MINE
        } else {
            VIEW_TYPE_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_MINE) {
            val view = inflater.inflate(R.layout.item_message_mine, parent, false)
            MessageViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_other, parent, false)
            MessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as MessageViewHolder).bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

        fun bind(msg: MessageItem) {
            tvContent.text = msg.content
            tvTime.text = formatTime(msg.time)
        }

        private fun formatTime(timestamp: Long): String {
            return try {
                val sdf = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp * 1000))
            } catch (e: Exception) {
                ""
            }
        }
    }
}