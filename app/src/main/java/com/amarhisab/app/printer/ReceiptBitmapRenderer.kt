package com.amarhisab.app.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import org.json.JSONObject

/**
 * Purpose-built native renderer for 58mm thermal receipt printing.
 * Draws structured receipt JSON directly onto an exact 384px wide Canvas.
 * Bypasses WebView screenshot scaling to preserve crisp Bangla typography.
 */
object ReceiptBitmapRenderer {

    const val PRINTER_WIDTH_58MM = 384

    private var cachedFontBold: Typeface? = null
    private var cachedFontRegular: Typeface? = null

    fun getFont(context: Context?, isBold: Boolean): Typeface {
        if (isBold && cachedFontBold != null) return cachedFontBold!!
        if (!isBold && cachedFontRegular != null) return cachedFontRegular!!

        var tf: Typeface? = null
        if (context != null) {
            try {
                val assetPath = if (isBold) "fonts/NotoSansBengali-Bold.ttf" else "fonts/NotoSansBengali-Regular.ttf"
                tf = Typeface.createFromAsset(context.assets, assetPath)
            } catch (_: Exception) {
                try {
                    tf = Typeface.createFromAsset(context.assets, "fonts/SolaimanLipi.ttf")
                } catch (_: Exception) {}
            }
        }

        val nonNullTf = tf ?: (if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT)

        if (isBold) cachedFontBold = nonNullTf else cachedFontRegular = nonNullTf
        return nonNullTf
    }

    fun renderReceipt(
        receipt: JSONObject,
        context: Context? = null,
        width: Int = PRINTER_WIDTH_58MM
    ): Bitmap {
        val boldTypeface = getFont(context, isBold = true)
        val regularTypeface = getFont(context, isBold = false)

        val titlePaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = boldTypeface
            isFakeBoldText = true
            isAntiAlias = true
        }

