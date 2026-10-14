package com.hisabbondhu.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Small optional images (logo, product photo, receipt photo) are shrunk and stored as text inside the record,
 *  so no Firebase Storage is needed. Typical size is 10-40 KB. */
object Img {
    fun decode(b64: String): Bitmap? = if (b64.isBlank()) null else runCatching {
        val b = Base64.decode(b64, Base64.NO_WRAP); BitmapFactory.decodeByteArray(b, 0, b.size)
    }.getOrNull()

    fun encode(c: Context, uri: Uri, maxSide: Int, quality: Int = 80): String? = runCatching {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        var sample = 1
        while (opt.outWidth / sample > maxSide * 2 || opt.outHeight / sample > maxSide * 2) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val sc = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, quality, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
