package com.nt.eosmesh.model

data class MessagesResponse(
    val messages: List<MessageItem>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)