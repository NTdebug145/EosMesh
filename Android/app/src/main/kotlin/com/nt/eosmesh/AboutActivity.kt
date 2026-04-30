package com.nt.eosmesh

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nt.eosmesh.databinding.ActivityAboutBinding
import com.nt.eosmesh.model.ApiResult
import com.nt.eosmesh.utils.ApiClient
import com.nt.eosmesh.utils.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 加载服务器信息
        loadServerInfo()

        // GitHub 链接
        binding.ivGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NTdebug145/EosMesh"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadServerInfo() {
        val serverUrl = SecureStorage.getString(SecureStorage.KEY_SERVER_URL)
        if (serverUrl.isEmpty()) return

        lifecycleScope.launch {
            try {
                val version = withContext(Dispatchers.IO) {
                    when (val result = ApiClient.getStationVersion(serverUrl)) {
                        is ApiResult.Success -> result.data
                        else -> null
                    }
                }

                val type = withContext(Dispatchers.IO) {
                    when (val result = ApiClient.getServerType(serverUrl)) {
                        is ApiResult.Success -> result.data
                        else -> null
                    }
                }

                if (version != null) {
                    binding.tvServerVersion.text = "$version - ${type ?: "unknown"}/Server"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}