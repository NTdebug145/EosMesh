package com.nt.eosmesh.utils

import com.nt.eosmesh.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private const val TIMEOUT = 15_000

    // ----------- 注册/登录 -----------
    suspend fun register(serverUrl: String, username: String, password: String): ApiResult<AuthResult> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "register"), "POST")
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            val code = json.optInt("code")
            if (code == 200) {
                val data = json.optJSONObject("data")
                ApiResult.Success(AuthResult(data?.optString("uid") ?: "", data?.optString("token") ?: ""))
            } else ApiResult.Error(code, json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun login(serverUrl: String, username: String, password: String): ApiResult<AuthResult> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "login"), "POST")
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            val code = json.optInt("code")
            if (code == 200) {
                val data = json.optJSONObject("data")
                ApiResult.Success(AuthResult(data?.optString("uid") ?: "", data?.optString("token") ?: ""))
            } else ApiResult.Error(code, json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    // ----------- 好友 -----------
    suspend fun getFriends(serverUrl: String, token: String): ApiResult<List<FriendItem>> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "get_friends"), "GET", token)
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) {
                val arr = json.optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<FriendItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(FriendItem(
                        uid = obj.optString("uid"),
                        username = obj.optString("username"),
                        avatar = obj.optString("avatar", null)
                    ))
                }
                ApiResult.Success(list)
            } else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun addFriend(serverUrl: String, token: String, username: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "add_friend"), "POST", token)
            val body = JSONObject().put("username", username)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) ApiResult.Success(json.optString("msg"))
            else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun getFriendRequests(serverUrl: String, token: String): ApiResult<List<FriendRequestItem>> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "get_friend_requests"), "GET", token)
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) {
                val arr = json.optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<FriendRequestItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(FriendRequestItem(
                        id = obj.optString("id"),
                        fromUid = obj.optString("from_uid"),
                        fromUsername = obj.optString("from_username"),
                        message = obj.optString("message"),
                        time = obj.optLong("time")
                    ))
                }
                ApiResult.Success(list)
            } else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun acceptFriendRequest(serverUrl: String, token: String, requestId: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "accept_friend_request"), "POST", token)
            val body = JSONObject().put("request_id", requestId)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) ApiResult.Success(json.optString("msg"))
            else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun getVerifySetting(serverUrl: String, token: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "get_verify_setting"), "GET", token)
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) {
                val data = json.optJSONObject("data")
                ApiResult.Success(data?.optString("mode") ?: "need_verify")
            } else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    suspend fun setVerifySetting(serverUrl: String, token: String, mode: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "set_verify_setting"), "POST", token)
            val body = JSONObject().put("mode", mode)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) ApiResult.Success(json.optString("msg"))
            else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    // ----------- 消息 -----------
suspend fun getMessages(
    serverUrl: String, token: String, friendUid: String,
    page: Int? = null, limit: Int = 20, since: Long? = null
): ApiResult<MessagesResponse> = withContext(Dispatchers.IO) {
    try {
        val baseUrl = buildUrl(serverUrl, "get_messages")
        var urlStr = "$baseUrl&friend_uid=$friendUid&limit=$limit"
        if (page != null) urlStr += "&page=$page"
        if (since != null && since > 0) urlStr += "&since=$since"

        val connection = createConnection(URL(urlStr), "GET", token)
        val json = JSONObject(connection.readText())
        if (json.optInt("code") != 200) {
            return@withContext ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }

        // 区分 data 是数组（since 模式）还是对象（分页模式）
        val dataObj = json.opt("data")
        val messages: List<MessageItem> = when {
            dataObj is org.json.JSONArray -> {
                val list = mutableListOf<MessageItem>()
                for (i in 0 until dataObj.length()) {
                    val item = dataObj.getJSONObject(i)
                    list.add(MessageItem(
                        from = item.optString("from"),
                        to = item.optString("to"),
                        content = item.optString("content"),
                        time = item.optLong("time")
                    ))
                }
                list
            }
            dataObj is JSONObject -> {
                val arr = dataObj.optJSONArray("messages") ?: org.json.JSONArray()
                val list = mutableListOf<MessageItem>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(MessageItem(
                        from = item.optString("from"),
                        to = item.optString("to"),
                        content = item.optString("content"),
                        time = item.optLong("time")
                    ))
                }
                list
            }
            else -> emptyList()
        }

        // 当是 since 模式时，没有分页信息，使用默认值
        val totalPages = if (dataObj is JSONObject) dataObj.optInt("total_pages") else 1
        val total = if (dataObj is JSONObject) dataObj.optInt("total") else messages.size
        val currentPage = if (dataObj is JSONObject) dataObj.optInt("page") else 1
        val resultLimit = if (dataObj is JSONObject) dataObj.optInt("limit") else messages.size

        ApiResult.Success(
            MessagesResponse(
                messages = messages,
                total = total,
                page = currentPage,
                limit = resultLimit,
                totalPages = totalPages
            )
        )
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}

    suspend fun sendMessage(serverUrl: String, token: String, toUid: String, content: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection(buildUrl(serverUrl, "send_message"), "POST", token)
            val body = JSONObject().apply {
                put("to_uid", toUid)
                put("content", content)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = JSONObject(connection.readText())
            if (json.optInt("code") == 200) ApiResult.Success(json.optString("msg"))
            else ApiResult.Error(json.optInt("code"), json.optString("msg"))
        } catch (e: Exception) { ApiResult.NetworkError }
    }

    // ----------- 工具 -----------
    private fun buildUrl(serverUrl: String, action: String): URL {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return URL("${base}?action=$action")
    }

    private fun createConnection(url: URL, method: String, token: String? = null): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            token?.let { setRequestProperty("Authorization", it) }
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            if (method == "POST") doOutput = true
        }
    }

    private fun HttpURLConnection.readText(): String {
        return try {
            if (responseCode in 200..299) inputStream.bufferedReader().use { it.readText() }
            else errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
        } catch (e: Exception) { "{}" }
    }
    suspend fun getUserInfo(
    serverUrl: String,
    token: String,
    uid: String
): ApiResult<UserInfo> = withContext(Dispatchers.IO) {
    try {
        val connection = createConnection(buildUrl(serverUrl, "get_user_info"), "POST", token)
        val body = JSONObject().put("uid", uid)
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            val data = json.optJSONObject("data")!!
            ApiResult.Success(
                UserInfo(
                    uid = data.optString("uid"),
                    username = data.optString("username"),
                    avatar = data.optString("avatar", null),
                    registeredAt = data.optLong("registered_at"),
                    stationId = data.optString("station_id"),
                    friendVerify = data.optString("friend_verify"),
                    messageCount = data.optInt("message_count")
                )
            )
        } else ApiResult.Error(json.optInt("code"), json.optString("msg"))
    } catch (e: Exception) { ApiResult.NetworkError }
}


