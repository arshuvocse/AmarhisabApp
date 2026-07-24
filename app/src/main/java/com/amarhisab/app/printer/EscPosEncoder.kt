package com.amarhisab.app.printer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Builds raw ESC/POS byte sequences for thermal receipt printers.
 * Reference: Epson ESC/POS command set (widely compatible with generic
 * Bluetooth thermal printers used for POS receipts).
 */
object EscPosEncoder {

    private val CP = Charset.forName("UTF-8")

    private const val ESC = 0x1B
    private const val GS = 0x1D

    /** Pixels lighter than this (0-255 luminance) print as white. Raised to 215
     *  so anti-aliased text edges print darker/bolder/sharper on thermal paper. */
    private const val DARKNESS_THRESHOLD = 215

    fun init(): ByteArray = byteArrayOf(ESC.toByte(), '@'.code.toByte())

    fun alignLeft(): ByteArray = byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0)
    fun alignCenter(): ByteArray = byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1)
    fun alignRight(): ByteArray = byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 2)

    fun boldOn(): ByteArray = byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1)
    fun boldOff(): ByteArray = byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0)

    fun doubleHeightOn(): ByteArray = byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11)
    fun doubleHeightOff(): ByteArray = byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00)

    fun text(value: String): ByteArray = value.toByteArray(CP)

    fun newLine(count: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) { out.write('\n'.code) }
        return out.toByteArray()
    }

    fun divider(width: Int = 32): ByteArray = ("-".repeat(width) + "\n").toByteArray(CP)

    /** Feeds paper and cuts (full cut). Not all printers support auto-cut; ignored if unsupported. */
    fun cutPaper(): ByteArray = byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x41, 0x00)

    /**
     * Converts a monochrome-ready Bitmap into ESC/POS raster bit-image commands (GS v 0).
     * Slices the bitmap into height chunks (max 160px) to prevent thermal printer buffer overflow.
     */
    fun bitmapToRaster(bitmap: Bitmap, chunkHeight: Int = 160): ByteArray {
        val width = bitmap.width
        val totalHeight = bitmap.height
        val bytesPerRow = (width + 7) / 8
        val out = ByteArrayOutputStream()

        var currentY = 0
        while (currentY < totalHeight) {
            val currentChunkHeight = Math.min(chunkHeight, totalHeight - currentY)

            out.write(GS)
            out.write('v'.code)
            out.write('0'.code)
            out.write(0) // normal mode
            out.write(bytesPerRow and 0xFF)
            out.write((bytesPerRow shr 8) and 0xFF)
            out.write(currentChunkHeight and 0xFF)
            out.write((currentChunkHeight shr 8) and 0xFF)

            for (y in currentY until (currentY + currentChunkHeight)) {
                var bitIndex = 0
                var currentByte = 0
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance = (android.graphics.Color.red(pixel) * 0.299 +
                            android.graphics.Color.green(pixel) * 0.587 +
                            android.graphics.Color.blue(pixel) * 0.114)
                    val isBlack = luminance < DARKNESS_THRESHOLD
                    if (isBlack) currentByte = currentByte or (0x80 shr bitIndex)
                    bitIndex++
                    if (bitIndex == 8) {
                        out.write(currentByte)
                        currentByte = 0
                        bitIndex = 0
                    }
                }
                if (bitIndex != 0) out.write(currentByte)
            }
            currentY += currentChunkHeight
        }
        return out.toByteArray()
    }

    /**
     * Universal ESC/POS Bit-Image command (ESC * 33).
     * Supported by 100% of generic Bluetooth thermal printers (including Dotmax, ZJiang, POS-58).
     */
    fun bitmapToEscAsterisk(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val out = ByteArrayOutputStream()

        // Set line spacing to 24 dots (ESC 3 24)
        out.write(byteArrayOf(ESC.toByte(), '3'.code.toByte(), 24))

        var y = 0
        while (y < height) {
            out.write(byteArrayOf(ESC.toByte(), '*'.code.toByte(), 33)) // 24-dot double density
            val nL = (width and 0xFF).toByte()
            val nH = ((width shr 8) and 0xFF).toByte()
            out.write(byteArrayOf(nL, nH))

            for (x in 0 until width) {
                for (k in 0 until 3) {
                    var slice = 0
                    for (b in 0 until 8) {
                        val currentY = y + k * 8 + b
                        if (currentY < height) {
                            val pixel = bitmap.getPixel(x, currentY)
                            val alpha = android.graphics.Color.alpha(pixel)
                            val red = android.graphics.Color.red(pixel)
                            val green = android.graphics.Color.green(pixel)
                            val blue = android.graphics.Color.blue(pixel)
                            val luminance = (red * 0.299 + green * 0.587 + blue * 0.114)
                            val isBlack = (alpha > 50) && (luminance < 200)
                            if (isBlack) {
                                slice = slice or (0x80 shr b)
                            }
                        }
                    }
                    out.write(slice)
                }
            }
            out.write('\n'.code)
            y += 24
        }

        // Reset line spacing (ESC 2)
        out.write(byteArrayOf(ESC.toByte(), '2'.code.toByte()))
        return out.toByteArray()
    }
}
