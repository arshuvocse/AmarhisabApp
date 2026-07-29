package com.amarhisab.app.printer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64

/**
 * Handles the "Bitmap renderer" stage of the printer pipeline:
 * decodes base64 images sent from the web page, renders Bangla/Unicode text to bitmap,
 * and scales them to a printer-safe dot width before handing off to EscPosEncoder.
 */
object BitmapPrintRenderer {

    /** Common 58mm thermal printer print width in dots. Use 576 for 80mm printers. */
    const val DEFAULT_PRINTER_DOT_WIDTH = 384

    fun decodeBase64(base64Image: String): Bitmap? {
        return try {
            val cleaned = base64Image.substringAfter("base64,", base64Image)
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    fun scaleToPrinterWidth(bitmap: Bitmap, targetWidth: Int = DEFAULT_PRINTER_DOT_WIDTH): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val ratio = targetWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Removes colored background fills (like green headers on web pages) by converting them to
     * clean white background, while preserving text anti-aliasing and grayscale details for
     * ultra-sharp, high-quality thermal printing.
     */
    fun cleanColoredBackgrounds(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val cleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = (pixel shr 24) and 0xFF

            if (alpha < 50) {
                pixels[i] = Color.WHITE
                continue
            }

            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val maxC = maxOf(r, maxOf(g, b))
            val minC = minOf(r, minOf(g, b))
            val isColored = (maxC - minC) > 20

            if (isColored) {
                val luminance = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
                if (luminance > 160) {
                    pixels[i] = Color.WHITE
                } else {
                    pixels[i] = Color.BLACK
                }
            } else {
                pixels[i] = pixel
            }
        }

        cleanBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return cleanBitmap
    }

    /**
     * Samples the bitmap (every 2nd pixel) for anything dark enough to actually mark the
     * thermal paper. Used right before a print job is sent, so a blank/invisible capture
     * (e.g. html2canvas screenshotting a not-yet-rendered element) aborts instead of wasting paper.
     */
    fun hasVisibleContent(bitmap: Bitmap, minDarkPixels: Int = 15): Boolean {
        val step = 2
        var darkCount = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 50) {
                    val luminance = Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114
                    if (luminance < 200) {
                        darkCount++
                        if (darkCount >= minDarkPixels) return true
                    }
                }
                x += step
            }
            y += step
        }
        return false
    }

    /**
     * Renders Bangla / Unicode text into a crisp black-and-white Bitmap canvas
     * for printing on ESC/POS thermal printers that lack built-in Bangla ROM fonts.
     */
    fun renderTextToBitmap(text: String, width: Int = DEFAULT_PRINTER_DOT_WIDTH): Bitmap {
        val textPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 16f
            isAntiAlias = true
            isSubpixelText = true
            typeface = Typeface.MONOSPACE
        }

        val alignment = Layout.Alignment.ALIGN_NORMAL
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, width, alignment, 1.15f, 0f, true)
        }

        val bitmap = Bitmap.createBitmap(width, layout.height + 20, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(0f, 10f)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    /**
     * Renders a bordered, table-style Bangla thermal receipt: one outer box, horizontal rules
     * between every field, a solid-black header bar with white text for the item table (thermal
     * printers are monochrome, so the reference design's green header prints as an inverted
     * black bar instead), vertical column rules across the item/charge grid, and a plain footer.
     * Header (Shop, Address, Phone, Person/Invoice ID, Date, Seller)
     * Items Table (Name, Price, Qty, Total)
     * Charges (Discount, VAT, Transport/Labor)
     * Balance Summary (Invoice Total, Subtotal, Payment, Invoice Due, Previous Due, Net Due)
     * Footer (Software Developed By www.softhostit.com)
     */
    fun renderReceiptToBitmap(receipt: org.json.JSONObject, width: Int = DEFAULT_PRINTER_DOT_WIDTH): Bitmap {
        val scale = 2.0f
        val renderWidth = (width * scale).toInt()

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val headerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val boldHeaderPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val itemPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val tableHeadPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val footerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val footerBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_ON
        }

        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3.0f
            isAntiAlias = true
        }

        val fillPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Extract JSON fields with fallback defaults from sample receipt
        val shopName = receipt.optString("shopName", receipt.optString("shop_name", receipt.optString("name", "amarhisab")))
        val address = receipt.optString("address", receipt.optString("shop_address", receipt.optString("location", "Dhaka")))
        val mobile = receipt.optString("mobile", receipt.optString("phone", receipt.optString("mobile_no", "01900000000")))
        val personId = receipt.optString("personId", receipt.optString("person_id", receipt.optString("customerId", "6")))
        val invoiceId = receipt.optString("invoiceId", receipt.optString("invoice_id", receipt.optString("invoiceNo", "658")))
        val date = receipt.optString("date", receipt.optString("dateTime", receipt.optString("created_at", "09 Jul 2026 at 09:55:39 PM")))
        val seller = receipt.optString("seller", receipt.optString("user", receipt.optString("operator", "akhi_9920")))

        val discount = receipt.optDouble("discount", receipt.optDouble("discount_amount", 0.0))
        val vat = receipt.optDouble("vat", receipt.optDouble("tax", 0.0))
        val transportCost = receipt.optDouble("transportCost", receipt.optDouble("transport_cost", receipt.optDouble("laborCost", 0.0)))

        val subtotal = receipt.optDouble("subtotal", receipt.optDouble("sub_total", 0.0))
        val payment = receipt.optDouble("payment", receipt.optDouble("paid", receipt.optDouble("paid_amount", 0.0)))
        val invoiceDue = receipt.optDouble("invoiceDue", receipt.optDouble("due", receipt.optDouble("due_amount", 0.0)))
        val previousDue = receipt.optDouble("previousDue", receipt.optDouble("prev_due", 0.0))
        val netDue = receipt.optDouble("netDue", receipt.optDouble("total_due", 0.0))

        val footerText = receipt.optString("footer", "")
        val devLabel = if (footerText.isNotBlank()) footerText else "Developed By"
        val webUrl = receipt.optString("website", receipt.optString("webUrl", "www.amarhisab.com"))

        data class ReceiptItem(val name: String, val price: Double, val qty: Double, val total: Double)

        val itemsArray = receipt.optJSONArray("items")
        val itemCount = itemsArray?.length() ?: 0
        val items = if (itemCount > 0) {
            (0 until itemCount).map { i ->
                val item = itemsArray!!.getJSONObject(i)
                val qty = item.optDouble("qty", item.optDouble("quantity", 1.0))
                val price = item.optDouble("price", item.optDouble("rate", 0.0))
                val total = item.optDouble("total", qty * price)
                ReceiptItem(item.optString("name", "test"), price, qty, total)
            }
        } else {
            listOf(ReceiptItem("test", 0.0, 1.0, 0.0))
        }
        val totalQtyStr = "%.2f".format(receipt.optDouble("invoiceTotalQty", items.sumOf { it.qty }))
        val totalAmountStr = "%.2f".format(receipt.optDouble("invoiceTotalAmount", items.sumOf { it.total }))

        // Outer box + item-table column geometry (2x super-sampled for 100% crisp anti-aliased print)
        val leftMargin = 24f
        val rightMargin = 68f
        val margin = leftMargin
        val boxLeft = leftMargin
        val boxRight = renderWidth - rightMargin
        val boxWidth = boxRight - boxLeft
        val padX = 10f
        val padY = 8f
        val rowH = 56f

        val col1W = boxWidth * 0.40f
        val col2W = boxWidth * 0.20f
        val col3W = boxWidth * 0.20f
        val cx0 = boxLeft
        val cx1 = cx0 + col1W
        val cx2 = cx1 + col2W
        val cx3 = cx2 + col3W
        val cx4 = boxRight
        val midX = boxLeft + boxWidth / 2f

        fun buildLayout(text: String, paint: TextPaint, maxWidth: Float, align: Layout.Alignment): StaticLayout {
            val safeWidth = maxWidth.toInt().coerceAtLeast(1)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                        .setAlignment(align)
                        .setLineSpacing(0f, 1.0f)
                        .setIncludePad(false)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(text, paint, safeWidth, align, 1.0f, 0f, false)
                }
            } catch (e: Exception) {
                val fallbackWidth = maxOf(safeWidth, paint.measureText(text).toInt() + 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(text, 0, text.length, paint, fallbackWidth)
                        .setAlignment(align)
                        .setLineSpacing(0f, 1.0f)
                        .setIncludePad(false)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(text, paint, fallbackWidth, align, 1.0f, 0f, false)
                }
            }
        }

        fun pass(canvas: Canvas?): Float {
            var y = margin

            fun hLine(yy: Float) {
                canvas?.drawLine(boxLeft, yy, boxRight, yy, borderPaint)
            }

            fun vLine(x: Float, y0: Float, y1: Float) {
                canvas?.drawLine(x, y0, x, y1, borderPaint)
            }

            fun drawCentered(text: String, colStart: Float, colEnd: Float, top: Float, paint: TextPaint) {
                val w = paint.measureText(text)
                val x = colStart + ((colEnd - colStart) - w) / 2f
                canvas?.drawText(text, x.coerceAtLeast(colStart), top - paint.fontMetrics.ascent, paint)
            }

            fun singleRow(text: String, paint: TextPaint, align: Layout.Alignment) {
                val top = y
                val contentTop = top + padY
                val layout = buildLayout(text, paint, boxWidth - padX * 2, align)
                val bottom = top + (layout.height + padY * 2).coerceAtLeast(rowH)
                canvas?.let {
                    it.save(); it.translate(boxLeft + padX, contentTop); layout.draw(it); it.restore()
                }
                y = bottom
                hLine(y)
            }

            fun twoColRow(left: String, right: String, paint: TextPaint) {
                val top = y
                val contentTop = top + padY
                val leftLayout = buildLayout(left, paint, (midX - boxLeft) - padX * 2, Layout.Alignment.ALIGN_NORMAL)
                val rightLayout = buildLayout(right, paint, (boxRight - midX) - padX * 2, Layout.Alignment.ALIGN_NORMAL)
                val bottom = top + (maxOf(leftLayout.height, rightLayout.height) + padY * 2).coerceAtLeast(rowH)
                canvas?.let {
                    it.save(); it.translate(boxLeft + padX, contentTop); leftLayout.draw(it); it.restore()
                    it.save(); it.translate(midX + padX, contentTop); rightLayout.draw(it); it.restore()
                }
                vLine(midX, top, bottom)
                y = bottom
                hLine(y)
            }

            fun labelValueRow(label: String, value: String, paint: TextPaint) {
                val top = y + padY
                canvas?.drawText(label, boxLeft + padX, top - paint.fontMetrics.ascent, paint)
                val w = paint.measureText(value)
                canvas?.drawText(value, (boxRight - padX - w).coerceAtLeast(boxLeft + padX), top - paint.fontMetrics.ascent, paint)
                y += rowH
                hLine(y)
            }

            fun gridRow(col1: String, col2: String, col3: String, col4: String, labelPaint: TextPaint, valuePaint: TextPaint, fill: Boolean) {
                val top = y
                val contentTop = top + padY
                val layout = buildLayout(col1, labelPaint, col1W - padX * 2, Layout.Alignment.ALIGN_CENTER)
                val bottom = top + (layout.height + padY * 2).coerceAtLeast(rowH)
                if (fill) canvas?.drawRect(boxLeft, top, boxRight, bottom, fillPaint)
                canvas?.let {
                    it.save(); it.translate(cx0 + padX, contentTop); layout.draw(it); it.restore()
                }
                drawCentered(col2, cx1, cx2, contentTop, valuePaint)
                drawCentered(col3, cx2, cx3, contentTop, valuePaint)
                drawCentered(col4, cx3, cx4, contentTop, valuePaint)
                vLine(cx0, top, bottom)
                vLine(cx1, top, bottom)
                vLine(cx2, top, bottom)
                vLine(cx3, top, bottom)
                vLine(cx4, top, bottom)
                y = bottom
                hLine(y)
            }

            singleRow(shopName, titlePaint, Layout.Alignment.ALIGN_CENTER)
            singleRow(address, headerPaint, Layout.Alignment.ALIGN_CENTER)
            singleRow("মোবাইল: - $mobile", headerPaint, Layout.Alignment.ALIGN_NORMAL)
            twoColRow("ব্যক্তি.আইডি:- $personId", "ইনভয়েস.আইডি:- $invoiceId", headerPaint)
            singleRow("তারিখ:- $date", headerPaint, Layout.Alignment.ALIGN_NORMAL)
            singleRow(":- $seller", headerPaint, Layout.Alignment.ALIGN_NORMAL)

            gridRow("নাম", "মূল্য", "পরিমাণ", "মোট", tableHeadPaint, tableHeadPaint, fill = false)
            items.forEach { item ->
                gridRow(item.name, "%.2f".format(item.price), "%.2f".format(item.qty), "%.2f".format(item.total), itemPaint, itemPaint, fill = false)
            }

            if (discount > 0) {
                gridRow("ডিসকাউন্ট", "--", "--", "%.2f".format(discount), headerPaint, headerPaint, fill = false)
            }
            if (vat > 0) {
                gridRow("ভ্যাট", "--", "--", "%.2f".format(vat), headerPaint, headerPaint, fill = false)
            }
            if (transportCost > 0) {
                gridRow("ট্রান্সপোর্টেশন খরচ", "--", "--", "%.2f".format(transportCost), headerPaint, headerPaint, fill = false)
            }

            gridRow("ইনভয়েস মোট", totalAmountStr, totalQtyStr, totalAmountStr, boldHeaderPaint, boldHeaderPaint, fill = false)
            labelValueRow("সাব টোটাল =", "%.2f".format(subtotal), headerPaint)
            labelValueRow("পেমেন্ট =", "%.2f".format(payment), headerPaint)
            labelValueRow("ইনভয়েস বাকি =", "%.2f".format(invoiceDue), headerPaint)
            labelValueRow("পূর্বের বাকি =", "%.2f".format(previousDue), headerPaint)
            labelValueRow("নেট বাকি =", "%.2f".format(netDue), boldHeaderPaint)

            y += 16f
            val footerTop1 = y
            val devLayout = buildLayout(devLabel, footerBoldPaint, boxWidth - padX * 2, Layout.Alignment.ALIGN_CENTER)
            canvas?.let {
                it.save(); it.translate(boxLeft + padX, footerTop1); devLayout.draw(it); it.restore()
            }
            y = footerTop1 + devLayout.height + 8f

            val footerTop2 = y
            val webLayout = buildLayout(webUrl, footerPaint, boxWidth - padX * 2, Layout.Alignment.ALIGN_CENTER)
            canvas?.let {
                it.save(); it.translate(boxLeft + padX, footerTop2); webLayout.draw(it); it.restore()
            }
            y = footerTop2 + webLayout.height + 20f

            canvas?.drawRect(boxLeft, margin, boxRight, y, borderPaint)
            return y
        }

        val totalHeight = pass(null).toInt().coerceAtLeast(200)
        val highResBitmap = Bitmap.createBitmap(renderWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(highResBitmap)
        canvas.drawColor(Color.WHITE)
        pass(canvas)

        return scaleToPrinterWidth(highResBitmap, width)
    }
}
