package com.nt.eosmesh

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.nt.eosmesh.databinding.ActivityMainBinding
import com.nt.eosmesh.databinding.DialogAddFriendBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.AvatarCacheManager
import com.nt.eosmesh.utils.SecureStorage
import com.nt.eosmesh.utils.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private var searchJob: Job? = null
    private lateinit var friendAdapter: FriendListAdapter
    private var friendAvatarMd5Map: Map<String, String?> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = ContextCompat.getColor(this, R.color.toolbar_background)
        SecureStorage.init(this)
        TokenManager.startAutoRefresh(this)

        drawerLayout = binding.drawerLayout
        navigationView = binding.navigationView

        setupDrawer()
        updateDrawerHeader()
        setupFriendsList()
        loadFriends()
        loadMyProfileAvatar()

        binding.ivMenu.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        binding.ivAddFriend.setOnClickListener { showAddFriendDialog() }

        val headerView = navigationView.getHeaderView(0)
        headerView.findViewById<ImageView>(R.id.ivRefresh)?.setOnClickListener {
            loadFriends()
            loadMyProfileAvatar()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        navigationView.setNavigationItemSelectedListener(this)
    }

    private fun updateDrawerHeader() {
        val headerView = navigationView.getHeaderView(0)
        val tvUsername = headerView.findViewById<TextView>(R.id.tvDrawerUsername)
        val username = SecureStorage.getString(SecureStorage.KEY_USERNAME, "用户")
        tvUsername?.text = username
    }

    private fun setupFriendsList() {
        friendAdapter = FriendListAdapter { friend ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("friend_uid", friend.uid)
                putExtra("friend_name", friend.username)
            }
            startActivity(intent)
        }
        binding.recyclerFriends.layoutManager = LinearLayoutManager(this)
        binding.recyclerFriends.adapter = friendAdapter
    }

    private fun loadFriends() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        if (serverUrl.isEmpty() || token.isEmpty()) return

        lifecycleScope.launch {
            val friendsDeferred = async { ApiClient.getFriends(serverUrl, token) }
            val md5Deferred = async { ApiClient.getFriendAvatarMd5(serverUrl, token) }

            val friendsResult = friendsDeferred.await()
            val md5Result = md5Deferred.await()

            if (friendsResult is ApiResult.Success) {
                friendAdapter.submitList(friendsResult.data)
            } else {
                Toast.makeText(this@MainActivity, "加载好友失败", Toast.LENGTH_SHORT).show()
            }

            if (md5Result is ApiResult.Success) {
                friendAvatarMd5Map = md5Result.data
                friendAdapter.avatarMd5Map = friendAvatarMd5Map

                if (friendsResult is ApiResult.Success) {
                    for (friend in friendsResult.data) {
                        val md5 = friendAvatarMd5Map[friend.uid]
                        val localFile = AvatarCacheManager.getLocalAvatarFile(this@MainActivity, friend.uid, md5)
                        if (localFile == null && md5 != null) {
                            launch {
                                AvatarCacheManager.downloadAvatar(this@MainActivity, serverUrl, friend.uid, md5)
                                friendAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadMyProfileAvatar() {
        val uid = SecureStorage.getString(SecureStorage.KEY_UID)
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        if (uid.isEmpty() || serverUrl.isEmpty()) return

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                        val url = java.net.URL("${baseUrl}?action=get_avatar&uid=$uid")
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000

                        if (connection.responseCode == 200) {
                            BitmapFactory.decodeStream(connection.inputStream)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    // 缓存到本地
                    val cacheDir = File(cacheDir, "eosmesh_avatars")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val cacheFile = File(cacheDir, "my_avatar_$uid.jpg")
                    FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }

                    // 更新侧边栏头像
                    val headerView = navigationView.getHeaderView(0)
                    val ivAvatar = headerView.findViewById<ImageView>(R.id.ivDrawerAvatar)
                    ivAvatar?.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showAddFriendDialog() {
        val dialogBinding = DialogAddFriendBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this, R.style.AlertDialogStyle)
            .setTitle("添加好友")
            .setView(dialogBinding.root)
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()

        dialogBinding.etFriendName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                dialogBinding.cardSearchResult.visibility = View.GONE
                if (text.matches(Regex("^[A-Z0-9]{32}$"))) {
                    dialogBinding.progressSearch.visibility = View.VISIBLE
                    searchJob = lifecycleScope.launch {
                        delay(500)
                        searchUser(text, dialogBinding)
                    }
                }
            }
        })
        dialog.show()
    }

    private suspend fun searchUser(uid: String, dialogBinding: DialogAddFriendBinding) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        when (val result = ApiClient.getUserInfo(serverUrl, token, uid)) {
            is ApiResult.Success -> {
                dialogBinding.progressSearch.visibility = View.GONE
                val user = result.data
                dialogBinding.tvResultUsername.text = user.username
                dialogBinding.tvResultUid.text = user.uid
                dialogBinding.tvResultAvatar.text = user.username.take(1).uppercase()
                dialogBinding.cardSearchResult.visibility = View.VISIBLE
                dialogBinding.llSearchResult.setOnClickListener {
                    showConfirmAddDialog(user.uid, user.username, dialogBinding)
                }
            }
            else -> {
                dialogBinding.progressSearch.visibility = View.GONE
                dialogBinding.cardSearchResult.visibility = View.GONE
            }
        }
    }

    private fun showConfirmAddDialog(uid: String, username: String, dialogBinding: DialogAddFriendBinding) {
        MaterialAlertDialogBuilder(this, R.style.AlertDialogStyle)
            .setTitle("添加好友")
            .setMessage("确定要添加 $username 为好友吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                addFriend(uid)
                dialogBinding.etFriendName.text?.clear()
                dialogBinding.cardSearchResult.visibility = View.GONE
            }
            .show()
    }

    private fun addFriend(uid: String) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        lifecycleScope.launch {
            when (val result = ApiClient.addFriend(serverUrl, token, uid)) {
                is ApiResult.Success -> {
                    val msg = result.data
                    val successMsg = when {
                        msg.contains("Friend added") -> "好友添加成功"
                        msg.contains("Friend request sent") -> "好友申请已发送，等待对方同意"
                        else -> msg
                    }
                    Toast.makeText(this@MainActivity, successMsg, Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this@MainActivity, "添加失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_user -> startActivity(Intent(this, UserActivity::class.java))
            R.id.menu_about -> { startActivity(Intent(this, AboutActivity::class.java)) }
            R.id.menu_messages -> startActivity(Intent(this, MessagesActivity::class.java))
            R.id.menu_logout -> logout()
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun logout() {
        SecureStorage.clearAuth()
        AvatarCacheManager.clearCache(this)
        TokenManager.stopAutoRefresh()
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }
}