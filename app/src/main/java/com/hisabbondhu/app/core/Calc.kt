package com.hisabbondhu.app.core

/**
 * Pure financial logic (no Android imports) so it can be unit-tested on the JVM.
 * Money = Long paisa (1 taka = 100 paisa). Quantity = Long "milli-units" (1 unit = 1000).
 * No floating point is used anywhere in money maths.
 */
object Calc {
    const val MILLI = 1000L

    /** Integer division rounded half away from zero. */
    fun divRound(n: Long, d: Long): Long {
        require(d > 0) { "divisor must be positive" }
        val q = n / d
        val r = n % d
        return if (n >= 0) { if (2 * r >= d) q + 1 else q } else { if (2 * -r >= d) q - 1 else q }
    }

    /** qty (milli) x unit price (paisa) -> paisa. */
    fun lineGross(qtyMilli: Long, unitPrice: Long): Long = divRound(qtyMilli * unitPrice, MILLI)

    data class Line(val qtyMilli: Long, val unitPrice: Long, val discount: Long = 0)

    fun lineNet(l: Line): Long = (lineGross(l.qtyMilli, l.unitPrice) - l.discount).coerceAtLeast(0)

    data class Totals(
        val subtotal: Long,        // sum of qty x price, before any discount
        val itemDiscount: Long,
        val invoiceDiscount: Long,
        val extra: Long,           // additional charge
        val total: Long
    )

    fun totals(lines: List<Line>, invoiceDiscount: Long, extra: Long): Totals {
        val gross = lines.sumOf { lineGross(it.qtyMilli, it.unitPrice) }
        val itemDisc = lines.sumOf { minOf(it.discount, lineGross(it.qtyMilli, it.unitPrice)) }
        val base = gross - itemDisc
        val inv = invoiceDiscount.coerceIn(0, base)
        return Totals(gross, itemDisc, inv, extra.coerceAtLeast(0), base - inv + extra.coerceAtLeast(0))
    }

    /** Weighted average unit cost after adding stock. If stock <= 0 the new cost wins. */
    fun weightedAvg(stockMilli: Long, avgCost: Long, addQtyMilli: Long, addUnitCost: Long): Long {
        if (stockMilli <= 0) return addUnitCost
        val total = stockMilli + addQtyMilli
        if (total <= 0) return addUnitCost
        return divRound(stockMilli * avgCost + addQtyMilli * addUnitCost, total)
    }

    /** Cost of a quantity at a unit cost. */
    fun cost(qtyMilli: Long, unitCost: Long): Long = divRound(qtyMilli * unitCost, MILLI)

    /**
     * Landed unit cost per purchase line: invoice discount reduces and extra costs increase the
     * cost, shared in proportion to each line's net value. The remainder goes to the last line so
     * the allocated total always equals (extra - discount) exactly.
     */
    fun landedUnitCosts(lines: List<Line>, invoiceDiscount: Long, extra: Long): List<Long> {
        if (lines.isEmpty()) return emptyList()
        val nets = lines.map { lineNet(it) }
        val base = nets.sum()
        val adj = extra - invoiceDiscount.coerceIn(0, base)
        var left = adj
        return lines.mapIndexed { i, l ->
            val share = when {
                i == lines.lastIndex -> left
                base > 0 -> divRound(adj * nets[i], base)
                else -> 0
            }
            left -= share
            if (l.qtyMilli <= 0) 0 else divRound((nets[i] + share) * MILLI, l.qtyMilli)
        }
    }

    /** Refund value for returning [retMilli] of a line (net of line discount and its share of the invoice discount). */
    fun returnAmount(lineNet: Long, lineQtyMilli: Long, retMilli: Long, invoiceDiscount: Long, netBase: Long): Long {
        if (lineQtyMilli <= 0) return 0
        val share = divRound(lineNet * retMilli, lineQtyMilli)
        val disc = if (netBase > 0) divRound(invoiceDiscount * share, netBase) else 0
        return share - disc
    }

    /** Party balance. Positive = they owe us (customer) / we owe them (supplier). Negative = advance/credit. */
    fun balance(opening: Long, invoiced: Long, paid: Long, returns: Long): Long = opening + invoiced - paid - returns

    data class Pnl(
        val netSales: Long, val cogs: Long, val grossProfit: Long,
        val expenses: Long, val adjustments: Long, val operatingProfit: Long
    )

    /**
     * netSales = sales - sale returns
     * cogs = cost of goods sold - cost of returned goods
     * adjustments = signed stock loss/gain (damage, corrections); negative reduces profit
     * Purchases are NOT an expense - they reach profit only through COGS.
     */
    fun pnl(sales: Long, saleReturns: Long, cogsSold: Long, cogsReturned: Long, expenses: Long, adjustments: Long): Pnl {
        val net = sales - saleReturns
        val cogs = cogsSold - cogsReturned
        val gross = net - cogs
        return Pnl(net, cogs, gross, expenses, adjustments, gross - expenses + adjustments)
    }
}
