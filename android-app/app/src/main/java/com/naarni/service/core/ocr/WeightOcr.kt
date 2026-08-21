package com.naarni.service.core.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Reads a weight off a photograph of a scale, on the phone.
 *
 * Bundled ML Kit, like the barcode decoder and for the same reason: a gate is
 * exactly where you cannot assume Play Services are installed or that there is
 * a network to fetch a model on first use. An OCR that works everywhere except
 * the plant is not an OCR.
 *
 * What it returns is a *suggestion*. The operator sees the number, checks it
 * against the display in front of them, and corrects it if the reading is off —
 * which is the only honest way to use OCR on a seven-segment display
 * photographed at an angle in bad light. It never blocks and never overwrites a
 * number the operator has already typed.
 */
object WeightOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** What the photo appears to say, or null when nothing weight-shaped was found. */
    data class Reading(
        val kilograms: Double,
        /** The exact text the number was lifted from, shown so a misread is visible. */
        val sourceText: String,
        /**
         * How much to trust it, 0..1.
         *
         * A number sitting next to a "kg" is worth far more than a bare number on
         * a label full of digits, so the unit is what mostly drives this. Below
         * [LOW_CONFIDENCE] the app still shows the reading but says it is unsure.
         */
        val confidence: Float,
    )

    const val LOW_CONFIDENCE = 0.5f

    /**
     * Numbers that look like a weight, with or without a unit.
     *
     * Deliberately tolerant of what a scale display and a phone camera do to
     * each other: thousands separators, a comma decimal mark, and a space
     * between the number and its unit are all normal.
     */
    private val WITH_UNIT = Regex(
        """(\d{1,3}(?:[ ,]\d{3})*(?:[.,]\d{1,3})?|\d+(?:[.,]\d{1,3})?)\s*(kgs?|kilo(?:gram)?s?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_NUMBER = Regex("""(?<![\d.,])(\d{1,6}(?:[.,]\d{1,3})?)(?![\d.,])""")

    /** Read [file]; null when the image yields nothing weight-shaped. */
    suspend fun read(context: Context, file: File): Reading? =
        runCatching { recognise(InputImage.fromFilePath(context, Uri.fromFile(file))) }.getOrNull()

    private suspend fun recognise(image: InputImage): Reading? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(parse(it.text)) }
                // A failed recognition is not an error the operator should see —
                // the weight box is simply not pre-filled and they type it.
                .addOnFailureListener { cont.resume(null) }
        }

    /** Pull the most weight-like number out of recognised text. Visible for testing. */
    fun parse(text: String): Reading? {
        if (text.isBlank()) return null

        // A number with "kg" beside it is almost certainly the reading. When a
        // display shows several, the largest wins: scales commonly show tare and
        // net together, and the gross figure is the one being recorded.
        WITH_UNIT.findAll(text)
            .mapNotNull { match ->
                toKilograms(match.groupValues[1])?.let { it to match.value.trim() }
            }
            .maxByOrNull { it.first }
            ?.let { (value, source) -> return Reading(value, source, confidence = 0.95f) }

        // No unit anywhere: fall back to the largest plausible bare number, and
        // say plainly that the reading is a guess.
        val candidates = BARE_NUMBER.findAll(text)
            .mapNotNull { match -> toKilograms(match.groupValues[1])?.let { it to match.value.trim() } }
            .filter { it.first in PLAUSIBLE }
            .toList()
        if (candidates.isEmpty()) return null

        val (value, source) = candidates.maxByOrNull { it.first }!!
        // One number on the whole image is far more likely to be the reading than
        // one of nine numbers on a printed label.
        val confidence = if (candidates.size == 1) 0.45f else 0.25f
        return Reading(value, source, confidence)
    }

    /** Weights a gate could plausibly record. Excludes years, part numbers, prices. */
    private val PLAUSIBLE = 0.05..50_000.0

    private fun toKilograms(raw: String): Double? {
        val cleaned = raw.replace(" ", "").let { candidate ->
            when {
                // "1,234.5" — comma is a thousands separator.
                candidate.contains(',') && candidate.contains('.') -> candidate.replace(",", "")
                // "1,234" is a thousand; "12,5" is twelve and a half. Three digits
                // after a single comma is the separator, anything else is a decimal.
                candidate.contains(',') ->
                    if (candidate.substringAfterLast(',').length == 3) candidate.replace(",", "")
                    else candidate.replace(',', '.')
                else -> candidate
            }
        }
        return cleaned.toDoubleOrNull()?.takeIf { it in PLAUSIBLE }
    }
}
