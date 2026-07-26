package com.amarhisab.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.amarhisab.app.R

object BluetoothPermissionHelper {

    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    fun hasBluetoothPermissions(context: Context): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Displays a trust-building rationale dialog explaining WHY Bluetooth permission
     * and enablement are required for receipt printing before triggering system popups.
     */
    fun showRationaleDialog(
        context: Context,
        onAllow: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_bluetooth_rationale, null)
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnAllow = dialogView.findViewById<Button>(R.id.btnAllowBluetooth)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelBluetooth)

        btnAllow.setOnClickListener {
            dialog.dismiss()
            onAllow()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        dialog.setOnCancelListener {
            onCancel?.invoke()
        }

        dialog.show()
    }
}
