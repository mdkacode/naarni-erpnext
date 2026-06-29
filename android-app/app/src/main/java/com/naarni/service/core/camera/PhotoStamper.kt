package com.naarni.service.core.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns an accountability stamp into the bottom-left of a captured photo:
 * date-time, latitude/longitude (+accuracy), and the capturing user's name —
 * clearly visible on a high-contrast bar (KOTLIN_APP_PLAN.md §5 headline feature).
 *
 * The text is also intended to be written to EXIF by the caller; this method
 * handles the visible burn-in.
 */
object PhotoStamper {

    data class StampData(
        val dateTime: String,
        val location: String,
        val by: String,
        /** Optional photo type/angle (e.g. "Front", "Damage") shown as the top line. */
        val label: String? = null,
    )

    /** Build stamp text from raw inputs at capture time. */
    fun build(
        location: Location?,
        userFullName: String,
        userRole: String,
        whenMillis: Long = System.currentTimeMillis(),
        label: String? = null,
    ): StampData {
        val ts = SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.getDefault())
            .format(Date(whenMillis))
        val loc = if (location != null) {
            val acc = if (location.hasAccuracy()) "  (±${location.accuracy.toInt()} m)" else ""
            "${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}$acc"
        } else {
            "Location unavailable"
        }
        return StampData(dateTime = ts, location = loc, by = "By: $userFullName ($userRole)", label = label)
    }

    /** Draw the stamp onto a copy of [src] and return it. */
    fun stamp(src: Bitmap, data: StampData): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val w = bmp.width
        val h = bmp.height

        val textSize = (w * 0.032f).coerceAtLeast(28f) // ~3.2% of width, min legible
        val pad = textSize * 0.5f
        val lineH = textSize * 1.35f

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }

        val lines = listOfNotNull(data.label?.let { "📷 $it" }, data.dateTime, data.location, data.by)
        val maxLineW = lines.maxOf { text.measureText(it) }
        val blockH = lineH * lines.size

        val left = pad
        val bottom = h - pad
        val top = bottom - blockH - pad

        canvas.drawRoundRect(
            left - pad * 0.5f, top - pad * 0.5f,
            left + maxLineW + pad, bottom + pad * 0.25f,
            pad * 0.4f, pad * 0.4f, bg,
        )

        var y = top + textSize
        for (line in lines) {
            canvas.drawText(line, left, y, text)
            y += lineH
        }
        return bmp
    }
}
