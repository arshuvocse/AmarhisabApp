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
class BluetoothPrinterManager(private val context: Context) {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val executor = Executors.newSingleThreadExecutor()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amarhisab_printer_prefs", Context.MODE_PRIVATE)

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
            editor.putString(KEY_PRINTER_NAME, device.name)
        } catch (_: SecurityException) {}
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
            true to null
        } catch (e: IOException) {
            Log.e(TAG, "Connect failed", e)
            false to e.message
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            false to "Bluetooth permission নেই"
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
        }
    }

    private fun ensureConnectedToSaved(): Boolean {
        if (isConnected()) return true
        try {
            val address = savedPrinterAddress() ?: run {
                showToast("Kono printer save kora nei, Printer settings theke printer save korun")
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
            val device = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: run {
                showToast("Save kora printer ta khuje pawa jacche na, nuton printer select korun")
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
            val (connected, errorMsg) = connectBlocking(device)
            if (!connected) {
                showToast("Printer e connect kora jay ni${if (errorMsg != null) ": $errorMsg" else ""}")
            }
            return connected
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            showToast("Bluetooth permission dorkar, App settings theke permission din")
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

    private fun showToast(message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
                    showToast("প্রিন্ট বাতিল: কোনো লেখা পাওয়া যায়নি (blank content)")
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.bitmapToRaster(scaled))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
            } catch (e: Exception) {
                Log.e(TAG, "printPlainText failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}")
                printErrorToPaper("printPlainText", e)
            }
        }
    }

    /** Builds a formatted receipt from JSON and sends it through the bit-image engine. */
    fun printReceipt(receipt: JSONObject) {
        executor.execute {
            try {
                if (!ensureConnectedToSaved()) return@execute
                showToast("প্রিন্ট হচ্ছে...")

                val bitmap = BitmapPrintRenderer.renderReceiptToBitmap(receipt)
                val scaled = BitmapPrintRenderer.scaleToPrinterWidth(bitmap)
                debugSaveBitmap(scaled, "printReceipt")
                if (!BitmapPrintRenderer.hasVisibleContent(scaled)) {
                    Log.w(TAG, "printReceipt aborted: rendered bitmap has no visible content")
                    showToast("প্রিন্ট বাতিল: কোনো কনটেন্ট পাওয়া যায়নি (blank content)")
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.bitmapToRaster(scaled))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
            } catch (e: Exception) {
                Log.e(TAG, "printReceipt failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}")
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
                    showToast("প্রিন্ট বাতিল: স্ক্রিনশট ফাঁকা এসেছে (blank capture)")
                    return@execute
                }
                writeSafely(EscPosEncoder.init())
                writeSafely(EscPosEncoder.bitmapToRaster(scaled))
                writeSafely(EscPosEncoder.newLine(3))
                writeSafely(EscPosEncoder.cutPaper())
            } catch (e: Exception) {
                Log.e(TAG, "printBitmapBase64 failed", e)
                showToast("প্রিন্ট ব্যর্থ হয়েছে: ${e.message}")
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
    }
}
