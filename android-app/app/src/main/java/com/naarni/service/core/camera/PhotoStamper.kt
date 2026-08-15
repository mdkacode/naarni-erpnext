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
 * the subject's serial, date-time, latitude/longitude (+accuracy), and the
 * capturing user's name — on a high-contrast bar (KOTLIN_APP_PLAN.md §5).
 *
 * **The serial line is what makes a photo findable.** Without it an inspection
 * archive is thousands of near-identical pictures of battery packs, and the only
 * way to tell which pack a photo shows is to trace it back through the record it
 * was attached to. Burning the scanned serial into the pixels means the photo
 * carries its own identity even after it has been exported, emailed or printed —
 * which is exactly when the surrounding record is gone.
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
        /** The scanned serial / pack number this photo belongs to. */
        val subject: String? = null,
    )

    /** Build stamp text from raw inputs at capture time. */
    fun build(
        location: Location?,
        userFullName: String,
        userRole: String,
        whenMillis: Long = System.currentTimeMillis(),
        label: String? = null,
        subject: String? = null,
    ): StampData {
        val ts = SimpleDateFormat("dd MMM yyyy, HH:mm:ss z", Locale.getDefault())
            .format(Date(whenMillis))
        val loc = if (location != null) {
            val acc = if (location.hasAccuracy()) "  (±${location.accuracy.toInt()} m)" else ""
            "${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}$acc"
        } else {
            "Location unavailable"
        }
        return StampData(
            dateTime = ts,
            location = loc,
            by = "By: $userFullName ($userRole)",
            label = label,
            subject = subject?.trim()?.takeIf { it.isNotEmpty() },
        )
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

        // Serial first: it is the line a person scanning a folder of photos is
        // actually looking for, and the top line of the block is where the eye
        // lands. The label follows it, then the accountability trio.
        val lines = listOfNotNull(
            data.subject?.let { "SN: $it" },
            data.label?.let { "📷 $it" },
            data.dateTime,
            data.location,
            data.by,
        )
        val maxLineW = lines.maxOf {
            if (data.subject != null && it.startsWith("SN: ")) text.measureText(it) * 1.25f
            else text.measureText(it)
        }
        val blockH = lineH * lines.size

        val left = pad
        val bottom = h - pad
        val top = bottom - blockH - pad

        canvas.drawRoundRect(
            left - pad * 0.5f, top - pad * 0.5f,
            left + maxLineW + pad, bottom + pad * 0.25f,
            pad * 0.4f, pad * 0.4f, bg,
        )

        // The serial is drawn a size up, so it survives the photo being viewed
        // as a thumbnail in a grid — which is how these are usually first seen.
        val serialPaint = Paint(text).apply {
            this.textSize = textSize * 1.25f
            color = Color.rgb(255, 214, 102)
        }
        var y = top + textSize
        for (line in lines) {
            val paint = if (data.subject != null && line.startsWith("SN: ")) serialPaint else text
            canvas.drawText(line, left, y, paint)
            y += lineH
        }
        return bmp
    }
}
