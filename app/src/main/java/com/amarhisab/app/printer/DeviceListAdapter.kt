package com.amarhisab.app.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.amarhisab.app.R

class DeviceListAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission") // permission is checked before this screen is shown
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "Unknown device"
        holder.address.text = device.address
        holder.itemView.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = devices.size
}
