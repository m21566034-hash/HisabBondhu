package com.hisabbondhu.app.export

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.hisabbondhu.app.Graph
import com.hisabbondhu.app.core.DateRange
import com.hisabbondhu.app.core.DateRanges
import com.hisabbondhu.app.core.Fmt
import com.hisabbondhu.app.core.Preset
import kotlinx.coroutines.flow.first
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Local notifications only (no push server, no cost). Each category can be switched off in Settings. */
object Notifier {
    val categories = listOf("low_stock" to "কম স্টক সতর্কতা", "cust_due" to "কাস্টমার বকেয়া", "sup_due" to "সাপ্লায়ার পাওনা",
        "daily" to "দৈনিক বিক্রয় সারাংশ", "backup" to "ব্যাকআপ রিমাইন্ডার")

    private fun prefs(c: Context) = c.getSharedPreferences("notif", Context.MODE_PRIVATE)
    fun enabled(c: Context, k: String) = prefs(c).getBoolean(k, false)
    fun setEnabled(c: Context, k: String, v: Boolean) {
        prefs(c).edit().putBoolean(k, v).apply()
        if (categories.any { enabled(c, it.first) }) schedule(c) else cancelAll(c)
    }
    fun markBackup(c: Context) = prefs(c).edit().putLong("lastBackup", System.currentTimeMillis()).apply()

    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26)
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("hisab", "হিসাববন্ধু রিমাইন্ডার", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Runs once a day at about 20:00 Bangladesh time. */
    fun schedule(c: Context) {
        val now = ZonedDateTime.now(Fmt.DHAKA)
        var at = now.toLocalDate().atTime(20, 0).atZone(Fmt.DHAKA)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(java.time.Duration.between(now, at).toMinutes(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("daily-notify", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
    fun cancelAll(c: Context) { WorkManager.getInstance(c).cancelUniqueWork("daily-notify") }

    fun post(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(c, "hisab").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
    }
}

class DailyWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val repo = Graph.repoOrNull(c) ?: return Result.success()
        val d = repo.dao
        if (Notifier.enabled(c, "low_stock")) {
            val low = d.productRowsOnce().filter { !it.p.archived && it.p.lowMilli > 0 && it.stock <= it.p.lowMilli }
            if (low.isNotEmpty()) Notifier.post(c, 1, "কম স্টক", Fmt.bn(low.size.toString()) + " টি পণ্যের স্টক কম: " + low.take(5).joinToString { it.p.name })
        }
        if (Notifier.enabled(c, "cust_due")) {
            val due = d.balances("C").first().filter { it.bal > 0 }.sumOf { it.bal }
            if (due > 0) Notifier.post(c, 2, "কাস্টমার বকেয়া", "মোট কাস্টমার বকেয়া " + Fmt.money(due))
        }
        if (Notifier.enabled(c, "sup_due")) {
            val due = d.balances("S").first().filter { it.bal > 0 }.sumOf { it.bal }
            if (due > 0) Notifier.post(c, 3, "সাপ্লায়ার পাওনা", "সাপ্লায়ারদের মোট পাওনা " + Fmt.money(due))
        }
        if (Notifier.enabled(c, "daily")) {
            val r: DateRange = DateRanges.of(Preset.TODAY)
            val tx = d.txnsBetween("SALE", r.from, r.to)
            Notifier.post(c, 4, "আজকের বিক্রয়", Fmt.bn(tx.size.toString()) + " টি বিল, মোট " + Fmt.money(tx.sumOf { it.total }))
        }
        if (Notifier.enabled(c, "backup")) {
            val last = c.getSharedPreferences("notif", Context.MODE_PRIVATE).getLong("lastBackup", 0)
            if (System.currentTimeMillis() - last > 7 * 24 * 3600_000L) Notifier.post(c, 5, "ব্যাকআপ নিন", "৭ দিনের বেশি হলো ব্যাকআপ নেওয়া হয়নি। সেটিংস থেকে ব্যাকআপ নিন")
        }
        return Result.success()
    }
}
