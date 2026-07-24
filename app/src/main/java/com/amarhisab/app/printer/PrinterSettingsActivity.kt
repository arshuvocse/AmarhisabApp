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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_settings)

        printerManager = BluetoothPrinterManager(this)
        statusText = findViewById(R.id.statusText)
        updateStatus()

        val deviceList = findViewById<RecyclerView>(R.id.deviceList)
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = DeviceListAdapter(printerManager.pairedDevices()) { device ->
            printerManager.connect(device) { success, error ->
                runOnUiThread {
                    if (success) {
                        printerManager.saveDefaultPrinter(device)
                        Toast.makeText(this, "Connected: ${device.name}", Toast.LENGTH_SHORT).show()
                        updateStatus()
                    } else {
                        Toast.makeText(this, "Connect fail hoyeche: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateStatus() {
        val saved = printerManager.savedPrinterAddress()
        statusText.text = if (saved != null) "Default printer: $saved" else getString(R.string.no_printer_connected)
    }
}
