package com.nt.eosmesh

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nt.eosmesh.databinding.ActivityAuthBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.SecureStorage
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAuthBinding
    private var isLoginMode = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置状态栏颜色与顶栏一致 (#151515)
        window.statusBarColor = ContextCompat.getColor(this, R.color.toolbar_background)
        
        // 初始化加密存储
        SecureStorage.init(this)
        
        // 检查自动登录（默认自动登录已开启）
        if (SecureStorage.getBoolean(SecureStorage.KEY_AUTO_LOGIN) && SecureStorage.isLoggedIn()) {
            startMainActivity()
            return
        }
        
        // 加载保存的服务器地址和账号密码
        loadSavedData()
        
        binding.btnSubmit.setOnClickListener {
            if (isLoginMode) login() else register()
        }
        
        binding.tvToggleMode.setOnClickListener {
            toggleMode()
        }
    }
    
    private fun loadSavedData() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL, "http://127.0.0.1:8001/")
        val username = SecureStorage.getString(SecureStorage.KEY_USERNAME)
        val password = SecureStorage.getString(SecureStorage.KEY_PASSWORD)
        
        binding.etServerUrl.setText(serverUrl)
        binding.etUsername.setText(username)
        binding.etPassword.setText(password)
        // 不再需要设置 Remember 复选框
    }
    
    private fun toggleMode() {
        isLoginMode = !isLoginMode
        binding.tvTitle.text = if (isLoginMode) "登录 EosMesh" else "注册 EosMesh"
        binding.btnSubmit.text = if (isLoginMode) "登 录" else "注 册"
        binding.tvToggleMode.text = if (isLoginMode) "没有账号？立即注册" else "已有账号？立即登录"
        binding.tvError.visibility = View.GONE
    }
    
    private fun login() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        
        if (!validateInput(serverUrl, username, password)) return
        
        // 保存凭证并默认开启自动登录
        saveCredentials(serverUrl, username, password)
        setLoading(true)
        binding.tvError.visibility = View.GONE
        
        lifecycleScope.launch {
            when (val result = ApiClient.login(serverUrl, username, password)) {
                is ApiResult.Success -> {
                    setLoading(false)
                    saveAuthData(result.data.uid, result.data.token)
                    Toast.makeText(this@AuthActivity, "登录成功", Toast.LENGTH_SHORT).show()
                    startMainActivity()
                }
                is ApiResult.Error -> {
                    setLoading(false)
                    when (result.code) {
                        401 -> showError("用户名或密码错误")
                        else -> showError(result.message)
                    }
                }
                ApiResult.NetworkError -> {
                    setLoading(false)
                    showError("网络连接失败，请检查服务器地址")
                }
            }
        }
    }
    
    private fun register() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        
        if (!validateInput(serverUrl, username, password)) return
        if (username.length > 12) {
            showError("用户名不能超过12个字符")
            return
        }
        
        // 保存凭证并默认开启自动登录
        saveCredentials(serverUrl, username, password)
        setLoading(true)
        binding.tvError.visibility = View.GONE
        
        lifecycleScope.launch {
            when (val result = ApiClient.register(serverUrl, username, password)) {
                is ApiResult.Success -> {
                    setLoading(false)
                    saveAuthData(result.data.uid, result.data.token)
                    Toast.makeText(this@AuthActivity, "注册成功", Toast.LENGTH_SHORT).show()
                    startMainActivity()
                }
                is ApiResult.Error -> {
                    setLoading(false)
                    when (result.code) {
                        409 -> showError("用户名已存在")
                        400 -> showError(result.message)
                        else -> showError(result.message)
                    }
                }
                ApiResult.NetworkError -> {
                    setLoading(false)
                    showError("网络连接失败，请检查服务器地址")
                }
            }
        }
    }
    
    private fun validateInput(serverUrl: String, username: String, password: String): Boolean {
        return when {
            TextUtils.isEmpty(serverUrl) -> {
                showError("请填写站点链接")
                false
            }
            TextUtils.isEmpty(username) -> {
                showError("请填写用户名")
                false
            }
            TextUtils.isEmpty(password) -> {
                showError("请填写密码")
                false
            }
            else -> true
        }
    }
    
    private fun saveCredentials(serverUrl: String, username: String, password: String) {
        SecureStorage.saveString(SecureStorage.KEY_SERVER_URL, serverUrl)
        SecureStorage.saveString(SecureStorage.KEY_USERNAME, username)
        SecureStorage.saveString(SecureStorage.KEY_PASSWORD, password)
        // 默认强制开启自动登录
        SecureStorage.saveBoolean(SecureStorage.KEY_AUTO_LOGIN, true)
    }
    
    private fun saveAuthData(uid: String, token: String) {
        SecureStorage.saveString(SecureStorage.KEY_UID, uid)
        SecureStorage.saveString(SecureStorage.KEY_TOKEN, token)
    }
    
    private fun setLoading(isLoading: Boolean) {
        binding.btnSubmit.isEnabled = !isLoading
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnSubmit.text = if (isLoading) "" else (if (isLoginMode) "登 录" else "注 册")
        binding.etServerUrl.isEnabled = !isLoading
        binding.etUsername.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading
        binding.tvToggleMode.isEnabled = !isLoading
    }
    
    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }
    
    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}