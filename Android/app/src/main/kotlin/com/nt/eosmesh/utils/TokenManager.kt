package com.nt.eosmesh.utils

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.nt.eosmesh.AuthActivity
import com.nt.eosmesh.model.ApiResult
import kotlinx.coroutines.*

object TokenManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    fun startAutoRefresh(activity: AppCompatActivity) {
        stopAutoRefresh()
        refreshJob = scope.launch {
            while (isActive) {
                delay(250_000L) // 4分10秒
                tryRefreshWithRetry(activity)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private suspend fun tryRefreshWithRetry(activity: AppCompatActivity) {
        var success = false
        repeat(5) {
            if (it > 0) delay(5000)
            val result = ApiClient.refreshToken(
                SecureStorage.getString(SecureStorage.KEY_SERVER_URL),
                SecureStorage.getString(SecureStorage.KEY_TOKEN)
            )
            if (result is ApiResult.Success) {
                SecureStorage.saveString(SecureStorage.KEY_TOKEN, result.data)
                success = true
                return@repeat  // 退出 lambda，相当于 break
            }
        }
        if (!success) {
            SecureStorage.clearAuth()
            val intent = Intent(activity, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
            activity.finish()
        }
    }
}