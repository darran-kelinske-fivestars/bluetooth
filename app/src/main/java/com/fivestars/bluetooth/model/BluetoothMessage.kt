package com.fivestars.bluetooth.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BluetoothMessage(val time: Long, byteArray: ByteArray)