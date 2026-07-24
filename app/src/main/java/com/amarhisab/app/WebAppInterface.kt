package com.amarhisab.app

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.amarhisab.app.printer.BluetoothPrinterManager
import com.amarhisab.app.printer.PrinterSettingsActivity
import org.json.JSONObject

/**
 * The JavaScript <-> Kotlin bridge.
 * From the web page (amarhisab.com), call these as:
 *
 *   window.AndroidBridge.printReceipt(jsonString)
 *   window.AndroidBridge.isPrinterConnected()
 *   window.AndroidBridge.openPrinterSettings()
 *
 * Expected receipt JSON shape:
 * {
 *   "shopName": "Amarhisab Store",
 *   "items": [{"name":"Item A","qty":2,"price":150.0}],
 *   "total": 300.0,
 *   "footer": "Thank you!"
 * }
 */
class WebAppInterface(
    private val context: Context,
    private val printerManager: BluetoothPrinterManager,
    private val enableRequester: BluetoothEnableRequester
) {

    /** Implemented by MainActivity — only an Activity can launch the system enable-Bluetooth dialog. */
    interface BluetoothEnableRequester {
        fun requestEnableBluetooth(onResult: (granted: Boolean) -> Unit)
    }

    @JavascriptInterface
    fun isPrinterConnected(): Boolean = printerManager.isConnected()

    @JavascriptInterface
    fun hasSavedPrinter(): Boolean = printerManager.hasSavedPrinter()

    @JavascriptInterface
    fun getSavedPrinterName(): String = printerManager.savedPrinterName() ?: ""

    @JavascriptInterface
    fun getSavedPrinterAddress(): String = printerManager.savedPrinterAddress() ?: ""

    @JavascriptInterface
    fun disconnectPrinter() {
        Log.d(TAG, "disconnectPrinter() called from JS")
        printerManager.disconnect()
        showToast("Printer disconnect kora hoyeche")
        (context as? MainActivity)?.let { activity ->
            activity.runOnUiThread { activity.updateFabState() }
        }
    }

    @JavascriptInterface
    fun openPrinterSettings() {
        context.startActivity(Intent(context, PrinterSettingsActivity::class.java))
    }

    /**
     * Prints a structured receipt via the ESC/POS engine.
     * If Bluetooth is off, this first shows the system "turn on Bluetooth" prompt,
     * then automatically connects to the saved default printer and prints.
     */
    @JavascriptInterface
    fun printReceipt(receiptJson: String) {
        Log.d(TAG, "printReceipt() called from JS, payload=$receiptJson")
        runWithBluetoothReady {
            try {
                val json = JSONObject(receiptJson)
                if (!printerManager.hasSavedPrinter()) {
                    showToast("কোনো ব্লুটুথ প্রিন্টার সেভ করা নেই। আগে সেটিংস থেকে একটি প্রিন্টার সিলেক্ট করুন।")
                    val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return@runWithBluetoothReady
                }
                printerManager.printReceipt(json)
            } catch (e: Exception) {
                Log.e(TAG, "printReceipt failed", e)
                showToast("প্রিন্ট করতে সমস্যা হয়েছে: ${e.message}")
            }
        }
    }

    /** Prints a plain block of text as-is (simple use case). */
    @JavascriptInterface
    fun printText(text: String) {
        Log.d(TAG, "printText() called from JS")
        runWithBluetoothReady {
            if (!printerManager.hasSavedPrinter()) {
                showToast("কোনো ব্লুটুথ প্রিন্টার সেভ করা নেই। আগে সেটিংস থেকে একটি প্রিন্টার সিলেক্ট করুন।")
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return@runWithBluetoothReady
            }
            printerManager.printPlainText(text)
        }
    }

    /** Prints a base64-encoded PNG/JPEG as a bitmap (e.g. logo or QR code receipts). */
    @JavascriptInterface
    fun printBitmapBase64(base64Image: String) {
        Log.d(TAG, "printBitmapBase64() called from JS")
        runWithBluetoothReady {
            if (!printerManager.hasSavedPrinter()) {
                showToast("কোনো ব্লুটুথ প্রিন্টার সেভ করা নেই। আগে সেটিংস থেকে একটি প্রিন্টার সিলেক্ট করুন।")
                val intent = Intent(context, PrinterSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return@runWithBluetoothReady
            }
            printerManager.printBitmapBase64(base64Image)
        }
    }

    @JavascriptInterface
    fun printBitmap(base64Image: String) {
        printBitmapBase64(base64Image)
    }

    /** Ensures Bluetooth is on (prompting the user if needed) before running a print action. */
    private fun runWithBluetoothReady(action: () -> Unit) {
        if (printerManager.isBluetoothEnabled()) {
            action()
            return
        }
        enableRequester.requestEnableBluetooth { granted ->
            if (granted) {
                action()
            } else {
                showToast("Print korte Bluetooth chalu thakte hobe")
            }
        }
    }

    private fun showToast(message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        com.amarhisab.app.utils.CustomToast.show(context, message, isError = isError, isSuccess = isSuccess)
    }

    companion object {
        private const val TAG = "WebAppInterface"
    }
}
