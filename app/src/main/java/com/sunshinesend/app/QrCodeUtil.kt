package com.sunshinesend.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeUtil {

    fun createQRCodeBitmap(
        content: String,
        size: Int = 512,
        characterSet: String = "UTF-8",
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        margin: Int = 1,
        colorBlack: Int = Color.BLACK,
        colorWhite: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to characterSet,
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                EncodeHintType.MARGIN to margin
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) colorBlack else colorWhite)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
