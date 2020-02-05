package com.fivestars.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch


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
    private var mOutEditText: EditText? = null
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
        // If BT is not on, request that it be enabled.
// setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter!!.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
            // Otherwise, setup the chat session
        } else {
            if (chatService == null) setupChat()
        }
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
        // Initialize the compose field with a listener for the return key
        mOutEditText = findViewById<View>(R.id.edit_text_out) as EditText
        mOutEditText!!.setOnEditorActionListener(mWriteListener)
        // Initialize the send button with a listener that for click events
        mSendButton = findViewById<View>(R.id.button_send) as Button
        mSendButton!!.setOnClickListener {
            // Send a message using content of the edit text widget
            val view = findViewById<View>(R.id.edit_text_out) as TextView
            val message = view.text.toString()
            sendMessage(message)
        }
        // Initialize the BluetoothChatService to perform bluetooth connections
        chatService = MessageUtil()
        // Initialize the buffer for outgoing messages
        mOutStringBuffer = StringBuffer("")

        CoroutineScope(Dispatchers.Main + readJob).launch {
            chatService?.readChannel?.asFlow()?.collect {
                mConversationArrayAdapter!!.add("${chatService?.device?.name}:  $it")
            }
        }

        CoroutineScope(Dispatchers.Main + readJob).launch {
            chatService?.writeChannel?.asFlow()?.collect {
                mConversationArrayAdapter!!.add("Me:  $it")
            }
        }
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        if (D) Log.e(TAG, "- ON PAUSE -")
    }

    override fun onStop() {
        super.onStop()
        if (D) Log.e(TAG, "-- ON STOP --")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the Bluetooth chat services
        if (chatService != null) chatService!!.stop()
        if (D) Log.e(TAG, "--- ON DESTROY ---")
        readJob?.cancel()
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
        // Check that there's actually something to send
        if (message.length > 0) { // Get the message bytes and tell the BluetoothChatService to write
            val send = message.toByteArray()
            chatService!!.write(send)
            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer!!.setLength(0)
            mOutEditText!!.setText(mOutStringBuffer)
        }
    }

    // The action listener for the EditText widget, to listen for the return key
    private val mWriteListener =
        OnEditorActionListener { view, actionId, event ->
            // If the action is a key-up event on the return key, send the message
            if (actionId == EditorInfo.IME_NULL && event.action == KeyEvent.ACTION_UP) {
                val message = view.text.toString()
                sendMessage(message)
            }
            if (D) Log.i(TAG, "END onEditorAction")
            true
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
                    connectDevice(data!!)
                }
            REQUEST_CONNECT_DEVICE_INSECURE ->  // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data!!)
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
        data: Intent
    ) { // Get the device MAC address
        val address = data.extras
            ?.getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS)
        // Get the BluetoothDevice object
        val device = mBluetoothAdapter!!.getRemoteDevice(address)
        // Attempt to connect to the device
        chatService!!.connect(device)
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
            R.id.secure_connect_scan -> {
                // Launch the DeviceListActivity to see devices and do scan
                serverIntent = Intent(this, DeviceListActivity::class.java)
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE)
                return true
            }
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

    companion object {
        // Message types sent from the BluetoothChatService Handler
        public const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5
        // Key names received from the BluetoothChatService Handler
        const val DEVICE_NAME = "device_name"
    }

}
