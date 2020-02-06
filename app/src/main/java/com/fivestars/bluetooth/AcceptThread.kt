package com.fivestars.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.*

/**
 * This thread runs while listening for incoming connections. It behaves
 * like a server-side client. It runs until a connection is accepted
 * (or until cancelled).
 */
class AcceptThread(bluetoothAdapter: BluetoothAdapter, uuid: UUID) : Thread() {
    private val serverSocket: BluetoothServerSocket?

    init {
        var tmp: BluetoothServerSocket? = null
        // Create a new listening server socket
        try {
            tmp =
                bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    "FIVESTARS",
                    uuid)
        } catch (e: IOException) {
            Log.e(
                MessageUtil.TAG,
                "listen() failed",
                e
            )
        }
        serverSocket = tmp
    }

    override fun run() {
        if (BuildConfig.DEBUG) Log.d(
            MessageUtil.TAG,
            "BEGIN AcceptThread $this"
        )
        name = "AcceptThread"
        var socket: BluetoothSocket?
        // Listen to the server socket if we're not connected
        while (MessageUtil.mState != MessageUtil.STATE_CONNECTED) {
            socket = try { // This is a blocking call and will only return on a successful connection or an exception
                serverSocket?.accept()
            } catch (e: IOException) {
                Log.e(
                    MessageUtil.TAG,
                    "Socket Type: accept() failed",
                    e
                )
                break
            }
            // If a connection was accepted
            if (socket != null) {
                synchronized(this) {
                    when (MessageUtil.mState) {
                        MessageUtil.STATE_LISTEN, MessageUtil.STATE_CONNECTING ->  // Situation normal. Start the connected thread.
                            MessageUtil.connected(
                                socket, socket.remoteDevice
                            )
                        MessageUtil.STATE_NONE, MessageUtil.STATE_CONNECTED ->  // Either not ready or already connected. Terminate new socket.
                            try {
                                socket.close()
                            } catch (e: IOException) {
                                Log.e(
                                    MessageUtil.TAG,
                                    "Could not close unwanted socket",
                                    e
                                )
                            }
                        else -> Log.e(
                            MessageUtil.TAG,
                            "Unknown state."
                        )
                    }
                }
            }
            if (BuildConfig.DEBUG) Log.i(
                MessageUtil.TAG,
                "END mAcceptThread"
            )
        }
    }

    fun cancel() {
        if (BuildConfig.DEBUG) Log.d(
            MessageUtil.TAG,
            "Socket Type cancel $this"
        )
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(
                MessageUtil.TAG,
                "Socket Type close() of server failed",
                e
            )
        }
    }
}