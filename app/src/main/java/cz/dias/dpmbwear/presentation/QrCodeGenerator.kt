package cz.dias.dpmbwear.presentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {

    /**
     * Vykreslí QR kód.
     *
     * @param size hrana bitmapy v pixelech
     * @param marginModules klidová zóna v modulech. Norma říká 4, tady stačí 1 –
     *   zbytek bílého okraje kreslí až UI vrstva kolem obrázku.
     */
    fun generateQrCode(
        content: String,
        size: Int = 512,
        marginModules: Int = 1,
    ): ImageBitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf<EncodeHintType, Any>(
                // Nízká korekce = méně modulů = větší čtverečky na malém
                // displeji hodinek. Vyšší úroveň by kód zbytečně zahustila.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to marginModules,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height

            // setPixel v cyklu znamenalo 262 144 volání přes JNI a viditelné
            // seknutí. Jedno setPixels na hotové pole je řádově rychlejší.
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
                .asImageBitmap()
        } catch (e: Exception) {
            // Nejčastěji WriterException, když se obsah nevejde do QR verze 40.
            e.printStackTrace()
            null
        }
    }
}
