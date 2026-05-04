package com.nt.eosmesh.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object SecureStorage {
    
    private const val PREFS_NAME = "eosmesh_secure_prefs"
    
    // 存储的键名
    const val KEY_SERVER_URL = "server_url"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_UID = "uid"
    const val KEY_TOKEN = "token"
    const val KEY_AUTO_LOGIN = "auto_login"
    
    private lateinit var prefs: SharedPreferences
    
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = EncryptedSharedPreferences.create(
                PREFS_NAME,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
    
    fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    
    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }
    
    fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }
    
    fun clear() {
        prefs.edit().clear().apply()
    }
    
    fun clearAuth() {
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_TOKEN)
            .apply()
    }
    
    fun isLoggedIn(): Boolean {
        val uid = getString(KEY_UID)
        val token = getString(KEY_TOKEN)
        return uid.isNotEmpty() && token.isNotEmpty()
    }
}