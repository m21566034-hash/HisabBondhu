package com.hisabbondhu.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Files {
    private fun shareDir(c: Context) = File(c.cacheDir, "share").also { it.mkdirs() }

    /** Writes into the app cache; other apps only ever see a content:// URI from FileProvider. */
    fun cacheFile(c: Context, name: String, bytes: ByteArray): Uri {
        val f = File(shareDir(c), name); f.writeBytes(bytes)
        return FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
    }

    fun cacheBitmap(c: Context, name: String, bmp: Bitmap, png: Boolean): Uri {
        val f = File(shareDir(c), name + if (png) ".png" else ".jpg")
        f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
    }

    /** Removes old temporary files so the cache never grows. */
    fun cleanup(c: Context) { shareDir(c).listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() } }

    fun shareFiles(c: Context, uris: List<Uri>, mime: String, text: String = "", subject: String = "", pkg: String? = null): Boolean {
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = mime
        if (text.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
        if (subject.isNotBlank()) i.putExtra(Intent.EXTRA_SUBJECT, subject)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cd = android.content.ClipData.newRawUri("", uris[0]); uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
        i.clipData = cd
        return try {
            if (pkg != null) { i.setPackage(pkg); c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else c.startActivity(Intent.createChooser(i, "শেয়ার করুন").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }
    }

    /** WhatsApp first (normal, then Business), then the normal share sheet if neither is installed. */
    fun shareToWhatsApp(c: Context, uris: List<Uri>, text: String): String {
        if (shareFiles(c, uris, "image/*", text, pkg = "com.whatsapp")) return "WhatsApp"
        if (shareFiles(c, uris, "image/*", text, pkg = "com.whatsapp.w4b")) return "WhatsApp Business"
        shareFiles(c, uris, "image/*", text)
        return ""
    }

    /** Saves to Pictures/HisabBondhu (API 29+, no permission) or the app's own Pictures folder on older phones. */
    fun saveImage(c: Context, name: String, bmp: Bitmap, png: Boolean): String {
        val mime = if (png) "image/png" else "image/jpeg"
        val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val fn = name + if (png) ".png" else ".jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fn); put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HisabBondhu")
            }
            val u = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return ""
            c.contentResolver.openOutputStream(u)?.use { bmp.compress(fmt, 92, it) }
            return "Pictures/HisabBondhu/$fn"
        }
        val d = File(c.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "HisabBondhu").also { it.mkdirs() }
        File(d, fn).outputStream().use { bmp.compress(fmt, 92, it) }
        return d.absolutePath + "/" + fn
    }

    fun saveToDownloads(c: Context, name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HisabBondhu")
            }
            val u = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return ""
            c.contentResolver.openOutputStream(u)?.use { it.write(bytes) }
            return "Download/HisabBondhu/$name"
        }
        val d = File(c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "HisabBondhu").also { it.mkdirs() }
        File(d, name).writeBytes(bytes)
        return d.absolutePath + "/" + name
    }
}
