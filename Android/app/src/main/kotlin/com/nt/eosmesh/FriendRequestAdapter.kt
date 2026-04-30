package com.nt.eosmesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nt.eosmesh.model.FriendRequestItem

class FriendRequestAdapter(
    private val onAccept: (String) -> Unit
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    private var items = listOf<FriendRequestItem>()

    fun submitList(list: List<FriendRequestItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val btnAccept: Button = itemView.findViewById(R.id.btnAccept)

        fun bind(request: FriendRequestItem) {
            tvName.text = request.fromUsername
            tvMessage.text = request.message
            btnAccept.setOnClickListener {
                onAccept(request.id)
            }
        }
    }
}