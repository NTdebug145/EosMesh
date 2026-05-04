package com.nt.eosmesh.model

data class MessageItem(
    val from: String,
    val to: String,
    val content: String,
    val time: Long
)