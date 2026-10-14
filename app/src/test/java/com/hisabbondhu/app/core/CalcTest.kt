package com.hisabbondhu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalcTest {
    @Test fun lineGrossRoundsHalfUp() {
        assertEquals(1250L, Calc.lineGross(2500, 500))       // 2.5 x 5.00
        assertEquals(333L, Calc.lineGross(1000, 333))
        assertEquals(1L, Calc.lineGross(1, 500))             // 0.001 x 5.00 = 0.005 paisa -> rounds to 1? (0.5 -> 1)
    }

    @Test fun invoiceTotals() {
        val lines = listOf(Calc.Line(10_000, 12_000, 5_000), Calc.Line(3_000, 25_050))
        val t = Calc.totals(lines, invoiceDiscount = 10_000, extra = 2_000)
        assertEquals(120_000 + 75_150, t.subtotal)
        assertEquals(5_000, t.itemDiscount)
        assertEquals(10_000, t.invoiceDiscount)
        assertEquals(195_150 - 5_000 - 10_000 + 2_000, t.total)
    }

    @Test fun discountCannotExceedBase() {
        val t = Calc.totals(listOf(Calc.Line(1000, 10_000)), invoiceDiscount = 99_999, extra = 0)
        assertEquals(0, t.total)
    }

    @Test fun weightedAverage() {
        // 10 @ 100 + 10 @ 200 -> 150
        assertEquals(15_000, Calc.weightedAvg(10_000, 10_000, 10_000, 20_000))
        // empty stock -> new cost
        assertEquals(20_000, Calc.weightedAvg(0, 10_000, 5_000, 20_000))
        // negative stock -> new cost
        assertEquals(20_000, Calc.weightedAvg(-1_000, 10_000, 5_000, 20_000))
    }

    @Test fun landedCostAllocationIsExact() {
        val lines = listOf(Calc.Line(10_000, 10_000), Calc.Line(10_000, 30_000))
        // discount 1000.00, extra 3000.00 -> net +2000.00 shared 1:3 by value
        val units = Calc.landedUnitCosts(lines, invoiceDiscount = 100_000, extra = 300_000)
        val totalCost = units.indices.sumOf { Calc.cost(lines[it].qtyMilli, units[it]) }
        assertEquals(100_000 + 300_000 + 200_000, totalCost)   // 4000 + 2000 adjustment
    }

    @Test fun returnAmountShareOfInvoiceDiscount() {
        // line net 900, qty 10, return 4, invoice discount 100 over base 1000
        val a = Calc.returnAmount(90_000, 10_000, 4_000, 10_000, 100_000)
        assertEquals(36_000 - 3_600, a)
    }

    @Test fun balanceAndAdvance() {
        assertEquals(7_000, Calc.balance(1_000, 10_000, 3_000, 1_000))
        assertEquals(-2_000, Calc.balance(0, 5_000, 7_000, 0))      // advance credit is explicit negative
    }

    @Test fun profitDoesNotCountPurchasesAsExpense() {
        val p = Calc.pnl(sales = 100_000, saleReturns = 10_000, cogsSold = 60_000, cogsReturned = 6_000,
            expenses = 8_000, adjustments = -2_000)
        assertEquals(90_000, p.netSales)
        assertEquals(54_000, p.cogs)
        assertEquals(36_000, p.grossProfit)
        assertEquals(36_000 - 8_000 - 2_000, p.operatingProfit)
    }

    @Test fun moneyFormatBangla() {
        assertEquals("৳১২,৩৪,৫৬৭", Fmt.money(123_456_700))
        assertEquals("৳১,২৩৪.৫০", Fmt.money(123_450))
        assertEquals("-৳৫০", Fmt.money(-5_000))
        assertEquals("৳০", Fmt.money(0))
        assertEquals("৳৯৯৯", Fmt.money(99_900))
    }

    @Test fun parseMoneyAcceptsBanglaDigits() {
        assertEquals(123_450L, Fmt.parseMoney("১২৩৪.৫"))
        assertEquals(123_450L, Fmt.parseMoney("১,২৩৪.৫"))
        assertEquals(100L, Fmt.parseMoney("1"))
        assertEquals(5L, Fmt.parseMoney("0.05"))
        assertNull(Fmt.parseMoney("abc"))
        assertNull(Fmt.parseMoney("1.2.3"))
        assertNull(Fmt.parseMoney(""))
    }

    @Test fun parseQty() {
        assertEquals(2_500L, Fmt.parseQty("2.5"))
        assertEquals(12_000L, Fmt.parseQty("১২"))
    }

    private val z = ZoneId.of("Asia/Dhaka")
    private val today = LocalDate.of(2026, 10, 13)

    @Test fun rangeTodayIncludesLastMillisecond() {
        val r = DateRanges.of(Preset.TODAY, today, z)
        assertEquals(24L * 3600 * 1000, r.to - r.from)
        val lateNight = today.atTime(23, 59, 59).atZone(z).toInstant().toEpochMilli()
        assert(lateNight >= r.from && lateNight < r.to)
    }

    @Test fun rangeLast7IsSevenCalendarDays() {
        val r = DateRanges.of(Preset.LAST7, today, z)
        assertEquals(7L * 24 * 3600 * 1000, r.to - r.from)
    }

    @Test fun lastMonthAndThisMonth() {
        val last = DateRanges.of(Preset.LAST_MONTH, today, z)
        val this_ = DateRanges.of(Preset.THIS_MONTH, today, z)
        assertEquals(last.to, this_.from)                       // no gap, no overlap
        assertEquals(30L * 24 * 3600 * 1000, last.to - last.from) // September has 30 days
    }

    @Test fun customRangeSwapsReversedDates() {
        val r = DateRanges.of(Preset.CUSTOM, today, z, today, today.minusDays(2))
        assertEquals(3L * 24 * 3600 * 1000, r.to - r.from)
    }
}
