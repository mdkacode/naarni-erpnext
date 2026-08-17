package com.naarni.service.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Getting a photo down to a size a depot's uplink can actually move.
 *
 * A modern handset produces a 12-megapixel JPEG of three to six megabytes. On
 * the connections these phones actually have that is a minute of uploading per
 * photo, and a technician documenting a breakdown sends six. Nothing in that
 * file is doing any work: it is displayed in a bubble a few hundred pixels
 * wide, and read at full screen at most.
 *
 * Two rules keep this from quietly destroying evidence:
 *
 * * **Only ever downwards.** A photo already smaller than the target is left
 *   exactly as it is. Re-encoding a small JPEG makes it worse and larger.
 * * **Only formats where it is safe.** GIFs would lose their animation and PNGs
 *   their transparency, so neither is converted; a large PNG is resized but
 *   stays a PNG.
 */
object ImageScaler {

    /**
     * Longest edge, in pixels, after scaling.
     *
     * 1600 is enough to read a part number or a number plate off the result at
     * full screen, which is the actual job these photos do — they are evidence
     * on a job card, not wall prints. It is also roughly what WhatsApp settled
     * on, which matters more than it sounds: it is the quality bar people
     * already judge a photo against.
     */
    const val MAX_EDGE = 1600

    /** JPEG quality. 85 is above the point where compression artefacts start
     *  showing up on the flat painted surfaces that fill a photo of a bus. */
    const val QUALITY = 85

    /**
     * Below this, a photo is left alone whatever its dimensions.
     *
     * Re-encoding costs CPU and a generation of quality to save bytes that were
     * never the problem. Screenshots of a defect report land here and pass
     * straight through.
     */
    private const val SKIP_UNDER_BYTES = 350L * 1024

    /**
     * Scale [file] in place if it is worth doing. Returns the file either way.
     *
     * Deliberately total: a photo that cannot be decoded — a format the platform
     * does not know, a truncated download — is passed through untouched rather
     * than rejected. The upload is the user's actual intent, and failing it
     * because an optimisation did not apply would be the wrong trade.
     */
    fun scaleInPlace(file: File, contentType: String): File {
        if (!canScale(contentType)) return file
        if (file.length() < SKIP_UNDER_BYTES) return file

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return file

            val rotation = rotationOf(file)
            if (longest <= MAX_EDGE && rotation == 0) return file

            val decoded = decodeSampled(file, longest) ?: return file
            val scaled = fit(decoded, MAX_EDGE)
            val upright = rotate(scaled, rotation)

            val png = contentType.equals("image/png", ignoreCase = true)
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val tmp = File(file.parentFile, "${file.name}.scaled")
            tmp.outputStream().use { upright.compress(format, QUALITY, it) }
            upright.recycle()

            // Only adopt the result if it actually helped. Re-encoding can grow a
            // file — a PNG of a photograph reliably does — and shipping a bigger
            // one than we started with would be the opposite of the point.
            if (tmp.length() in 1 until file.length()) tmp.copyTo(file, overwrite = true)
            tmp.delete()
            file
        }.getOrDefault(file)
    }

    /**
     * JPEG and PNG only.
     *
     * GIF is excluded so animation survives. WebP and HEIC are excluded for the
     * same reason in a different disguise: this re-encodes to JPEG, which would
     * flatten an animated WebP to its first frame and turn a transparent one's
     * alpha black — and, worse, the result would still be queued with the
     * source's `image/webp` type and `.webp` name, so the server would store
     * and serve JPEG bytes under a mime type that does not match them. They are
     * left alone rather than converted, since the upload path has no way to
     * revise the content type after the fact.
     */
    fun canScale(contentType: String): Boolean = contentType.lowercase() in SCALABLE

    private val SCALABLE = setOf("image/jpeg", "image/png")

    /**
     * Decode at a power-of-two reduction first.
     *
     * This is the part that matters for stability, not just for speed. Decoding
     * a 12-megapixel photo straight to a bitmap costs about 48 MB of heap, and
     * doing that on a 2 GB handset while the camera preview is still bound is
     * how an app gets killed mid-capture. `inSampleSize` decodes at a fraction
     * of the pixels, so the peak is a few megabytes.
     */
    private fun decodeSampled(file: File, longest: Int): Bitmap? {
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** Exact scale to the target longest edge, preserving aspect ratio. */
    fun fit(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (out !== source) source.recycle()
        return out
    }

    /**
     * Bake the EXIF rotation into the pixels.
     *
     * Re-encoding drops the original EXIF, so a photo taken in portrait would
     * arrive on its side for every viewer that was relying on that tag — which
     * is all of them. Rotating the pixels means the file needs no tag to be the
     * right way up.
     */
    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (out !== source) source.recycle()
        return out
    }

    private fun rotationOf(file: File): Int = runCatching {
        when (
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
