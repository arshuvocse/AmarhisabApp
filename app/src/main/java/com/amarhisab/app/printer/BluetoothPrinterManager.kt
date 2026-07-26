package com.amarhisab.app.printer

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Central "Native Kotlin API" component responsible for:
 *  - Bluetooth device discovery / connection (paired SPP devices)
 *  - Persisting the chosen default printer
 *  - Driving the ESC/POS engine + bitmap renderer to send print jobs
 *
 * Uses the standard Serial Port Profile UUID, which is what almost all
 * generic Bluetooth thermal receipt printers expose.
 */
class BluetoothPrinterManager private constructor(private val context: Context) {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val executor = Executors.newSingleThreadExecutor()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    var onConnectionStateChanged: (() -> Unit)? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amarhisab_printer_prefs", Context.MODE_PRIVATE)

    private val btReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val savedAddr = savedPrinterAddress()
                if (device != null && savedAddr != null && device.address.equals(savedAddr, ignoreCase = true)) {
                    Log.d(TAG, "Saved Bluetooth printer ACL disconnected: ${device.address}")
                    disconnectInternal()
                }
            } else if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.d(TAG, "Bluetooth turned off")
                    disconnectInternal()
                }
            }
        }
    }

    init {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(btReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BT broadcast receiver", e)
        }
    }

    private fun notifyStateChanged() {
        android.os.Handler(context.mainLooper).post {
            onConnectionStateChanged?.invoke()
        }
    }

    fun pairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun savedPrinterAddress(): String? = prefs.getString(KEY_PRINTER_ADDRESS, null)

    fun savedPrinterName(): String? {
        val address = savedPrinterAddress() ?: return null
        return try {
            val device = adapter?.bondedDevices?.firstOrNull { it.address == address }
            device?.name ?: prefs.getString(KEY_PRINTER_NAME, null) ?: address
        } catch (e: SecurityException) {
            prefs.getString(KEY_PRINTER_NAME, null) ?: address
        }
    }

    fun saveDefaultPrinter(device: BluetoothDevice) {
        val editor = prefs.edit().putString(KEY_PRINTER_ADDRESS, device.address)
        try {
            val deviceName = device.name ?: device.address
            editor.putString(KEY_PRINTER_NAME, deviceName)
        } catch (_: SecurityException) {
            editor.putString(KEY_PRINTER_NAME, device.address)
        }
        editor.apply()
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasSavedPrinter(): Boolean = savedPrinterAddress() != null

    /**
     * Does the actual RFCOMM connect on whichever thread calls it — blocks until the
     * connection succeeds/fails (this printer's classic-BT handshake can take several
     * seconds). Must NOT be dispatched through [executor] by callers that are themselves
     * already running on [executor], since it is single-threaded and would deadlock
     * behind whoever is waiting for the result (see [ensureConnectedToSaved]).
     */
    private fun connectBlocking(device: BluetoothDevice): Pair<Boolean, String?> {
        return try {
            disconnectInternal()
            val newSocket = device.createRfcommSocketToServiceRecord(sppUuid)
            adapter?.cancelDiscovery()
            newSocket.connect()
            socket = newSocket
            outputStream = newSocket.outputStream
            notifyStateChanged()
            true to null
        } catch (e: IOException) {
            Log.e(TAG, "Connect failed", e)
            notifyStateChanged()
            false to e.message
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            notifyStateChanged()
            false to "Bluetooth permission নেই"
        }
    }

    /** Connects to the saved printer in SharedPreferences if available. */
    fun connectToSaved(onResult: ((Boolean, String?) -> Unit)? = null) {
        if (isConnected()) {
            onResult?.invoke(true, null)
            return
        }
        val address = savedPrinterAddress()
        if (address == null) {
            onResult?.invoke(false, "কোনো প্রিন্টার সেভ করা নেই")
            return
        }
        val device = try {
            adapter?.bondedDevices?.firstOrNull { it.address == address }
        } catch (e: SecurityException) {
            null
        }
        if (device == null) {
            onResult?.invoke(false, "সেভ করা প্রিন্টারটি পাওয়া যায়নি")
            return
        }
        connect(device) { success, error ->
            onResult?.invoke(success, error)
        }
    }

    /** Connects on a background thread; invokes [onResult] on completion. For UI callers only. */
    fun connect(device: BluetoothDevice, onResult: (success: Boolean, error: String?) -> Unit) {
        executor.execute {
            val (success, error) = connectBlocking(device)
            onResult(success, error)
        }
    }

    fun disconnect() = executor.execute { disconnectInternal() }

    private fun disconnectInternal() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) {
        } finally {
            outputStream = null
            socket = null
            notifyStateChanged()
        }
    }

    private fun ensureConnectedToSaved(): Boolean {
        if (isConnected()) return true
        try {
            val address = savedPrinterAddress() ?: run {
                showToast("কোনো ব্লুটুথ প্রিন্টার সেভ করা নেই। আগে প্রিন্টার সেটিংস থেকে প্রিন্টার সিলেক্ট করুন।", isError = true)
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
            val device = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: run {
                showToast("সেভ করা প্রিন্টারটি ফোনে পাওয়া যাচ্ছে না। ব্লুটুথ সেটিংস থেকে আবার সিলেক্ট করুন।", isError = true)
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
            showToast("প্রিন্টারে কানেক্ট করা হচ্ছে...")
            val (connected, _) = connectBlocking(device)
            if (!connected) {
                showToast("প্রিন্টারে কানেক্ট করা যায়নি! প্রিন্টারটি চালু ও কাছে আছে কিনা পরীক্ষা করুন।", isError = true)
            }
            return connected
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            showToast("ব্লুটুথ পারমিশন দেওয়া নেই। অ্যাপ সেটিংস থেকে পারমিশন দিন।", isError = true)
            return false
        }
    }

    /** Dumps the exact bitmap about to be sent to the printer to a pullable file, for debugging what actually printed. */
    private fun debugSaveBitmap(bitmap: Bitmap, tag: String) {
        try {
            val timestamp = System.currentTimeMillis()
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: context.getExternalFilesDir(null)
                ?: context.cacheDir

            dir.mkdirs()
            val file = File(dir, "print_debug_${tag}_${timestamp}.png")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

            val latestFile = File(dir, "latest_print_debug.png")
            FileOutputStream(latestFile).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }

            Log.d(TAG, "==========================================================")
            Log.d(TAG, "DEBUG PRINT BITMAP SAVED (${bitmap.width}x${bitmap.height}px):")
            Log.d(TAG, "File Path: ${file.absolutePath}")
            Log.d(TAG, "Latest File: ${latestFile.absolutePath}")
            Log.d(TAG, "==========================================================")
        } catch (e: Exception) {
            Log.e(TAG, "debugSaveBitmap failed", e)
        }
    }

    private fun showToast(message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        com.amarhisab.app.utils.CustomToast.show(context, message, isError = isError, isSuccess = isSuccess)
    }

    /** Sends a plain block of text through the ESC/POS bit-image engine. Supports Bangla/Unicode 100%. */
    fun printPlainText(text: String) {
        executor.execute {
            try {
                if (!ensureConnectedToSaved()) return@execute
                showToast("প্রিন্ট হচ্ছে...")

                val bitmap = BitmapPrintRenderer.renderTextToBitmap(text)
                val scaled = BitmapPrintRenderer.scaleToPrinterWidth(bitmap)
                debugSaveBitmap(scaled, "printPlainText")
                if (!BitmapPrintRenderer.hasVisibleContent(scaled)) {
                    Log.w(TAG, "printPlainText aborted: rendered bitmap has no visible content")
                    showToast("প্রিন্ট বাতিল: কোনো লেখা পাওয়া যায়নি", isError = true)
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.bitmapToRaster(scaled))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
                showToast("প্রিন্ট সফল হয়েছে!", isSuccess = true)
            } catch (e: Exception) {
                Log.e(TAG, "printPlainText failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}", isError = true)
                printErrorToPaper("printPlainText", e)
            }
        }
    }

    /** Builds a formatted receipt from JSON using native 384px ReceiptBitmapRenderer and dithered ESC/POS encoder. */
    fun printReceipt(receipt: JSONObject) {
        executor.execute {
            try {
                if (!ensureConnectedToSaved()) return@execute
                showToast("প্রিন্ট হচ্ছে...")

                val bitmap = ReceiptBitmapRenderer.renderReceipt(receipt, context)
                debugSaveBitmap(bitmap, "printReceipt")
                if (!BitmapPrintRenderer.hasVisibleContent(bitmap)) {
                    Log.w(TAG, "printReceipt aborted: rendered bitmap has no visible content")
                    showToast("প্রিন্ট বাতিল: কোনো কনটেন্ট পাওয়া যায়নি", isError = true)
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.setPrintDensity(heatingDots = 7, heatingTime = 80, heatingInterval = 2))
                writeSafely(EscPosEncoder.bitmapToRaster(bitmap, dither = true))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
                showToast("প্রিন্ট সফল হয়েছে!", isSuccess = true)
            } catch (e: Exception) {
                Log.e(TAG, "printReceipt failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}", isError = true)
                printErrorToPaper("printReceipt", e)
            }
        }
    }

    /** Renders a base64 image via the bitmap renderer and prints it using the ESC * bit-image command. */
    fun printBitmapBase64(base64Image: String) {
        executor.execute {
            try {
                if (!ensureConnectedToSaved()) return@execute
                showToast("প্রিন্ট হচ্ছে...")
                val bitmap = BitmapPrintRenderer.decodeBase64(base64Image) ?: return@execute
                val scaled = BitmapPrintRenderer.scaleToPrinterWidth(bitmap)
                debugSaveBitmap(scaled, "printBitmapBase64")
                if (!BitmapPrintRenderer.hasVisibleContent(scaled)) {
                    Log.w(TAG, "printBitmapBase64 aborted: captured bitmap has no visible content")
                    showToast("প্রিন্ট বাতিল: স্ক্রিনশট ফাঁকা এসেছে", isError = true)
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.bitmapToRaster(scaled))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
                showToast("প্রিন্ট সফল হয়েছে!", isSuccess = true)
            } catch (e: Exception) {
                Log.e(TAG, "printBitmapBase64 failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}", isError = true)
                printErrorToPaper("printBitmapBase64", e)
            }
        }
    }

    /**
     * Prints the failure (function name, exception type/message, and a couple of stack
     * frames) directly on the receipt paper via the plain-text path, since it doesn't
     * depend on the more complex table renderer that may itself be the thing crashing.
     * Runs on the same executor thread as the caller, so no extra connect/dispatch needed.
     */
    private fun printErrorToPaper(source: String, error: Exception) {
        try {
            val trace = error.stackTrace.take(3).joinToString("\n") { "  at $it" }
            val message = buildString {
                append("!! PRINT ERROR !!\n")
                append("fn: $source\n")
                append("${error.javaClass.simpleName}: ${error.message}\n")
                if (trace.isNotBlank()) append(trace)
            }
            val bitmap = BitmapPrintRenderer.renderTextToBitmap(message)
            val scaled = BitmapPrintRenderer.scaleToPrinterWidth(bitmap)
            writeSafely(EscPosEncoder.init())
            writeSafely(EscPosEncoder.bitmapToRaster(scaled))
            writeSafely(EscPosEncoder.newLine(3))
            writeSafely(EscPosEncoder.cutPaper())
        } catch (e: Exception) {
            Log.e(TAG, "printErrorToPaper itself failed", e)
        }
    }

    private fun writeSafely(bytes: ByteArray) {
        try {
            val chunkSize = 512
            var offset = 0
            while (offset < bytes.size) {
                val length = Math.min(chunkSize, bytes.size - offset)
                outputStream?.write(bytes, offset, length)
                outputStream?.flush()
                offset += length
                if (offset < bytes.size) {
                    Thread.sleep(5)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            disconnectInternal()
        }
    }

    companion object {
        private const val TAG = "BluetoothPrinterManager"
        private const val KEY_PRINTER_ADDRESS = "default_printer_address"
        private const val KEY_PRINTER_NAME = "default_printer_name"

        @Volatile
        private var instance: BluetoothPrinterManager? = null

        fun getInstance(context: Context): BluetoothPrinterManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothPrinterManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
