package com.netkit.local.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR contains the complete secret HTTPS bearer URL: hidden unless owner explicitly reveals it.
 * Never save it to public media storage, analytics, or logs. Regenerate after session revocation.
 */
@Composable
internal fun ShareQrPanel(sessionUrl: String) {
    var revealed by remember(sessionUrl) { mutableStateOf(false) }
    OutlinedButton(onClick = { revealed = !revealed }) {
        Text(if (revealed) "إخفاء رمز QR" else "إظهار QR للمشاركة")
    }
    if (revealed) {
        val bitmap = remember(sessionUrl) {
            runCatching {
                val dimension = 256
                val matrix = QRCodeWriter().encode(
                    sessionUrl,
                    BarcodeFormat.QR_CODE,
                    dimension,
                    dimension,
                    mapOf(
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                        EncodeHintType.MARGIN to 2
                    )
                )
                val argb = IntArray(dimension * dimension) { index ->
                    if (matrix[index % dimension, index / dimension]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888).apply {
                    setPixels(argb, 0, dimension, 0, 0, dimension, dimension)
                }
            }.getOrNull()
        }
        if (bitmap == null) {
            Text("تعذّر إنشاء رمز QR لهذه الجلسة.")
        } else {
            Box(
                Modifier.background(Color.White, RoundedCornerShape(18.dp)).padding(12.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "رمز QR لرابط جلسة NetKit المحلية",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(232.dp)
                )
            }
            Text("هذا الرمز يمنح الوصول إلى الملفات المشتركة. اعرضه فقط على أجهزتك الموثوقة، وأوقف المشاركة بعد الانتهاء.")
        }
    }
}
