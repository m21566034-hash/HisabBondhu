package com.hisabbondhu.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Display formatting only. Stored values are never formatted strings. */
object Fmt {
    private const val BN = "০১২৩৪৫৬৭৮৯"
    val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

    private val months = listOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
    )

    fun bn(s: String): String = buildString { for (c in s) append(if (c in '0'..'9') BN[c - '0'] else c) }
    fun en(s: String): String = buildString {
        for (c in s) { val i = BN.indexOf(c); append(if (i >= 0) ('0' + i) else c) }
    }

    /** Bangladeshi digit grouping: 1234567 -> 12,34,567 */
    fun group(n: Long): String {
        val s = Math.abs(n).toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        val parts = ArrayList<String>()
        var h = head
        while (h.length > 2) { parts.add(0, h.takeLast(2)); h = h.dropLast(2) }
        if (h.isNotEmpty()) parts.add(0, h)
        return parts.joinToString(",") + "," + tail
    }

    /** ৳১২,৩৪৫ or ৳১২,৩৪৫.৫০ ; negative -> -৳... */
    fun money(paisa: Long, symbol: Boolean = true): String {
        val abs = Math.abs(paisa)
        val taka = abs / 100
        val p = abs % 100
        val body = group(taka) + if (p != 0L) "." + p.toString().padStart(2, '0') else ""
        return (if (paisa < 0) "-" else "") + (if (symbol) "৳" else "") + bn(body)
    }

    /** Plain number for CSV / input boxes (English digits). 12345 paisa -> "123.45" */
    fun plain(paisa: Long): String {
        val abs = Math.abs(paisa)
        val p = abs % 100
        return (if (paisa < 0) "-" else "") + (abs / 100) + if (p != 0L) "." + p.toString().padStart(2, '0') else ""
    }

    /** Parse user input (Bangla or English digits, commas allowed) into paisa. null if invalid. */
    fun parseMoney(text: String): Long? = parseScaled(text, 100, 2)

    /** Parse a quantity into milli-units. */
    fun parseQty(text: String): Long? = parseScaled(text, 1000, 3)

    private fun parseScaled(text: String, scale: Long, decimals: Int): Long? {
        val t = en(text).replace(",", "").replace("৳", "").trim()
        if (t.isEmpty()) return null
        val neg = t.startsWith("-")
        val u = t.removePrefix("-")
        val parts = u.split(".")
        if (parts.size > 2 || parts[0].any { it !in '0'..'9' }) return null
        val whole = if (parts[0].isEmpty()) 0L else parts[0].toLongOrNull() ?: return null
        var frac = 0L
        if (parts.size == 2) {
            val f = parts[1]
            if (f.any { it !in '0'..'9' }) return null
            frac = f.padEnd(decimals, '0').take(decimals).toLongOrNull() ?: return null
        }
        val v = whole * scale + frac
        return if (neg) -v else v
    }

    fun qty(milli: Long): String {
        val abs = Math.abs(milli)
        val f = abs % 1000
        val s = (abs / 1000).toString() + if (f != 0L) "." + f.toString().padStart(3, '0').trimEnd('0') else ""
        return bn((if (milli < 0) "-" else "") + s)
    }

    fun date(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(DHAKA).toLocalDate()
        return date(d)
    }

    fun date(d: LocalDate): String = bn("${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}").let {
        // keep month names un-digitised; only digits are converted
        it
    }

    fun dateTime(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(DHAKA)
        return date(z.toLocalDate()) + ", " + bn(z.hour.toString().padStart(2, '0') + ":" + z.minute.toString().padStart(2, '0'))
    }
}
