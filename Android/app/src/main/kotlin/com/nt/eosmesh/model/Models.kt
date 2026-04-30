package com.nt.eosmesh.model

data class AuthResult(
    val uid: String,
    val token: String
)

data class UserInfo(
    val uid: String,
    val username: String,
    val avatar: String?,
    val registeredAt: Long,
    val stationId: String,
    val friendVerify: String,
    val messageCount: Int
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    object NetworkError : ApiResult<Nothing>()
}