package com.jobradar.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Builds a clean one-column A4 resume PDF from the profile and opens the share sheet. */
object ResumePdf {
    private const val W = 595
    private const val H = 842
    private const val M = 42f
    private val ACCENT = Color.rgb(31, 78, 121)

    private class Writer(val doc: PdfDocument) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        var y = 0f

        init { newPage() }

        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(30, 30, 30)) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun text(s: String, p: TextPaint, indent: Float = 0f, gapAfter: Float = 2f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            if (s.isBlank()) return
            val width = (W - 2 * M - indent).toInt()
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(align).build()
            if (y + layout.height > H - M) newPage()
            val c = page.canvas
            c.save(); c.translate(M + indent, y); layout.draw(c); c.restore()
            y += layout.height + gapAfter
        }

        fun section(title: String) {
            if (y + 50 > H - M) newPage()
            y += 8
            text(title.uppercase(), paint(11f, true, ACCENT), gapAfter = 3f)
            page.canvas.drawLine(M, y, W - M, y, Paint().apply { color = ACCENT; strokeWidth = 1f })
            y += 6
        }

        fun bullets(lines: String, size: Float = 9.5f) {
            lines.lines().map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotEmpty() }.forEach {
                text("•  $it", paint(size), indent = 6f, gapAfter = 2f)
            }
        }

        fun finish() = doc.finishPage(page)
    }

    fun build(ctx: Context, p: Profile): File {
        val doc = PdfDocument()
        val w = Writer(doc)
        w.text(p.name.ifBlank { "Your Name" }, w.paint(22f, true, ACCENT), gapAfter = 2f)
        w.text(p.headline, w.paint(12f, true), gapAfter = 4f)
        w.text(listOf(p.email, p.phone, p.city, p.linkedin).filter { it.isNotBlank() }.joinToString("   |   "),
            w.paint(9f, color = Color.DKGRAY), gapAfter = 4f)

        if (p.summary.isNotBlank()) { w.section("Professional Summary"); w.text(p.summary, w.paint(9.5f)) }

        if (p.keySkills.isNotEmpty() || p.otherSkills.isNotEmpty()) {
            w.section("Core Skills")
            if (p.keySkills.isNotEmpty()) w.text("Key:  " + p.keySkills.filter { s -> s.all { it.code < 0x0250 } }.joinToString("  ·  "), w.paint(9.5f))
            if (p.otherSkills.isNotEmpty()) w.text("Also:  " + p.otherSkills.joinToString("  ·  "), w.paint(9.5f))
            // search words in other scripts (e.g. Korean) are for matching only, not for the resume
            val domains = p.domains.filter { d -> d.all { it.code < 0x0250 } }
            if (domains.isNotEmpty()) w.text("Domains:  " + domains.joinToString("  ·  "), w.paint(9.5f))
        }

        if (p.experience.isNotEmpty()) {
            w.section("Work Experience")
            p.experience.forEach { e ->
                w.text(listOf(e.role, e.company).filter { it.isNotBlank() }.joinToString("  |  "), w.paint(10.5f, true), gapAfter = 1f)
                w.text(listOf(e.period, e.location).filter { it.isNotBlank() }.joinToString("   ·   "), w.paint(9f, color = Color.DKGRAY), gapAfter = 3f)
                w.bullets(e.points)
                w.y += 5
            }
        }
        if (p.education.isNotBlank()) { w.section("Education"); w.bullets(p.education) }
        if (p.certifications.isNotBlank()) { w.section("Training & Certifications"); w.bullets(p.certifications) }
        if (p.languages.isNotBlank()) { w.section("Languages"); w.bullets(p.languages) }
        val extra = listOfNotNull(
            p.noticePeriod.takeIf { it.isNotBlank() }?.let { "Notice period: $it" },
            p.relocation.takeIf { it.isNotBlank() }?.let { "Relocation: $it" },
        )
        if (extra.isNotEmpty()) { w.section("Additional Information"); w.bullets(extra.joinToString("\n")) }
        w.finish()

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val safe = p.name.ifBlank { "Resume" }.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val f = File(dir, "${safe}_Resume.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Save or send resume"))
    }

    fun open(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(view) }.onFailure { share(ctx, f) }
    }
}
