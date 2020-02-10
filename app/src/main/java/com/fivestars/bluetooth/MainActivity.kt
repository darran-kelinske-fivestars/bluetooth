package com.fivestars.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.widget.TextView.OnEditorActionListener
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fivestars.bluetooth.model.BluetoothMessage
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
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
    val adapter: JsonAdapter<BluetoothMessage> = moshi.adapter(BluetoothMessage::class.java)
    var currentMessage: BluetoothMessage? = null
    var ack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (D) Log.e(TAG, "+++ ON CREATE +++")
        // Set up the window layout
        setContentView(R.layout.main)
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show()
            finish()
            return
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
        // Performing this check in onResume() covers the case in which BT was
// not enabled during onStart(), so we were paused to enable it...
// onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
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
                this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION),
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
            // Send a message using content of the edit text widget
            val view = findViewById<View>(R.id.edit_text_out) as TextView

            currentMessage = BluetoothMessage(Date().time)
            sendMessage(adapter.toJson(currentMessage))
        }

        // Initialize the BluetoothChatService to perform bluetooth connections
        chatService = MessageUtil()
        // Initialize the buffer for outgoing messages
        mOutStringBuffer = StringBuffer("")

        CoroutineScope(Dispatchers.Main + readJob).launch {
            chatService?.readChannel?.asFlow()?.collect {
                if (currentMessage == null) {
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
                mConversationArrayAdapter!!.add("${chatService?.device?.name}:  $it")

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

        // Check that there's actually something to send
        if (message.length > 0) { // Get the message bytes and tell the BluetoothChatService to write
            val send = message.toByteArray()
            chatService!!.write(send)
            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer!!.setLength(0)
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
                connectDevice(Intent().putExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS, "AC:37:43:D4:17:E5"), false)
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
