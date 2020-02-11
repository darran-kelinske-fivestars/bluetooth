package com.fivestars.bluetooth.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TestMessage(val time: Long, val messageType: MessageType, val payload: String)

enum class MessageType {
    BIDIRECTIONAL, UNIDIRECTIONAL
}