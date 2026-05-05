package com.example.fosterconnect.history

data class Message(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val timestamp: Long,
    val fosterCaseId: String,
    var isRead: Boolean = false
)
