package com.nt.eosmesh.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AvatarCacheManager {

    private const val AVATAR_CACHE_DIR = "eosmesh_avatars"

    /**
     * 获取头像缓存目录
     */
    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, AVATAR_CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取本地缓存的头像文件路径
     * @param uid 用户ID
     * @param md5 头像MD5值（为null时返回null）
     * @return 本地头像文件，如果不存在则返回null
     */
    fun getLocalAvatarFile(context: Context, uid: String, md5: String?): File? {
        if (md5.isNullOrEmpty()) return null
        
        val cacheDir = getCacheDir(context)
        
        // 清理该用户的旧头像文件（不同MD5的文件）
        try {
            val oldFiles = cacheDir.listFiles { file ->
                file.name.startsWith("${uid}_") && !file.name.contains(md5)
            }
            oldFiles?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 返回当前MD5对应的文件
        val avatarFile = File(cacheDir, "${uid}_${md5}.jpg")
        return if (avatarFile.exists() && avatarFile.length() > 0) {
            avatarFile
        } else {
            null
        }
    }

    /**
     * 从服务器下载头像并缓存到本地
     * @return 下载成功返回Bitmap，失败返回null
     */
    suspend fun downloadAvatar(
        context: Context,
        serverUrl: String,
        uid: String,
        md5: String?
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (md5.isNullOrEmpty()) return@withContext null

        try {
            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val url = URL("${baseUrl}?action=get_avatar&uid=${java.net.URLEncoder.encode(uid, "UTF-8")}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "image/*")
            }

            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()

                if (bitmap != null) {
                    saveToCache(context, uid, md5, bitmap)
                }
                return@withContext bitmap
            } else {
                connection.disconnect()
                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * 保存 Bitmap 到本地缓存
     */
    private fun saveToCache(context: Context, uid: String, md5: String, bitmap: Bitmap) {
        try {
            val cacheDir = getCacheDir(context)
            
            // 删除该用户的旧头像
            try {
                val oldFiles = cacheDir.listFiles { file ->
                    file.name.startsWith("${uid}_") && file.name != "${uid}_${md5}.jpg"
                }
                oldFiles?.forEach { it.delete() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // 保存新头像
            val avatarFile = File(cacheDir, "${uid}_${md5}.jpg")
            FileOutputStream(avatarFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清除所有缓存的头像
     */
    fun clearCache(context: Context) {
        try {
            val cacheDir = getCacheDir(context)
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}