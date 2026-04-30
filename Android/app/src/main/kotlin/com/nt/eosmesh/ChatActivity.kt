package com.nt.eosmesh

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nt.eosmesh.databinding.ActivityChatBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.model.MessageItem
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.SecureStorage
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private val uid get() = SecureStorage.getString(SecureStorage.KEY_UID) ?: ""
    private var friendUid = ""
    private var latestMsgTime = 0L
    private var currentPage = 1
    private var totalPages = 0
    private var isLoading = false
    private var isLoadingMore = false
    private var hasMore = true
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        friendUid = intent.getStringExtra("friend_uid") ?: return
        val friendName = intent.getStringExtra("friend_name") ?: friendUid
        binding.toolbar.title = friendName
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ChatAdapter(uid)
        binding.recyclerMessages.itemAnimator = null
        binding.recyclerMessages.adapter = adapter
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = false }
        binding.recyclerMessages.layoutManager = layoutManager

        binding.recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isLoadingMore || !hasMore) return
                if (layoutManager.findFirstVisibleItemPosition() == 0) {
                    loadOlderMessages()
                }
            }
        })

        loadLatestMessages()
        startPolling()

        binding.btnSend.setOnClickListener {
            val content = binding.etMessage.text?.toString()?.trim() ?: ""
            if (content.isNotEmpty()) sendMessage(content)
        }
    }

    // 加载最后一页（最新消息）
    private fun loadLatestMessages() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        if (serverUrl.isEmpty() || token.isEmpty()) return
        isLoading = true

        lifecycleScope.launch {
            try {
                val firstResult = ApiClient.getMessages(serverUrl, token, friendUid, page = 1)
                if (firstResult !is ApiResult.Success) {
                    showError("加载消息失败")
                    isLoading = false
                    return@launch
                }
                totalPages = firstResult.data.totalPages
                if (totalPages == 0) {
                    adapter.submitList(emptyList())
                    hasMore = false
                    isLoading = false
                    return@launch
                }

                val lastPage = totalPages
                val lastResult = if (lastPage == 1) firstResult
                else ApiClient.getMessages(serverUrl, token, friendUid, page = lastPage)

                if (lastResult !is ApiResult.Success) {
                    showError("加载最新消息失败")
                    isLoading = false
                    return@launch
                }

                val messages = lastResult.data.messages.sortedBy { it.time }
                adapter.submitList(messages)
                currentPage = lastPage
                hasMore = lastPage > 1
                latestMsgTime = messages.lastOrNull()?.time ?: 0L
                binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
            } catch (e: Exception) {
                showError("发生错误: ${e.message}")
            }
            isLoading = false
        }
    }

    // 加载更早的消息
    private fun loadOlderMessages() {
        if (!hasMore || isLoadingMore || currentPage <= 1) return
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        if (serverUrl.isEmpty() || token.isEmpty()) return

        isLoadingMore = true
        val prevPage = currentPage - 1
        binding.progressLoadMore.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = ApiClient.getMessages(serverUrl, token, friendUid, page = prevPage)
                if (result is ApiResult.Success) {
                    val older = result.data.messages.sortedBy { it.time }
                    if (older.isNotEmpty()) {
                        val existingKeys = adapter.currentList.map { "${it.from}_${it.time}" }.toSet()
                        val newOnes = older.filter { "${it.from}_${it.time}" !in existingKeys }
                        if (newOnes.isNotEmpty()) {
                            adapter.addOldMessages(newOnes)
                        }
                        currentPage = prevPage
                        hasMore = prevPage > 1
                    } else {
                        hasMore = false
                    }
                } else {
                    showError("加载历史失败")
                }
            } catch (e: Exception) {
                showError("发生错误: ${e.message}")
            }
            binding.progressLoadMore.visibility = View.GONE
            isLoadingMore = false
        }
    }

    // 每3秒轮询一次新消息
    private fun startPolling() {
        val runnable = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
                val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
                if (serverUrl.isEmpty() || token.isEmpty()) {
                    handler.postDelayed(this, 3000)
                    return
                }

                lifecycleScope.launch {
                    val result = ApiClient.getMessages(serverUrl, token, friendUid, since = latestMsgTime)
                    if (result is ApiResult.Success) {
                        val newMessages = result.data.messages
                        if (newMessages.isNotEmpty()) {
                            val existingKeys = adapter.currentList.map { "${it.from}_${it.time}" }.toSet()
                            val trulyNew = newMessages.filter { "${it.from}_${it.time}" !in existingKeys }
                            if (trulyNew.isNotEmpty()) {
                                val merged = (adapter.currentList + trulyNew).sortedBy { it.time }
                                adapter.submitList(merged)
                                latestMsgTime = merged.last().time
                                val lastVisible = (binding.recyclerMessages.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: 0
                                if (lastVisible >= adapter.itemCount - 3) {
                                    binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                                }
                            }
                        }
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, 3000)
    }

    // 发送消息（乐观更新 + 服务器真实消息替换）
    private fun sendMessage(content: String) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        if (serverUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "未设置服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        binding.etMessage.setText("")

        val tempMsg = MessageItem(
            from = uid,
            to = friendUid,
            content = content,
            time = System.currentTimeMillis() / 1000
        )
        // 本地立即显示
        val currentList = adapter.currentList.toMutableList()
        currentList.add(tempMsg)
        adapter.submitList(currentList.sortedBy { it.time })
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            when (val sendResult = ApiClient.sendMessage(serverUrl, token, friendUid, content)) {
                is ApiResult.Success -> {
                    // 获取最新消息以替换临时消息
                    val pollResult = ApiClient.getMessages(serverUrl, token, friendUid, since = latestMsgTime)
                    if (pollResult is ApiResult.Success) {
                        val newMessages = pollResult.data.messages
                        if (newMessages.isNotEmpty()) {
                            val listAfterSend = adapter.currentList.toMutableList()
                            // 移除本地临时消息（根据内容匹配）
                            listAfterSend.removeAll { it.from == uid && it.content == content && it.to == friendUid }
                            // 合并服务器新消息并去重
                            val existingKeys = listAfterSend.map { "${it.from}_${it.time}" }.toSet()
                            val toAdd = newMessages.filter { "${it.from}_${it.time}" !in existingKeys }
                            listAfterSend.addAll(toAdd)
                            val merged = listAfterSend.sortedBy { it.time }
                            adapter.submitList(merged)
                            latestMsgTime = merged.last().time
                            binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                    } else {
                        // 拉取失败，重新加载以恢复正确状态
                        loadLatestMessages()
                    }
                }
                is ApiResult.Error -> {
                    showError("发送失败: ${sendResult.message}")
                    loadLatestMessages()
                }
                ApiResult.NetworkError -> {
                    showError("网络错误，发送失败")
                    loadLatestMessages()
                }
            }
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollRunnable?.let { handler.removeCallbacks(it) }
    }
}