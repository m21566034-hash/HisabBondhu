package com.hisabbondhu.app.render

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class Col(val title: String, val weight: Float, val right: Boolean = false)

/** style: 0 normal, 1 bold, 2 highlighted (final amounts) */
data class SumLine(val label: String, val value: String, val style: Int = 0)

data class DocSpec(
    val bizName: String, val bizLines: List<String>, val logo: Bitmap?,
    val title: String,
    val metaLeft: List<Pair<String, String>>, val metaRight: List<Pair<String, String>>,
    val shortHeader: String,
    val cols: List<Col>, val rows: List<List<String>>,
    val summary: List<SumLine>, val footer: String, val accent: Int, val pageWord: String = "বিল"
)

/**
 * Draws invoices, receipts and statements with plain Android Canvas (no external service).
 * Height is computed from the content; if one image would exceed MAX_H the document is split into
 * sequential images, each repeating the document number and party so every image stands on its own.
 * Text is never shrunk to fit. The totals block is never split across images.
 */
object DocRenderer {
    private const val W = 1080
    private const val M = 48
    private const val CW = W - 2 * M
    private const val MAX_H = 2600
    private const val PAD = 14

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun lay(text: String, p: TextPaint, w: Int, a: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(a).setLineSpacing(0f, 1.12f).setIncludePad(false).build()

    private fun Canvas?.put(l: StaticLayout, x: Float, y: Float) {
        if (this == null) return
        save(); translate(x, y); l.draw(this); restore()
    }

    private class Page(val first: Boolean, val from: Int, val to: Int, val summary: Boolean)

    fun render(s: DocSpec): List<Bitmap> {
        val ink = Color.rgb(25, 30, 28); val gray = Color.rgb(100, 108, 105)
        val body = paint(34f, ink); val bodyB = paint(34f, ink, true); val small = paint(30f, gray)
        val colW = run { val sum = s.cols.sumOf { it.weight.toDouble() }.toFloat(); s.cols.map { (CW * it.weight / sum) } }

        // ---- measure
        val rowLayouts = s.rows.map { r -> r.mapIndexed { i, c ->
            lay(c, body, (colW[i] - 2 * PAD).toInt().coerceAtLeast(40), if (s.cols[i].right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL) } }
        val rowH = rowLayouts.map { r -> (r.maxOfOrNull { it.height } ?: 30) + 2 * 16 }
        val headH = headerFull(null, s, 0, ink, gray)
        val shortH = headerShort(null, s, 0, 1, ink, gray)
        val thH = 34 + 2 * 16
        val sumH = summary(null, s, 0f, ink)
        val footH = footer(null, s, 0f, gray)

        // ---- paginate
        val pages = ArrayList<Page>(); var i = 0; var first = true
        while (true) {
            var y = (if (first) headH else shortH) + thH
            val start = i
            while (i < s.rows.size && y + rowH[i] + footH <= MAX_H) { y += rowH[i]; i++ }
            if (i == start && i < s.rows.size) { i++ }                      // always make progress
            if (i >= s.rows.size) {
                val used = (if (first) headH else shortH) + thH + (start until i).sumOf { rowH[it] }
                if (used + sumH + footH <= MAX_H) { pages.add(Page(first, start, i, true)); break }
                if (i - start > 1) { i--; pages.add(Page(first, start, i, false)); first = false; continue }  // keep a row with the totals
                pages.add(Page(first, start, i, false)); pages.add(Page(false, i, i, true)); break
            } else { pages.add(Page(first, start, i, false)); first = false }
        }

        // ---- draw
        return pages.mapIndexed { pi, pg ->
            val h = (if (pg.first) headH else shortH) + thH + (pg.from until pg.to).sumOf { rowH[it] } + (if (pg.summary) sumH else 0) + footH
            val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(Color.WHITE)
            val label = if (pages.size > 1) "${s.pageWord} ${toBn(pi + 1)}/${toBn(pages.size)}" else null
            var y = if (pg.first) headerFull(c, s, 0, ink, gray, label) else headerShort(c, s, 0, 1, ink, gray, label)
            // table header
            val acc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.accent }
            c.drawRect(M.toFloat(), y.toFloat(), (W - M).toFloat(), (y + thH).toFloat(), acc)
            var x = M.toFloat()
            s.cols.forEachIndexed { ci, col ->
                val l = lay(col.title, paint(32f, Color.WHITE, true), (colW[ci] - 2 * PAD).toInt(), if (col.right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                c.put(l, x + PAD, y + (thH - l.height) / 2f); x += colW[ci]
            }
            y += thH
            val stripe = Paint().apply { color = Color.rgb(244, 247, 246) }
            val line = Paint().apply { color = Color.rgb(222, 228, 225); strokeWidth = 2f }
            for (r in pg.from until pg.to) {
                if (r % 2 == 1) c.drawRect(M.toFloat(), y.toFloat(), (W - M).toFloat(), (y + rowH[r]).toFloat(), stripe)
                x = M.toFloat()
                rowLayouts[r].forEachIndexed { ci, l -> c.put(l, x + PAD, y + 16f); x += colW[ci] }
                y += rowH[r]
                c.drawLine(M.toFloat(), y.toFloat(), (W - M).toFloat(), y.toFloat(), line)
            }
            if (pg.summary) y += summary(c, s, y.toFloat(), ink)
            footer(c, s, y.toFloat(), gray)
            bmp
        }
    }

    private fun toBn(n: Int) = n.toString().map { "০১২৩৪৫৬৭৮৯"[it - '0'] }.joinToString("")

    /** Returns the y after the header. With canvas == null it only measures. */
    private fun headerFull(c: Canvas?, s: DocSpec, y0: Int, ink: Int, gray: Int, label: String? = null): Int {
        var y = y0.toFloat()
        c?.drawRect(0f, 0f, W.toFloat(), 14f, Paint().apply { color = s.accent })
        y += 40f
        val logoSize = 120
        val hasLogo = s.logo != null
        val tx = M + if (hasLogo) logoSize + 24 else 0
        val tw = W - M - tx - 0
        if (hasLogo) c?.drawBitmap(Bitmap.createScaledBitmap(s.logo!!, logoSize, logoSize, true), M.toFloat(), y, null)
        val nameL = lay(s.bizName, paint(46f, ink, true), tw)
        c.put(nameL, tx.toFloat(), y)
        var ty = y + nameL.height + 6
        s.bizLines.filter { it.isNotBlank() }.forEach { ln ->
            val l = lay(ln, paint(30f, gray), tw); c.put(l, tx.toFloat(), ty); ty += l.height + 4
        }
        y = maxOf(ty, if (hasLogo) y + logoSize else 0f) + 22
        c?.drawRect(M.toFloat(), y, (W - M).toFloat(), y + 3f, Paint().apply { color = s.accent })
        y += 18
        val tl = lay(s.title, paint(44f, s.accent, true), 560); c.put(tl, M.toFloat(), y)
        if (label != null) c.put(lay(label, paint(36f, s.accent, true), 360, Layout.Alignment.ALIGN_OPPOSITE), (W - M - 360).toFloat(), y + 4)
        y += tl.height + 14
        val half = (CW - 24) / 2
        fun block(list: List<Pair<String, String>>, x: Float): Float {
            var by = y
            list.forEach { (k, v) ->
                val l = lay("$k: $v", paint(32f, ink), half); c.put(l, x, by); by += l.height + 6
            }
            return by
        }
        val a = block(s.metaLeft, M.toFloat()); val b = block(s.metaRight, (M + half + 24).toFloat())
        y = maxOf(a, b) + 20
        return y.toInt()
    }

    private fun headerShort(c: Canvas?, s: DocSpec, y0: Int, @Suppress("UNUSED_PARAMETER") n: Int, ink: Int, gray: Int, label: String? = null): Int {
        var y = y0.toFloat()
        c?.drawRect(0f, 0f, W.toFloat(), 14f, Paint().apply { color = s.accent })
        y += 34f
        if (label != null) c.put(lay(label, paint(36f, s.accent, true), 260, Layout.Alignment.ALIGN_OPPOSITE), (W - M - 260).toFloat(), y)
        val nl = lay(s.bizName, paint(38f, ink, true), CW - 280); c.put(nl, M.toFloat(), y); y += nl.height + 6
        val sl = lay(s.shortHeader, paint(32f, gray), CW - 280); c.put(sl, M.toFloat(), y); y += sl.height + 22
        return y.toInt()
    }

    private fun summary(c: Canvas?, s: DocSpec, y0: Float, ink: Int): Int {
        var y = y0 + 24f
        val boxX = M + CW * 0.30f
        val w = (W - M - boxX)
        s.summary.forEach { sl ->
            val big = sl.style == 2
            val p = paint(if (big) 38f else 34f, ink, sl.style >= 1)
            val lab = lay(sl.label, p, (w * 0.58f).toInt()); val v = lay(sl.value, p, (w * 0.38f).toInt(), Layout.Alignment.ALIGN_OPPOSITE)
            val h = maxOf(lab.height, v.height) + 20
            if (big) c?.drawRect(boxX - 8, y, W - M.toFloat(), y + h, Paint().apply { color = (s.accent and 0x00FFFFFF) or 0x22000000 })
            c.put(lab, boxX, y + 10); c.put(v, boxX + w * 0.60f, y + 10)
            y += h
            c?.drawLine(boxX - 8, y, (W - M).toFloat(), y, Paint().apply { color = Color.rgb(222, 228, 225); strokeWidth = 2f })
        }
        return (y - y0 + 10).toInt()
    }

    private fun footer(c: Canvas?, s: DocSpec, y0: Float, gray: Int): Int {
        var y = y0 + 22f
        val t = (s.footer.ifBlank { "" } + "\nহিসাববন্ধু দিয়ে তৈরি").trim()
        val l = lay(t, paint(28f, gray), CW, Layout.Alignment.ALIGN_CENTER)
        c.put(l, M.toFloat(), y); y += l.height + 30f
        return (y - y0).toInt()
    }
}
