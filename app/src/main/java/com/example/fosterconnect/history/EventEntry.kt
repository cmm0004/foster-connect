package com.example.fosterconnect.history

enum class EventType(val display: String, val colorHex: String) {
    VOMITING("Vomiting", "#D32F2F");
}

data class EventEntry(val dateMillis: Long, val type: EventType)
