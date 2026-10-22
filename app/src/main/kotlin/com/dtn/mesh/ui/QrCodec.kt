package com.dtn.mesh.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encode/decode on plain pixel arrays — no Android types, so it's JVM unit-tested.
 *
 * Encoding produces the minimal matrix (one pixel per module): the UI scales it up with
 * nearest-neighbour filtering, so the image is crisp and generation is ~1000 pixels of work
 * instead of the 313,600 `setPixel` calls the old 560×560 render did on the UI thread.
 */
object QrCodec {
    const val DARK: Int = 0xFF000000.toInt()
    const val LIGHT: Int = 0xFFFFFFFF.toInt()

    fun encode(text: String, marginModules: Int = 2): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.MARGIN to marginModules,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /** Row-major ARGB pixels for [matrix], each module drawn as a [scale]×[scale] block. */
    fun toPixels(matrix: BitMatrix, scale: Int = 1): IntArray {
        require(scale >= 1)
        val w = matrix.width * scale
        val h = matrix.height * scale
        val px = IntArray(w * h)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                val c = if (matrix[x, y]) DARK else LIGHT
                for (dy in 0 until scale) {
                    val start = (y * scale + dy) * w + x * scale
                    java.util.Arrays.fill(px, start, start + scale, c)
                }
            }
        }
        return px
    }

    /**
     * Find a QR code in an image. Tries two binarizers on the image and on its inverse (light-on-
     * dark screenshots), with TRY_HARDER — photos and screenshots of QR codes vary a lot more than
     * a live camera frame.
     */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        val sources: List<LuminanceSource> = listOf(source, source.invert())
        for (src in sources) {
            for (binarizer in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
                try {
                    return reader.decodeWithState(BinaryBitmap(binarizer)).text
                } catch (_: ReaderException) {
                    // try the next combination
                } finally {
                    reader.reset()
                }
            }
        }
        // Last resort for perfectly cropped codes (e.g. a shared QR PNG, or one tiny enough that
        // the finder-pattern detector can't lock on): read the modules directly.
        val pure = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.PURE_BARCODE to true,
                ),
            )
        }
        for (src in sources) {
            try {
                return pure.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(src))).text
            } catch (_: ReaderException) {
                // not a pure barcode either
            } finally {
                pure.reset()
            }
        }
        return null
    }
}
