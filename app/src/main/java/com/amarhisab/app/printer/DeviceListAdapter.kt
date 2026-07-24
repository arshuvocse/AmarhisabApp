package com.amarhisab.app.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.amarhisab.app.R

class DeviceListAdapter(
    private val devices: List<BluetoothDevice>,
    private var selectedAddress: String?,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
        val icon: ImageView = view.findViewById(R.id.deviceIcon)
        val badge: TextView = view.findViewById(R.id.selectBadge)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectedAddress(address: String?) {
        selectedAddress = address
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return DeviceViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        val deviceNameStr = device.name ?: "Unknown device"
        holder.name.text = deviceNameStr
        holder.address.text = device.address

        val isSelected = selectedAddress != null && device.address.equals(selectedAddress, ignoreCase = true)

        if (isSelected) {
            holder.itemView.setBackgroundResource(R.drawable.bg_glossy_item_selected)
            holder.badge.text = "সিলেক্টেড ✓"
            holder.badge.setTextColor(Color.parseColor("#10B981"))
            holder.badge.setBackgroundResource(R.drawable.bg_glossy_badge_green)
            holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            holder.name.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_glossy_item)
            holder.badge.text = "সিলেক্ট"
            holder.badge.setTextColor(Color.parseColor("#94A3B8"))
            holder.badge.setBackgroundResource(R.drawable.bg_glossy_badge_neutral)
            holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            holder.name.setTextColor(Color.parseColor("#F1F5F9"))
        }

        holder.itemView.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = devices.size
}
