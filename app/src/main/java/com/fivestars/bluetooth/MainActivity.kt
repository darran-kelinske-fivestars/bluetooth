package com.fivestars.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fivestars.bluetooth.model.MessageType
import com.fivestars.bluetooth.model.TestMessage
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import java.lang.Thread.sleep
import java.util.*
import java.util.concurrent.atomic.AtomicLong


class MainActivity : AppCompatActivity() {
    // Debugging
    private val TAG = "BluetoothChat"
    private val D = true

    // Intent request codes
    private val REQUEST_CONNECT_DEVICE_SECURE = 1
    private val REQUEST_CONNECT_DEVICE_INSECURE = 2
    private val REQUEST_COARSE_LOCATION = 4

    // Name of the connected device
    private var mConnectedDeviceName: String? = null
    // Local Bluetooth adapter
    private var mBluetoothAdapter: BluetoothAdapter? = null
    // Member object for the chat services
    private val messageUtil: MessageUtil = MessageUtil()
    private var readJob = Job()
    private val moshi: Moshi = Moshi.Builder()
        .build()
    private val adapter: JsonAdapter<TestMessage> = moshi.adapter(TestMessage::class.java)
    private var currentMessage: TestMessage? = null
    private var totalBytesSent: AtomicLong = AtomicLong(0)
    private var totalBytesReceived: AtomicLong = AtomicLong(0)
    private var startTime: AtomicLong = AtomicLong(0)
    private var byteArrayPayload = ByteArray(256)
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (D) Log.e(TAG, "+++ ON CREATE +++")
        setContentView(R.layout.activity_main)
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setStatus("yowza")

        CoroutineScope(Dispatchers.Default + readJob).launch {
            while (true) {
                val totalTimeInSeconds: Long = ((Date().time - startTime.get())) / 1000
                try {
                    withContext(Dispatchers.Main) {

                        var totalBytesSentSecond: Long = 0

                        if (totalBytesSent.get() != 0L) {
                            totalBytesSentSecond = (totalBytesSent.get() / totalTimeInSeconds)
                        }

                        var totalBytesReceivedSecond: Long = 0

                        if (totalBytesReceived.get() != 0L) {
                            totalBytesReceivedSecond = (totalBytesReceived.get() / totalTimeInSeconds)
                        }
                        text_view_status.text =
                            "Total bytes sent: $totalBytesSent \nBytes/second sent: $totalBytesSentSecond\nTotal bytes received: $totalBytesReceived\nBytes/second received: $totalBytesReceivedSecond"
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "The divide by zero" + e)
                }
                sleep(2000)
            }
        }

        if (!mBluetoothAdapter?.isEnabled!!) {
            mBluetoothAdapter?.enable()
        }

        CoroutineScope(Dispatchers.Main + readJob).launch {
            messageUtil.statusChannel?.asFlow()?.collect {
                setStatus(it)
            }
        }

        button_bidirectional.setOnClickListener {
            setPayLoadSizeAndStartTime()
            randomizeAndSendMessage(MessageType.BIDIRECTIONAL)

        }

        button_unidirectional.setOnClickListener {
            setPayLoadSizeAndStartTime()

            CoroutineScope(Dispatchers.IO + readJob).launch {
                while (true) {
                    randomizeAndSendMessage(MessageType.UNIDIRECTIONAL)
                }
            }
        }

        CoroutineScope(newFixedThreadPoolContext(1, "uno") + readJob).launch {
            messageUtil.readChannel.asFlow().collect {
                val parsedMessage: TestMessage?

                try {
                    parsedMessage = adapter.fromJson(it)
                } catch (e: java.lang.Exception) {
                    Log.e(TAG, e.toString() + "data was: it")
                    return@collect
                }
                totalBytesReceived.getAndAdd(it.toByteArray().size.toLong())
                if (startTime.get() == 0L) {
                    startTime = AtomicLong(Date().time)
                }
                // Send the response message if we are the receiver app
                if (currentMessage == null && parsedMessage?.messageType == MessageType.BIDIRECTIONAL) {
                    sendMessage(it)
                } else {
                    parsedMessage?.run {
                        // If the time on the incoming message matches our last sent message, then we received the "ACK"
                        // Send another message to keep the data flow going
                        if (time == currentMessage?.time && messageType == MessageType.BIDIRECTIONAL) {
                            currentMessage = TestMessage(
                                Date().time, MessageType.BIDIRECTIONAL, String(
                                    byteArrayPayload
                                )
                            )
                            sendMessage(adapter.toJson(currentMessage))
                        }
                    }
                }
            }
        }
    }

    private fun randomizeAndSendMessage(messageType: MessageType) {
        random.nextBytes(byteArrayPayload)
        currentMessage = TestMessage(
            Date().time,
            messageType,
            String(byteArrayPayload)
        )
        sendMessage(adapter.toJson(currentMessage))
    }

    private fun setPayLoadSizeAndStartTime() {
        val payloadSize = edit_text_payload_size.text.toString()
        edit_text_payload_size.isEnabled = false
        byteArrayPayload = ByteArray(payloadSize.toInt())
        startTime = AtomicLong(Date().time)
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        if (D) Log.e(TAG, "+ ON RESUME +")
        if (messageUtil.state == MessageUtil.STATE_NONE) {
            messageUtil.start()
        }
        checkLocationPermission()
    }

    fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_COARSE_LOCATION
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the Bluetooth chat services
        messageUtil.stop()
        if (D) Log.e(TAG, "--- ON DESTROY ---")
        readJob.cancel()
    }

    private fun ensureDiscoverable() {
        if (D) Log.d(TAG, "ensure discoverable")
        if (mBluetoothAdapter!!.scanMode !=
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        ) {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivity(discoverableIntent)
        }
    }

    /**
     * Sends a message.
     * @param message  A string of text to send.
     */
    private fun sendMessage(message: String) { // Check that we're actually connected before trying anything
        if (messageUtil.state != MessageUtil.STATE_CONNECTED) {
            return
        }

        if (message.isNotEmpty()) {
            val send = (message + "\n").toByteArray()
            totalBytesSent.getAndAdd(send.size.toLong())
            messageUtil.write(send)
        }

    }

    private fun setStatus(resId: Int) {
        val actionBar = actionBar
        actionBar?.setSubtitle(resId)
    }

    private fun setStatus(subTitle: CharSequence) {
        val actionBar = supportActionBar
        actionBar?.subtitle = subTitle
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (D) Log.d(TAG, "onActivityResult $resultCode")
        when (requestCode) {
            REQUEST_CONNECT_DEVICE_SECURE ->  // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data!!, true)
                }
            REQUEST_CONNECT_DEVICE_INSECURE ->  // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data!!, false)
                }
        }
    }

    private fun connectDevice(
        data: Intent,
        secure: Boolean
    ) { // Get the device MAC address
        val address = data.extras
            ?.getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS)
        // Get the BluetoothDevice object
        val device = mBluetoothAdapter!!.getRemoteDevice(address)
        // Attempt to connect to the device
        messageUtil!!.connect(device, secure)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.option_menu, menu)
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_COARSE_LOCATION -> if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Fine location permission is not granted!")
                finish()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var serverIntent: Intent? = null
        when (item.itemId) {
            R.id.insecure_connect_scan -> {
                // Launch the DeviceListActivity to see devices and do scan
                serverIntent = Intent(this, DeviceListActivity::class.java)
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE)
                return true
            }
            R.id.discoverable -> {
                // Ensure this device is discoverable by others
                ensureDiscoverable()
                return true
            }
        }
        return false
    }
}
