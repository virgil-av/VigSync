package com.vigsync.feature.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Color

@Serializable
data class PairingData(
    val brokerUrl: String,
    val port: Int,
    val sharedKey: String,
    val topicPrefix: String,
    val deviceName: String,
    val deviceId: String
)

class PairingManager {

    fun generatePairingJson(data: PairingData): String {
        return Json.encodeToString(data)
    }

    fun parsePairingJson(json: String): PairingData? {
        return try {
            Json.decodeFromString<PairingData>(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
