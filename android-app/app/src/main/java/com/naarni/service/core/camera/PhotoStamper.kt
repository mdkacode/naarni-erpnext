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
        /**
         * A single word painted across the top of the picture — INWARD or OUTWARD.
         *
         * Deliberately not another line in the corner block. A gate photograph is
         * looked at months later, often printed, sometimes by somebody settling an
         * argument about whether a part arrived or left; the direction has to be
         * readable at a glance and at thumbnail size, which the small text is not.
         */
        val banner: String? = null,
    )

    /** Build stamp text from raw inputs at capture time. */
    fun build(
        location: Location?,
        userFullName: String,
        userRole: String,
        whenMillis: Long = System.currentTimeMillis(),
        label: String? = null,
        banner: String? = null,
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
            banner = banner?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(),
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
        //
        // The label wraps rather than being cut. Callers used to trim it to forty
        // characters before handing it over, because a long check title ran off
        // the right edge of the photograph — so the stamp on a photo of
        // "Check the busbar torque and mark the bolt head after torquing" said
        // "Check the busbar torque and mark the bolt he". Wrapping is what makes
        // the whole title fit; trimming only made the loss quieter.
        val available = w - pad * 3
        val lines = buildList {
            data.subject?.let { add("SN: $it") }
            data.label?.let { addAll(wrapToWidth("📷 $it", text, available)) }
            add(data.dateTime)
            add(data.location)
            add(data.by)
        }
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

        data.banner?.let { drawBanner(canvas, it, w, textSize, pad) }
        return bmp
    }

    /**
     * The direction, across the top of the picture.
     *
     * Sized off the image width and drawn on its own bar so it stays legible
     * when the photo is a 72dp thumbnail in a grid — which is how these are
     * almost always seen first. Colour carries the meaning a second time, never
     * the only time: the word itself is the signal, so it survives printing in
     * black and white.
     */
    private fun drawBanner(canvas: Canvas, banner: String, w: Int, baseTextSize: Float, pad: Float) {
        val size = (w * 0.075f).coerceAtLeast(46f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            letterSpacing = 0.12f
            setShadowLayer(size * 0.10f, 0f, 0f, Color.BLACK)
        }
        val tint = when (banner) {
            "INWARD" -> Color.argb(210, 21, 101, 42)
            "OUTWARD" -> Color.argb(210, 21, 63, 122)
            else -> Color.argb(190, 0, 0, 0)
        }
        val textW = paint.measureText(banner)
        val barH = size * 1.6f
        canvas.drawRect(0f, 0f, w.toFloat(), barH, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint })
        canvas.drawText(banner, (w - textW) / 2f, barH * 0.5f + size * 0.36f, paint)
    }

    /**
     * Break one line into as many as it takes to fit, on word boundaries.
     *
     * Falls back to a hard split for a single "word" longer than the image is
     * wide — a serial with no spaces in it, typically — because dropping the tail
     * of *that* is the one thing this whole stamp exists to prevent.
     */
    private fun wrapToWidth(line: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0f || paint.measureText(line) <= maxWidth) return listOf(line)

        val out = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString()
                current = StringBuilder()
            }
        }

        for (word in line.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                paint.measureText(candidate) <= maxWidth -> current = StringBuilder(candidate)
                current.isNotEmpty() -> {
                    flush()
                    current = StringBuilder(word)
                }
                else -> {
                    // One unbreakable word wider than the image.
                    var rest = word
                    while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                        var cut = rest.length
                        while (cut > 1 && paint.measureText(rest.substring(0, cut)) > maxWidth) cut--
                        out += rest.substring(0, cut)
                        rest = rest.substring(cut)
                    }
                    current = StringBuilder(rest)
                }
            }
        }
        flush()
        return out
    }
}