        val subHeaderPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = regularTypeface
            isFakeBoldText = true
            isAntiAlias = true
        }

        val boldHeaderPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 26f
            typeface = boldTypeface
            isFakeBoldText = true
            isAntiAlias = true
        }

        val itemPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 26f
            typeface = boldTypeface
            isFakeBoldText = true
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        val shopName = receipt.optString("shopName", receipt.optString("shop_name", "আমার হিসাব")).ifEmpty { "আমার হিসাব" }
        val address = receipt.optString("address", receipt.optString("shop_address", ""))
        val phone = receipt.optString("phone", receipt.optString("shop_phone", ""))
        val invoiceNo = receipt.optString("invoiceNo", receipt.optString("invoice_no", receipt.optString("voucher_no", "")))
        val date = receipt.optString("date", receipt.optString("created_at", ""))
        val customerName = receipt.optString("customerName", receipt.optString("customer_name", ""))
        val footer = receipt.optString("footer", receipt.optString("note", "ধন্যবাদ! আবার আসবেন।"))

        var measuredHeight = 25

        measuredHeight += wrapText(shopName, titlePaint, width - 20).size * 38 + 10
        if (address.isNotEmpty()) measuredHeight += wrapText(address, subHeaderPaint, width - 20).size * 30 + 5
        if (phone.isNotEmpty()) measuredHeight += wrapText("ফোন: $phone", subHeaderPaint, width - 20).size * 30 + 5

        measuredHeight += 15

        if (invoiceNo.isNotEmpty()) measuredHeight += 30
        if (date.isNotEmpty()) measuredHeight += 30
        if (customerName.isNotEmpty()) measuredHeight += 30

        measuredHeight += 15
        measuredHeight += 34

        val itemsArray = receipt.optJSONArray("items")
        val itemList = mutableListOf<ReceiptItem>()
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.optJSONObject(i) ?: continue
                val name = obj.optString("name", obj.optString("title", "আইটেম ${i + 1}"))
                val qty = obj.optString("qty", obj.optString("quantity", "1"))
                val price = obj.optString("price", obj.optString("rate", "0"))
                val calcTotal = ((qty.toDoubleOrNull() ?: 1.0) * (price.toDoubleOrNull() ?: 0.0)).toString()
                val total = obj.optString("total", obj.optString("subtotal", calcTotal))
                itemList.add(ReceiptItem(name, qty, price, total))
            }
        }

        for (item in itemList) {
            val lines = wrapText(item.name, itemPaint, 170)
            val rowHeight = (lines.size * 30).coerceAtLeast(34)
            measuredHeight += rowHeight + 6
        }

        measuredHeight += 15

        val total = receipt.optString("total", receipt.optString("grand_total", "0"))
        val subtotal = receipt.optString("subtotal", "")
        val discount = receipt.optString("discount", "")
        val paid = receipt.optString("paid", receipt.optString("paid_amount", ""))
        val due = receipt.optString("due", receipt.optString("due_amount", ""))

        if (subtotal.isNotEmpty()) measuredHeight += 30
        if (discount.isNotEmpty()) measuredHeight += 30
        measuredHeight += 38
        if (paid.isNotEmpty()) measuredHeight += 30
        if (due.isNotEmpty()) measuredHeight += 30

        measuredHeight += 20
        if (footer.isNotEmpty()) measuredHeight += wrapText(footer, subHeaderPaint, width - 20).size * 30 + 10
        measuredHeight += 30

        val bitmap = Bitmap.createBitmap(width, measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = 30f

        for (line in wrapText(shopName, titlePaint, width - 20)) {
            val textWidth = titlePaint.measureText(line)
            val x = ((width - textWidth) / 2f).coerceAtLeast(10f)
            canvas.drawText(line, x, y, titlePaint)
            y += 38f
        }

        if (address.isNotEmpty()) {
            for (line in wrapText(address, subHeaderPaint, width - 20)) {
                val textWidth = subHeaderPaint.measureText(line)
                val x = ((width - textWidth) / 2f).coerceAtLeast(10f)
                canvas.drawText(line, x, y, subHeaderPaint)
                y += 30f
            }
        }
        if (phone.isNotEmpty()) {
            val pText = "ফোন: $phone"
            val textWidth = subHeaderPaint.measureText(pText)
            val x = ((width - textWidth) / 2f).coerceAtLeast(10f)
            canvas.drawText(pText, x, y, subHeaderPaint)
            y += 30f
        }

        y += 10f
        canvas.drawLine(10f, y, (width - 10).toFloat(), y, dividerPaint)
        y += 25f

        if (invoiceNo.isNotEmpty()) {
            canvas.drawText("ইনভয়েস: $invoiceNo", 10f, y, boldHeaderPaint)
            y += 30f
        }
        if (date.isNotEmpty()) {
            canvas.drawText("তারিখ: $date", 10f, y, subHeaderPaint)
            y += 30f
        }
        if (customerName.isNotEmpty()) {
            canvas.drawText("গ্রাহক: $customerName", 10f, y, subHeaderPaint)
            y += 30f
        }

        y += 5f
        canvas.drawLine(10f, y, (width - 10).toFloat(), y, dividerPaint)
        y += 25f

        canvas.drawText("বিবরণ", 10f, y, boldHeaderPaint)
        canvas.drawText("পরিমাণ", 185f, y, boldHeaderPaint)
        canvas.drawText("দর", 260f, y, boldHeaderPaint)
        canvas.drawText("মোট", 325f, y, boldHeaderPaint)

        y += 10f
        canvas.drawLine(10f, y, (width - 10).toFloat(), y, dividerPaint)
        y += 25f

        for (item in itemList) {
            val nameLines = wrapText(item.name, itemPaint, 170)
            val startY = y

            for (i in nameLines.indices) {
                canvas.drawText(nameLines[i], 10f, y, itemPaint)
                if (i < nameLines.size - 1) y += 30f
            }

            canvas.drawText(item.qty, 190f, startY, itemPaint)
            canvas.drawText(item.price, 260f, startY, itemPaint)
            canvas.drawText(item.total, 325f, startY, itemPaint)

            y += 34f
        }

        canvas.drawLine(10f, y, (width - 10).toFloat(), y, dividerPaint)
        y += 25f

        if (subtotal.isNotEmpty()) {
            canvas.drawText("সাবটোটাল:", 10f, y, subHeaderPaint)
            canvas.drawText(subtotal, 280f, y, subHeaderPaint)
            y += 30f
        }
        if (discount.isNotEmpty()) {
            canvas.drawText("ডিসকাউন্ট:", 10f, y, subHeaderPaint)
            canvas.drawText(discount, 280f, y, subHeaderPaint)
            y += 30f
        }

        canvas.drawText("সর্বমোট (Total):", 10f, y, titlePaint)
        canvas.drawText(total, 260f, y, titlePaint)
        y += 40f

        if (paid.isNotEmpty()) {
            canvas.drawText("পরিশোধ:", 10f, y, boldHeaderPaint)
            canvas.drawText(paid, 280f, y, boldHeaderPaint)
            y += 30f
        }
        if (due.isNotEmpty()) {
            canvas.drawText("বাকি (Due):", 10f, y, boldHeaderPaint)
            canvas.drawText(due, 280f, y, boldHeaderPaint)
            y += 30f
        }

        y += 10f
        canvas.drawLine(10f, y, (width - 10).toFloat(), y, dividerPaint)
        y += 25f

        if (footer.isNotEmpty()) {
            for (line in wrapText(footer, subHeaderPaint, width - 20)) {
                val textWidth = subHeaderPaint.measureText(line)
                val x = ((width - textWidth) / 2f).coerceAtLeast(10f)
                canvas.drawText(line, x, y, subHeaderPaint)
                y += 30f
            }
        }

        return bitmap
    }

    fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(word)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    lines.add(word)
                    currentLine = StringBuilder()
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    data class ReceiptItem(
        val name: String,
        val qty: String,
        val price: String,
        val total: String
    )
}
