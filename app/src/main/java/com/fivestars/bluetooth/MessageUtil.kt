/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fivestars.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.coroutines.CoroutineContext


class MessageUtil {

    val readChannel = BroadcastChannel<String>(1)
    val writeChannel = BroadcastChannel<String>(1)
    val statusChannel = BroadcastChannel<String>(1)
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    var stringBuffer = StringBuffer()
    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null
    private var bluetoothServerSocket: BluetoothServerSocket? = null

    fun listen() {
        CoroutineScope(
            newFixedThreadPoolContext(1, "uno")).launch {
            val localBluetoothServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                NAME_INSECURE,
                MY_UUID_INSECURE
            )

            bluetoothServerSocket = localBluetoothServerSocket

            try {
                val socket = localBluetoothServerSocket.accept()

//                localBluetoothServerSocket.close()

                socket?.run {
                    connect()
                    setupSocket(this)
                }
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Socket Type: accept() failed",
                    e
                )
            }
        }
    }

    fun connect(address: String) {
        CoroutineScope(
            newFixedThreadPoolContext(1, "dos")).launch {
            bluetoothAdapter.cancelDiscovery()
            val bluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
            val bluetoothSocket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(
                MY_UUID_INSECURE
            )
            bluetoothSocket.connect()
            setupSocket(bluetoothSocket)
        }
    }

    /**
     * Stop all threads
     */
    @Synchronized
    fun disconnect() {
        bluetoothServerSocket?.close()
    }

    private fun setupSocket(bluetoothSocket: BluetoothSocket) {
        inputStream = bluetoothSocket.inputStream
        outputStream = bluetoothSocket.outputStream

        val localInputStream = inputStream!!
        val buffer = ByteArray(1024)
        var bytes: Int

        while (true) {
            try {
                bytes = localInputStream.read(buffer)
                stringBuffer.append(String(buffer, 0, bytes))
                Log.d(TAG, "this is the current buffer: " + stringBuffer)
            } catch (e: IOException) {
                Log.e(TAG, "disconnected", e)
                connectionLost()
                break
            }
        }
    }

    fun send(byteArrayToWrite: ByteArray?) {
        byteArrayToWrite?.run { outputStream?.write(this) }
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private fun connectionFailed() { // Send a failure message back to the Activity
        // Start the service over to restart listening mode
//        start()
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private fun connectionLost() { // Send a failure message back to the Activity
        // Start the service over to restart listening mode
//        start()
    }

    companion object {
        // Debugging
        private const val TAG = "BluetoothChatService"
        // Name for the SDP record when creating server socket
        private const val NAME_INSECURE = "PhoneGapBluetoothSerialServiceInSecure"
        private val MY_UUID_INSECURE =
            UUID.fromString("23F18142-B389-4772-93BD-52BDBB2C03E9")
    }
}