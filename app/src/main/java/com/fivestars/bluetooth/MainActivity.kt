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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fivestars.bluetooth.model.BluetoothMessage
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import java.lang.Thread.sleep
import java.util.*


class MainActivity : AppCompatActivity() {
    // Debugging
    private val TAG = "BluetoothChat"
    private val D = true

    // Intent request codes
    private val REQUEST_CONNECT_DEVICE_SECURE = 1
    private val REQUEST_CONNECT_DEVICE_INSECURE = 2
    private val REQUEST_ENABLE_BT = 3
    private val REQUEST_COARSE_LOCATION = 4

    // Layout Views
    private var mConversationView: ListView? = null
    private var mSendButton: Button? = null

    // Name of the connected device
    private var mConnectedDeviceName: String? = null
    // Array adapter for the conversation thread
    private var mConversationArrayAdapter: ArrayAdapter<String>? = null
    // String buffer for outgoing messages
    private var mOutStringBuffer: StringBuffer? = null
    // Local Bluetooth adapter
    private var mBluetoothAdapter: BluetoothAdapter? = null
    // Member object for the chat services
    private var chatService: MessageUtil? = null
    private var readJob = Job()
    private val moshi: Moshi = Moshi.Builder().build()
    private val adapter: JsonAdapter<BluetoothMessage> = moshi.adapter(BluetoothMessage::class.java)
    private var currentMessage: BluetoothMessage? = null
    private var totalBytesSent: Long = 0
    private var totalBytesReceived: Long = 0
    private var startTime: Long = 0
    private var byteArrayPayload = ByteArray(256)
    private var random = Random()

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

        CoroutineScope(Dispatchers.Default + readJob).launch {
            while (true) {
                val totalTimeInSeconds: Long = ((Date().time - startTime)) / 1000
                try {
                    mConversationArrayAdapter?.clear()
                    withContext(Dispatchers.Main) {
                        text_view_status.text =
                            "Bytes/second sent: ${totalBytesSent / totalTimeInSeconds} : Bytes/second received: ${totalBytesReceived / totalTimeInSeconds}"
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "The divide by zero" + e)
                }
                sleep(1000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (D) Log.e(TAG, "++ ON START ++")
        if (!mBluetoothAdapter!!.isEnabled) {
            mBluetoothAdapter?.enable()
        }
        if (chatService == null) setupChat()
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        if (D) Log.e(TAG, "+ ON RESUME +")
        if (chatService != null) { // Only if the state is STATE_NONE, do we know that we haven't started already
            if (chatService!!.state === MessageUtil.STATE_NONE) { // Start the Bluetooth chat services
                chatService!!.start()
            }
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

    private fun setupChat() {
        Log.d(TAG, "setupChat()")
        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = ArrayAdapter(this, R.layout.message)
        mConversationView = findViewById<View>(R.id.`in`) as ListView
        mConversationView!!.adapter = mConversationArrayAdapter
        // Initialize the send button with a listener that for click events
        mSendButton = findViewById<View>(R.id.button_send) as Button

        mSendButton!!.setOnClickListener {
            startTime = Date().time
            random.nextBytes(byteArrayPayload)
            currentMessage = BluetoothMessage(Date().time, byteArrayPayload)
            sendMessage(adapter.toJson(currentMessage))
        }

        // Initialize the BluetoothChatService to perform bluetooth connections
        chatService = MessageUtil()
        // Initialize the buffer for outgoing messages
        mOutStringBuffer = StringBuffer("")

        CoroutineScope(Dispatchers.IO + readJob).launch {
            chatService?.readChannel?.asFlow()?.collect {
                totalBytesReceived += it.length
                if (currentMessage == null) {
                    if (startTime == 0L) {
                        startTime = Date().time
                    }
                    sendMessage(it)
                } else {
                    val parsedMessage = adapter.fromJson(it)
                    parsedMessage?.run {
                        if (time == currentMessage?.time) {
                            currentMessage = BluetoothMessage(Date().time)
                            sendMessage(adapter.toJson(currentMessage))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    mConversationArrayAdapter!!.add("${chatService?.device?.name}:  $it")
                }

            }
        }

        CoroutineScope(Dispatchers.Main + readJob).launch {
            chatService?.writeChannel?.asFlow()?.collect {
                mConversationArrayAdapter!!.add("Me:  $it")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the Bluetooth chat services
        if (chatService != null) chatService!!.stop()
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
        if (chatService?.state != MessageUtil.STATE_CONNECTED) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        if (message.isNotEmpty()) {
            val send = message.toByteArray()
            totalBytesSent += send.size
            chatService!!.write(send)
        }

    }

    private fun setStatus(resId: Int) {
        val actionBar = actionBar
        actionBar?.setSubtitle(resId)
    }

    private fun setStatus(subTitle: CharSequence) {
        val actionBar = actionBar
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
            REQUEST_ENABLE_BT ->  // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) { // Bluetooth is now enabled, so set up a chat session
                    setupChat()
                } else { // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled")
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show()
                    finish()
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
        chatService!!.connect(device, secure)
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
