package com.amarhisab.app.printer

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amarhisab.app.R

/**
 * Lets the user pick which paired Bluetooth thermal printer the app should use.
 * Selection is persisted via BluetoothPrinterManager and reused for future print jobs
 * triggered from the web page through the JS bridge.
 */
class PrinterSettingsActivity : AppCompatActivity() {

    private lateinit var printerManager: BluetoothPrinterManager
    private lateinit var statusText: TextView
    private lateinit var adapter: DeviceListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_settings)

        printerManager = BluetoothPrinterManager.getInstance(this)
        statusText = findViewById(R.id.statusText)
        findViewById<android.view.View>(R.id.btnBack)?.setOnClickListener { finish() }
        updateStatus()

        val deviceList = findViewById<RecyclerView>(R.id.deviceList)
        deviceList.layoutManager = LinearLayoutManager(this)
        
        adapter = DeviceListAdapter(
            devices = printerManager.pairedDevices(),
            selectedAddress = printerManager.savedPrinterAddress()
        ) { device ->
            printerManager.saveDefaultPrinter(device)
            adapter.setSelectedAddress(device.address)
            updateStatus()

            val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            com.amarhisab.app.utils.CustomToast.show(this, "$deviceName কানেক্ট করা হচ্ছে...")
            printerManager.connect(device) { success, error ->
                runOnUiThread {
                    if (success) {
                        com.amarhisab.app.utils.CustomToast.show(this, "সংযুক্ত হয়েছে: $deviceName", isSuccess = true)
                    } else {
                        com.amarhisab.app.utils.CustomToast.show(this, "কানেকশন ব্যর্থ: ${error ?: "অজানা সমস্যা"}", isError = true)
                    }
                    updateStatus()
                }
            }
        }

        deviceList.adapter = adapter
    }

    private fun updateStatus() {
        val savedName = printerManager.savedPrinterName()
        val savedAddress = printerManager.savedPrinterAddress()
        val isConnected = printerManager.isConnected()
        
        statusText.text = if (savedAddress != null) {
            val nameStr = savedName ?: savedAddress
            val stateStr = if (isConnected) "সংযুক্ত (Green)" else "ডিসকানেক্টেড"
            "সেভ করা প্রিন্টার: $nameStr\nস্ট্যাটাস: $stateStr"
        } else {
            getString(R.string.no_printer_connected)
        }
    }
}