/**
 * 刷新Token（需要当前有效Token）
 */
suspend fun refreshToken(serverUrl: String, token: String): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val connection = createConnection(buildUrl(serverUrl, "refresh_token"), "POST", token)
        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            val newToken = json.optJSONObject("data")?.optString("token") ?: ""
            if (newToken.isNotEmpty()) {
                ApiResult.Success(newToken)
            } else {
                ApiResult.Error(500, "Empty token in response")
            }
        } else {
            ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}
suspend fun uploadAvatar(
    serverUrl: String,
    token: String,
    fileData: ByteArray,
    fileName: String,
    mimeType: String
): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val url = buildUrl(serverUrl, "upload_avatar")
        val boundary = "EosMesh-${System.currentTimeMillis()}"
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", token)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
        }

        val outputStream = connection.outputStream
        outputStream.write("--$boundary\r\n".toByteArray())
        outputStream.write("Content-Disposition: form-data; name=\"avatar\"; filename=\"$fileName\"\r\n".toByteArray())
        outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        outputStream.write(fileData)
        outputStream.write("\r\n--$boundary--\r\n".toByteArray())
        outputStream.flush()
        outputStream.close()

        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            ApiResult.Success(json.optString("msg"))
        } else {
            ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}
/**
 * 获取好友头像的 MD5 值映射
 */
suspend fun getFriendAvatarMd5(
    serverUrl: String,
    token: String
): ApiResult<Map<String, String?>> = withContext(Dispatchers.IO) {
    try {
        val url = buildUrl(serverUrl, "get_friend_avatar_img_md5")
        val connection = createConnection(url, "GET", token)
        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            val data = json.optJSONObject("data")
            val map = mutableMapOf<String, String?>()
            data?.keys()?.forEach { key ->
                map[key] = data.optString(key, null)
            }
            ApiResult.Success(map)
        } else {
            ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}
/**
 * 获取站点版本
 */
suspend fun getStationVersion(serverUrl: String): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val url = buildUrl(serverUrl, "get_station_version")
        val connection = createConnection(url, "GET")
        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            val data = json.optJSONObject("data")
            ApiResult.Success(data?.optString("version") ?: "")
        } else {
            ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}

/**
 * 获取服务器类型
 */
suspend fun getServerType(serverUrl: String): ApiResult<String> = withContext(Dispatchers.IO) {
    try {
        val url = buildUrl(serverUrl, "get_server_type")
        val connection = createConnection(url, "GET")
        val json = JSONObject(connection.readText())
        if (json.optInt("code") == 200) {
            val data = json.optJSONObject("data")
            ApiResult.Success(data?.optString("type") ?: "")
        } else {
            ApiResult.Error(json.optInt("code"), json.optString("msg"))
        }
    } catch (e: Exception) {
        ApiResult.NetworkError
    }
}
}