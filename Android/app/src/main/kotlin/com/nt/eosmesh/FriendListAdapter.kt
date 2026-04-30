package com.nt.eosmesh

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nt.eosmesh.model.FriendItem
import com.nt.eosmesh.utils.AvatarCacheManager

class FriendListAdapter(
    private val onFriendClick: (FriendItem) -> Unit
) : RecyclerView.Adapter<FriendListAdapter.ViewHolder>() {

    private var friends = listOf<FriendItem>()
    var avatarMd5Map: Map<String, String?> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(list: List<FriendItem>) {
        friends = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(friends[position])
    }

    override fun getItemCount() = friends.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvFriendName)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)

        fun bind(friend: FriendItem) {
            tvName.text = friend.username

            // 加载头像
            val md5 = avatarMd5Map[friend.uid]
            val context = itemView.context
            
            if (md5 != null) {
                val localFile = AvatarCacheManager.getLocalAvatarFile(context, friend.uid, md5)
                if (localFile != null && localFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
                        if (bitmap != null) {
                            ivAvatar.setImageBitmap(bitmap)
                        } else {
                            // 文件损坏，删除并显示默认头像
                            localFile.delete()
                            ivAvatar.setImageResource(R.drawable.ic_user)
                        }
                    } catch (e: Exception) {
                        ivAvatar.setImageResource(R.drawable.ic_user)
                    }
                } else {
                    // 本地没有缓存，显示默认头像（异步下载由外部处理）
                    ivAvatar.setImageResource(R.drawable.ic_user)
                }
            } else {
                // md5 为 null，说明好友没有头像
                ivAvatar.setImageResource(R.drawable.ic_user)
            }

            itemView.setOnClickListener { onFriendClick(friend) }
        }
    }
}