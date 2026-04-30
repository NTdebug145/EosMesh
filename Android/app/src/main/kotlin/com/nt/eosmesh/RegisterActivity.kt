package com.nt.eosmesh

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nt.eosmesh.databinding.ActivityRegisterBinding
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RegisterActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityRegisterBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val prefs = getSharedPreferences("eos_prefs", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "http://127.0.0.1:8001/")
        binding.etServerUrl.setText(savedUrl)
        
        binding.btnRegister.setOnClickListener {
            register()
        }
    }
    
    private fun register() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        
        when {
            TextUtils.isEmpty(serverUrl) -> {
                showError("请输入服务器地址")
                return
            }
            TextUtils.isEmpty(username) -> {
                showError("请输入用户名")
                return
            }
            username.length > 12 -> {
                showError("用户名不能超过12个字符")
                return
            }
            TextUtils.isEmpty(password) -> {
                showError("请输入密码")
                return
            }
        }
        
        getSharedPreferences("eos_prefs", MODE_PRIVATE)
            .edit().putString("server_url", serverUrl).apply()
        
        setLoading(true)
        
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    registerApi(serverUrl, username, password)
                }
                
                setLoading(false)
                
                when (result) {
                    is RegisterResult.Success -> {
                        showSuccess("注册成功！\nUID: ${result.uid}")
                        saveLoginInfo(result.uid, result.token)
                    }
                    is RegisterResult.Error -> {
                        showError(result.message)
                    }
                }
            } catch (e: Exception) {
                setLoading(false)
                showError("网络错误: ${e.message}")
            }
        }
    }
    
    private fun registerApi(serverUrl: String, username: String, password: String): RegisterResult {
        return try {
            // 确保 URL 格式正确
            val baseUrl = when {
                serverUrl.endsWith("/") -> serverUrl
                serverUrl.endsWith(".php") -> serverUrl
                else -> "$serverUrl/"
            }
            
            val apiUrl = if (baseUrl.contains("?")) {
                "$baseUrl&action=register"
            } else {
                "${baseUrl}?action=register"
            }
            
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            
            val requestBody = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            val responseText = try {
                if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
            } catch (e: Exception) {
                // 如果读取失败，尝试从错误流读取
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            
            // 清理响应文本（移除可能的 HTML 标签）
            val cleanResponse = responseText
                .replace(Regex("<[^>]*>"), "") // 移除 HTML 标签
                .replace("&nbsp;", " ")
                .trim()
            
            // 尝试解析 JSON
            try {
                val jsonResponse = JSONObject(cleanResponse)
                val code = jsonResponse.optInt("code", -1)
                val msg = jsonResponse.optString("msg", "未知错误")
                
                if (code == 200) {
                    val data = jsonResponse.optJSONObject("data")
                    RegisterResult.Success(
                        uid = data?.optString("uid") ?: "",
                        token = data?.optString("token") ?: ""
                    )
                } else {
                    RegisterResult.Error(msg)
                }
            } catch (e: JSONException) {
                // JSON 解析失败，返回原始响应
                RegisterResult.Error("服务器响应异常: ${cleanResponse.take(100)}")
            }
            
        } catch (e: Exception) {
            RegisterResult.Error("请求失败: ${e.message}")
        }
    }
    
    private fun setLoading(isLoading: Boolean) {
        binding.btnRegister.isEnabled = !isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnRegister.text = if (isLoading) "" else "注 册"
        binding.etServerUrl.isEnabled = !isLoading
        binding.etUsername.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
    }
    
    private fun showError(message: String) {
        binding.tvResult.text = message
        binding.tvResult.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        binding.tvResult.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showSuccess(message: String) {
        binding.tvResult.text = message
        binding.tvResult.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        binding.tvResult.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.etPassword.text?.clear()
    }
    
    private fun saveLoginInfo(uid: String, token: String) {
        getSharedPreferences("eos_prefs", MODE_PRIVATE)
            .edit()
            .putString("uid", uid)
            .putString("token", token)
            .apply()
    }
    
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    
    sealed class RegisterResult {
        data class Success(val uid: String, val token: String) : RegisterResult()
        data class Error(val message: String) : RegisterResult()
    }
}