package com.nt.eosmesh

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nt.eosmesh.databinding.ActivityUserBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.AvatarCacheManager
import com.nt.eosmesh.utils.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class UserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadAvatar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadUserInfo()

        binding.cardAvatar.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnFriendList.setOnClickListener {
            startActivity(Intent(this, FriendListActivity::class.java))
        }
    }

    private fun loadUserInfo() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        val uid = SecureStorage.getString(SecureStorage.KEY_UID)
        if (serverUrl.isEmpty() || token.isEmpty() || uid.isEmpty()) {
            Toast.makeText(this, "未登录", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            when (val result = ApiClient.getUserInfo(serverUrl, token, uid)) {
                is ApiResult.Success -> {
                    val user = result.data
                    // 显示基本用户信息
                    binding.tvUsername.text = user.username
                    binding.tvUid.text = user.uid
                    binding.tvRegTime.text = formatTime(user.registeredAt)
                    binding.tvStationId.text = user.stationId
                    binding.tvMsgCount.text = user.messageCount.toString()
                    
                    // 加载头像
                    loadAvatarFromServer(serverUrl, uid, token)
                }
                else -> {
                    showError("加载用户信息失败")
                }
            }
        }
    }

    /**
     * 从服务器下载用户头像
     */
    private fun loadAvatarFromServer(serverUrl: String, uid: String, token: String) {
        lifecycleScope.launch {
            try {
                // 先检查本地缓存
                val cacheDir = File(cacheDir, "eosmesh_avatars")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val cacheFile = File(cacheDir, "my_avatar_$uid.jpg")
                
                var bitmap: Bitmap? = null
                
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    // 从缓存加载
                    bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                }
                
                if (bitmap == null) {
                    // 从服务器下载
                    bitmap = downloadAvatarFromServer(serverUrl, uid, token)
                    if (bitmap != null) {
                        // 保存到缓存
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
                }
                
                if (bitmap != null) {
                    binding.ivAvatar.setImageBitmap(bitmap)
                } else {
                    // 使用默认头像
                    binding.ivAvatar.setImageResource(R.drawable.ic_user)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.ivAvatar.setImageResource(R.drawable.ic_user)
            }
        }
    }

    /**
     * 从服务器下载头像
     */
    private suspend fun downloadAvatarFromServer(
        serverUrl: String,
        uid: String,
        token: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val url = java.net.URL("${baseUrl}?action=get_avatar&uid=${java.net.URLEncoder.encode(uid, "UTF-8")}")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            // 添加 Authorization 头以通过验证
            connection.setRequestProperty("Authorization", token)

            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                bitmap
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun uploadAvatar(uri: Uri) {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        val token = SecureStorage.getString(SecureStorage.KEY_TOKEN)
        val uid = SecureStorage.getString(SecureStorage.KEY_UID)
        if (serverUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "未设置服务器地址", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val fileData = inputStream?.use { it.readBytes() } ?: return@launch
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                val extension = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    else -> "jpg"
                }
                val fileName = "avatar.$extension"

                when (val result = ApiClient.uploadAvatar(serverUrl, token, fileData, fileName, mimeType)) {
                    is ApiResult.Success -> {
                        Toast.makeText(this@UserActivity, "头像上传成功", Toast.LENGTH_SHORT).show()
                        // 清除旧的头像缓存
                        val cacheDir = File(cacheDir, "eosmesh_avatars")
                        val cacheFile = File(cacheDir, "my_avatar_$uid.jpg")
                        if (cacheFile.exists()) cacheFile.delete()
                        // 重新加载
                        loadUserInfo()
                    }
                    else -> showError("上传失败")
                }
            } catch (e: Exception) {
                showError("读取文件失败: ${e.message}")
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp * 1000))
        } catch (e: Exception) {
            "未知"
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}