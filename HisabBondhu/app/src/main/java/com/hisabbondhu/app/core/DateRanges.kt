package com.hisabbondhu.app.core

import java.time.LocalDate
import java.time.ZoneId

/** Half-open range [from, to) in epoch millis, so the last day is never cut off. */
data class DateRange(val from: Long, val to: Long, val label: String)

enum class Preset(val label: String) {
    TODAY("আজ"), LAST7("গত ৭ দিন"), LAST15("গত ১৫ দিন"), LAST30("গত ৩০ দিন"),
    LAST6M("গত ৬ মাস"), LAST1Y("গত ১ বছর"), THIS_MONTH("এই মাস"), LAST_MONTH("গত মাস"), CUSTOM("কাস্টম তারিখ")
}

object DateRanges {
    private fun startOf(d: LocalDate, z: ZoneId) = d.atStartOfDay(z).toInstant().toEpochMilli()

    /** [firstDay, lastDay] inclusive calendar days -> half-open millis range. */
    fun days(firstDay: LocalDate, lastDay: LocalDate, z: ZoneId = Fmt.DHAKA, label: String? = null): DateRange {
        val a = if (lastDay.isBefore(firstDay)) lastDay else firstDay
        val b = if (lastDay.isBefore(firstDay)) firstDay else lastDay
        return DateRange(
            startOf(a, z), startOf(b.plusDays(1), z),
            label ?: if (a == b) Fmt.date(a) else Fmt.date(a) + " – " + Fmt.date(b)
        )
    }

    fun of(p: Preset, today: LocalDate = LocalDate.now(Fmt.DHAKA), z: ZoneId = Fmt.DHAKA,
           customFrom: LocalDate? = null, customTo: LocalDate? = null): DateRange = when (p) {
        Preset.TODAY -> days(today, today, z)
        Preset.LAST7 -> days(today.minusDays(6), today, z)
        Preset.LAST15 -> days(today.minusDays(14), today, z)
        Preset.LAST30 -> days(today.minusDays(29), today, z)
        Preset.LAST6M -> days(today.minusMonths(6).plusDays(1), today, z)
        Preset.LAST1Y -> days(today.minusYears(1).plusDays(1), today, z)
        Preset.THIS_MONTH -> days(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1).minusDays(1), z)
        Preset.LAST_MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            days(first, first.plusMonths(1).minusDays(1), z)
        }
        Preset.CUSTOM -> days(customFrom ?: today, customTo ?: today, z)
    }
}
