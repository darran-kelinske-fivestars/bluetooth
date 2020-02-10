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
import kotlinx.coroutines.channels.BroadcastChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MessageUtil {

    lateinit var device: BluetoothDevice
    val readChannel = BroadcastChannel<String>(1)
    val writeChannel = BroadcastChannel<String>(1)
    private val bluetoothAdapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var mSecureAcceptThread: AcceptThread? = null
    private var mInsecureAcceptThread: AcceptThread? = null
    private var mConnectThread: ConnectThread? = null
    private var mConnectedThread: ConnectedThread? = null
    private var mState: Int

    /**
     * Return the current connection state.  */// Give the new state to the Handler so the UI Activity can update
    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    @get:Synchronized
    @set:Synchronized
    var state: Int
        get() = mState
        private set(state) {
            if (D) Log.d(
                TAG,
                "setState() $mState -> $state"
            )
            mState = state
            // Give the new state to the Handler so the UI Activity can update
//            mHandler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
        }

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
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        state = STATE_LISTEN
        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = AcceptThread(true)
            mSecureAcceptThread!!.start()
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = AcceptThread(false)
            mInsecureAcceptThread!!.start()
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     * @param device  The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    @Synchronized
    fun connect(device: BluetoothDevice, secure: Boolean) {
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
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        // Start the thread to connect with the given device
        mConnectThread = ConnectThread(device, secure)
        mConnectThread!!.start()
        state = STATE_CONNECTING
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    @Synchronized
    fun connected(
        socket: BluetoothSocket?,
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
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread!!.cancel()
            mSecureAcceptThread = null
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread!!.cancel()
            mInsecureAcceptThread = null
        }
        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = ConnectedThread(socket, socketType)
        mConnectedThread!!.start()
        state = STATE_CONNECTED
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
            mConnectThread!!.cancel()
            mConnectThread = null
        }
        if (mConnectedThread != null) {
            mConnectedThread!!.cancel()
            mConnectedThread = null
        }
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread!!.cancel()
            mSecureAcceptThread = null
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread!!.cancel()
            mInsecureAcceptThread = null
        }
        state = STATE_NONE
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
            if (mState != STATE_CONNECTED) return
            connectedThread = mConnectedThread
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
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private inner class AcceptThread(secure: Boolean) : Thread() {
        // The local server socket
        private val mmServerSocket: BluetoothServerSocket?
        private val mSocketType: String
        override fun run() {
            if (D) Log.d(
                TAG, "Socket Type: " + mSocketType +
                        "BEGIN mAcceptThread" + this
            )
            name = "AcceptThread$mSocketType"
            var socket: BluetoothSocket? = null
            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                socket = try { // This is a blocking call and will only return on a
// successful connection or an exception
                    mmServerSocket!!.accept()
                } catch (e: IOException) {
                    Log.e(
                        TAG,
                        "Socket Type: " + mSocketType + "accept() failed",
                        e
                    )
                    break
                }
                // If a connection was accepted
                if (socket != null) {
                    synchronized(this@MessageUtil) {
                        when (mState) {
                            STATE_LISTEN, STATE_CONNECTING ->  // Situation normal. Start the connected thread.
                                connected(
                                    socket, socket.remoteDevice,
                                    mSocketType
                                )
                            STATE_NONE, STATE_CONNECTED ->  // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close()
                                } catch (e: IOException) {
                                    Log.e(
                                        TAG,
                                        "Could not close unwanted socket",
                                        e
                                    )
                                }
                            else -> Log.e(TAG, "Unknown state.")
                        }
                    }
                }
            }
            if (D) Log.i(
                TAG,
                "END mAcceptThread, socket Type: $mSocketType"
            )
        }

        fun cancel() {
            if (D) Log.d(
                TAG,
                "Socket Type" + mSocketType + "cancel " + this
            )
            try {
                mmServerSocket!!.close()
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Socket Type" + mSocketType + "close() of server failed",
                    e
                )
            }
        }

        init {
            var tmp: BluetoothServerSocket? = null
            mSocketType = if (secure) "Secure" else "Insecure"
            // Create a new listening server socket
            try {
                tmp = if (secure) {
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        NAME_SECURE,
                        MY_UUID_SECURE
                    )
                } else {
                    bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        NAME_INSECURE,
                        MY_UUID_INSECURE
                    )
                }
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Socket Type: " + mSocketType + "listen() failed",
                    e
                )
            }
            mmServerSocket = tmp
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private inner class ConnectThread(private val mmDevice: BluetoothDevice, secure: Boolean) :
        Thread() {
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
            synchronized(this@MessageUtil) { mConnectThread = null }
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
            mSocketType = if (secure) "Secure" else "Insecure"
            // Get a BluetoothSocket for a connection with the
// given BluetoothDevice
            try {
                tmp = if (secure) {
                    mmDevice.createRfcommSocketToServiceRecord(
                        MY_UUID_SECURE
                    )
                } else {
                    mmDevice.createInsecureRfcommSocketToServiceRecord(
                        MY_UUID_INSECURE
                    )
                }
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Socket Type: " + mSocketType + "create() failed",
                    e
                )
            }
            mmSocket = tmp
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private inner class ConnectedThread(
        socket: BluetoothSocket?,
        socketType: String
    ) : Thread() {
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
                    this@MessageUtil.start()
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
                tmpIn = socket!!.inputStream
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

    companion object {
        // Debugging
        private const val TAG = "BluetoothChatService"
        private const val D = true
        // Name for the SDP record when creating server socket
        private const val NAME_SECURE = "BluetoothChatSecure"
        private const val NAME_INSECURE = "PhoneGapBluetoothSerialServiceInSecure"
        // Unique UUID for this application
        private val MY_UUID_SECURE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private val MY_UUID_INSECURE =
            UUID.fromString("23F18142-B389-4772-93BD-52BDBB2C03E9")
        // Constants that indicate the current connection state
        const val STATE_NONE = 0 // we're doing nothing
        const val STATE_LISTEN = 1 // now listening for incoming connections
        const val STATE_CONNECTING = 2 // now initiating an outgoing connection
        const val STATE_CONNECTED = 3 // now connected to a remote device
    }

    init {
        mState = STATE_NONE
    }
}