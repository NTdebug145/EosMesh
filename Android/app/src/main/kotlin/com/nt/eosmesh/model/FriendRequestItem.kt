package com.nt.eosmesh.model

data class FriendRequestItem(
    val id: String,
    val fromUid: String,
    val fromUsername: String,
    val message: String,
    val time: Long
)