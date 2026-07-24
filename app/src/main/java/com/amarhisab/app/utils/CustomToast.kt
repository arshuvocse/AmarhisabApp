package com.amarhisab.app.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.amarhisab.app.R

object CustomToast {

    fun show(context: Context, message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        val mainHandler = android.os.Handler(context.mainLooper)
        mainHandler.post {
            try {
                val inflater = LayoutInflater.from(context)
                val layout = inflater.inflate(R.layout.layout_custom_toast, null)

                val toastIcon = layout.findViewById<ImageView>(R.id.toastIcon)
                val toastMessage = layout.findViewById<TextView>(R.id.toastMessage)

                toastMessage.text = message

                when {
                    isError -> {
                        toastIcon.setImageResource(R.drawable.ic_error_outline)
                        toastIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                    }
                    isSuccess -> {
                        toastIcon.setImageResource(R.drawable.ic_check_circle)
                        toastIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    }
                    else -> {
                        toastIcon.setImageResource(R.drawable.ic_bluetooth_connected)
                        toastIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                    }
                }

                @Suppress("DEPRECATION")
                val toast = Toast(context.applicationContext).apply {
                    duration = Toast.LENGTH_SHORT
                    setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
                    view = layout
                }
                toast.show()
            } catch (e: Exception) {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
