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
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.channels.BroadcastChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

object MessageUtil {

    var started: Boolean = false
    lateinit var device: BluetoothDevice
    val readChannel = BroadcastChannel<String>(1)
    val writeChannel = BroadcastChannel<String>(1)
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var mInsecureAcceptThread: AcceptThread? = null
    private var mConnectThread: ConnectThread? = null
    private val connectedThreads: ArrayList<ConnectedThread> = arrayListOf()
    private var currentUuid = UUID.randomUUID()

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()  */
    @Synchronized
    fun start() {
        if (D) Log.d(
            TAG,
            "start"
        )
        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread!!.cancel()
            mConnectThread = null
        }

        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = AcceptThread(bluetoothAdapter, currentUuid)
            mInsecureAcceptThread!!.start()
        }
        mState = STATE_LISTEN
        started = true
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    @Synchronized
    fun connect(device: BluetoothDevice) {
        if (D) Log.d(
            TAG,
            "connect to: $device"
        )
        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread!!.cancel()
                mConnectThread = null
            }
        }
        // Start the thread to connect with the given device
        mConnectThread = ConnectThread(device)
        mConnectThread!!.start()
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    @Synchronized
    fun connected(
        socket: BluetoothSocket,
        device: BluetoothDevice,
        socketType: String
    ) {
        this.device = device
        if (D) Log.d(
            TAG,
            "connected, Socket Type:$socketType"
        )
        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread?.state = STATE_CONNECTED
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread!!.cancel()
            mInsecureAcceptThread = AcceptThread(bluetoothAdapter, currentUuid)
            mInsecureAcceptThread?.start()
        }
        // Start the thread to manage the connection and perform transmissions
        val connectedThread = ConnectedThread(socket, socketType)
        connectedThread.run {
            start()
            connectedThreads.add(this)
        }

    }

    /**
     * Stop all threads
     */
    @Synchronized
    fun stop() {
        if (D) Log.d(
            TAG,
            "stop"
        )
        if (mConnectThread != null) {
            mConnectThread?.state = STATE_NONE
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread!!.cancel()
            mInsecureAcceptThread = null
        }

    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread.write
     */
    fun write(out: ByteArray?) { // Create temporary object
        var connectedThread: ConnectedThread?
        // Synchronize a copy of the ConnectedThread
        synchronized(this) {
            connectedThread = connectedThreads[0]
            if (connectedThread?.mState != STATE_CONNECTED) return
        }
        // Perform the write unsynchronized
        out?.run { connectedThread?.write(this) }
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private fun connectionFailed() { // Send a failure message back to the Activity
        // Start the service over to restart listening mode
        start()
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private fun connectionLost() { // Send a failure message back to the Activity
        // Start the service over to restart listening mode
        start()
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread(private val mmDevice: BluetoothDevice) :
        Thread() {

        /**
         * Return the current connection state.  */// Give the new state to the Handler so the UI Activity can update
        /**
         * Set the current state of the chat connection
         * @param state  An integer defining the current connection state
         */
        @get:Synchronized
        @set:Synchronized
        var state: Int?
            get() = mState
            set(state) {
                if (D) Log.d(
                    TAG,
                    "setState() $mState -> $state"
                )
                mState = state
            }


        private val mmSocket: BluetoothSocket?
        private val mSocketType: String
        override fun run() {
            Log.i(
                TAG,
                "BEGIN mConnectThread SocketType:$mSocketType"
            )
            name = "ConnectThread$mSocketType"
            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter.cancelDiscovery()
            // Make a connection to the BluetoothSocket
            try { // This is a blocking call and will only return on a
// successful connection or an exception
                mmSocket!!.connect()
            } catch (e: IOException) { // Close the socket
                try {
                    mmSocket!!.close()
                } catch (e2: IOException) {
                    Log.e(
                        TAG, "unable to close() " + mSocketType +
                                " socket during connection failure", e2
                    )
                }
                connectionFailed()
                return
            }
            // Reset the ConnectThread because we're done
            synchronized(this) { mConnectThread = null }
            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType)
        }

        fun cancel() {
            try {
                mmSocket!!.close()
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "close() of connect $mSocketType socket failed",
                    e
                )
            }
        }

        init {
            var tmp: BluetoothSocket? = null
            mSocketType = "Insecure"
            // Get a BluetoothSocket for a connection with the
// given BluetoothDevice
            try {
                tmp =
                    mmDevice.createInsecureRfcommSocketToServiceRecord(
                        currentUuid
                    )

            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Socket Type: " + mSocketType + "create() failed",
                    e
                )
            }
            mmSocket = tmp
            currentUuid = UUID.randomUUID()
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread(
        socket: BluetoothSocket,
        socketType: String
    ) : Thread() {
        var mState = STATE_NONE
        private val mmSocket: BluetoothSocket?
        private val mmInStream: InputStream?
        private val mmOutStream: OutputStream?
        override fun run() {
            Log.i(TAG, "BEGIN mConnectedThread")
            val buffer = ByteArray(1024)
            var bytes: Int
            // Keep listening to the InputStream while connected
            while (true) {
                try { // Read from the InputStream
                    bytes = mmInStream!!.read(buffer)
                    val readMessage = String(buffer,0, bytes)
                    readChannel.offer(readMessage)
                } catch (e: IOException) {
                    Log.e(TAG, "disconnected", e)
                    connectionLost()
                    // Start the service over to restart listening mode
                    MessageUtil.start()
                    break
                }
            }
        }

        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        fun write(buffer: ByteArray) {
            try {
                mmOutStream!!.write(buffer)
                // Share the sent message back to the UI Activity
                val writeMessage = String(buffer,0, buffer.size)
                writeChannel.offer(writeMessage)
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }
        }

        fun cancel() {
            try {
                mmSocket!!.close()
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "close() of connect socket failed",
                    e
                )
            }
        }

        init {
            Log.d(
                TAG,
                "create ConnectedThread: $socketType"
            )
            mmSocket = socket
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.inputStream
                tmpOut = socket.outputStream
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "temp sockets not created",
                    e
                )
            }
            mmInStream = tmpIn
            mmOutStream = tmpOut
        }
    }


        // Debugging
        const val TAG = "BluetoothChatService"
        private const val D = true
        // Name for the SDP record when creating server socket
        private const val NAME_INSECURE = "BluetoothChatInsecure"
        // Constants that indicate the current connection state
        const val STATE_NONE = 0 // we're doing nothing
        const val STATE_LISTEN = 1 // now listening for incoming connections
        const val STATE_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_CONNECTED = 3 // now connected to a remote device
        var mState: Int? = null


    init {
        mState = STATE_NONE
    }
}