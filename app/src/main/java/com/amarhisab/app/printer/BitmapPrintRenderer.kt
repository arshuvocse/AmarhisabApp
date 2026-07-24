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
        val sideMargin = 8
        val safeWidth = targetWidth - (sideMargin * 2)
        if (bitmap.width == targetWidth) return bitmap
        val ratio = safeWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaledInner = Bitmap.createScaledBitmap(bitmap, safeWidth, targetHeight, true)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaledInner, sideMargin.toFloat(), 0f, null)
        return output
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
            textSize = 20f
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
        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val headerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val boldHeaderPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val itemPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val tableHeadPaint = TextPaint().apply {
            color = Color.WHITE
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val footerPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val footerBoldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.3f
        }

        val fillPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
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
        val devLabel = if (footerText.isNotBlank()) footerText else "Software Developed By"
        val webUrl = "www.softhostit.com"

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

        // Outer box + item-table column geometry (নাম gets 40%, the three numeric columns split the rest evenly)
        val margin = 8f
        val boxLeft = margin
        val boxRight = width - margin
        val boxWidth = boxRight - boxLeft
        val padX = 6f
        val padY = 6f
        val rowH = 34f

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
                // Narrow columns wrapping Bangla conjunct clusters can crash StaticLayout's
                // line-breaker on some Android versions; fall back to an unwrapped single line.
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

        /**
         * Draws the full receipt (or, with [canvas] == null, only measures it) and returns the
         * total content height. Called once to size the bitmap and once to draw it, so the two
         * passes share the exact same row-by-row logic and can never disagree on layout.
         */
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

            // Full-width row, single block of (possibly wrapping) text
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

            // Full-width row split into two independent halves (person/invoice ID)
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

            // Label left, value right, on one line (balance summary rows)
            fun labelValueRow(label: String, value: String, paint: TextPaint) {
                val top = y + padY
                canvas?.drawText(label, boxLeft + padX, top - paint.fontMetrics.ascent, paint)
                val w = paint.measureText(value)
                canvas?.drawText(value, (boxRight - padX - w).coerceAtLeast(boxLeft + padX), top - paint.fontMetrics.ascent, paint)
                y += rowH
                hLine(y)
            }

            // The নাম | মূল্য | পরিমাণ | মোট grid used by the table header, item rows, charges and the invoice-total row
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
                vLine(cx1, top, bottom)
                vLine(cx2, top, bottom)
                vLine(cx3, top, bottom)
                y = bottom
                hLine(y)
            }

            // 1-6. Shop header
            singleRow(shopName, titlePaint, Layout.Alignment.ALIGN_CENTER)
            singleRow(address, headerPaint, Layout.Alignment.ALIGN_CENTER)
            singleRow("মোবাইল: - $mobile", headerPaint, Layout.Alignment.ALIGN_NORMAL)
            twoColRow("ব্যক্তি.আইডি:- $personId", "ইনভয়েস.আইডি:- $invoiceId", headerPaint)
            singleRow("তারিখ:- $date", headerPaint, Layout.Alignment.ALIGN_NORMAL)
            singleRow(":- $seller", headerPaint, Layout.Alignment.ALIGN_NORMAL)

            // 7-8. Item table
            gridRow("নাম", "মূল্য", "পরিমাণ", "মোট", tableHeadPaint, tableHeadPaint, fill = true)
            items.forEach { item ->
                gridRow(item.name, "%.2f".format(item.price), "%.2f".format(item.qty), "%.2f".format(item.total), itemPaint, itemPaint, fill = false)
            }

            // 9. Charges & discounts
            gridRow("ডিসকাউন্ট ----", "--", "--", "%.2f".format(discount), headerPaint, headerPaint, fill = false)
            gridRow("ভ্যাট ----", "--", "--", "%.2f".format(vat), headerPaint, headerPaint, fill = false)
            gridRow("ট্রান্সপোর্টেশন খরচ & শ্রমিকের খরচ ----", "--", "--", "%.2f".format(transportCost), headerPaint, headerPaint, fill = false)

            // 10. Invoice total + balance summary
            gridRow("ইনভয়েস মোট", totalAmountStr, totalQtyStr, totalAmountStr, boldHeaderPaint, boldHeaderPaint, fill = false)
            labelValueRow("সাব টোটাল =", "%.2f".format(subtotal), headerPaint)
            labelValueRow("পেমেন্ট =", "%.2f".format(payment), headerPaint)
            labelValueRow("ইনভয়েস বাকি =", "%.2f".format(invoiceDue), headerPaint)
            labelValueRow("পূর্বের বাকি =", "%.2f".format(previousDue), headerPaint)
            labelValueRow("নেট বাকি =", "%.2f".format(netDue), boldHeaderPaint)

            // 11. Footer
            y += 8f
            val footerTop1 = y
            val devLayout = buildLayout(devLabel, footerBoldPaint, boxWidth - padX * 2, Layout.Alignment.ALIGN_CENTER)
            canvas?.let {
                it.save(); it.translate(boxLeft + padX, footerTop1); devLayout.draw(it); it.restore()
            }
            y = footerTop1 + devLayout.height + 4f

            val footerTop2 = y
            val webLayout = buildLayout(webUrl, footerPaint, boxWidth - padX * 2, Layout.Alignment.ALIGN_CENTER)
            canvas?.let {
                it.save(); it.translate(boxLeft + padX, footerTop2); webLayout.draw(it); it.restore()
            }
            y = footerTop2 + webLayout.height + 10f

            // Outer box (also covers the top rule, since nothing draws one before the first row)
            canvas?.drawRect(boxLeft, margin, boxRight, y, borderPaint)
            return y
        }

        val totalHeight = pass(null).toInt().coerceAtLeast(100)
        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        pass(canvas)
        return bitmap
    }
}
