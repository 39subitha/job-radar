package com.jobradar.app

import java.io.InputStream
import java.time.YearMonth
import java.util.zip.ZipInputStream

/** Reads any resume (Word / PDF / text) into a Profile, using word lists and patterns (no AI, works offline). */
object ResumeParser {

    // ------------------------------------------------------------ text extraction

    fun docxText(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return xml
                        .replace(Regex("</w:p>"), "\n")
                        .replace(Regex("<w:tab/>|<w:tab [^>]*/>"), "\t")
                        .replace(Regex("<w:br[^>]*/>"), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'")
                }
                e = zip.nextEntry
            }
        }
        error("Not a Word document")
    }

    // ------------------------------------------------------------ parsing

    private const val MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"
    private const val DATE = "(?:$MONTH\\s*'?\\d{2,4}|\\d{1,2}[/.-]\\d{2,4}|\\d{4})"
    private val RANGE = Regex("($DATE)\\s*(?:–|—|-|to|till|until)\\s*(present|current|now|till date|date|ongoing|$DATE)", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val PHONE = Regex("\\+?\\d[\\d\\s()-]{8,}\\d")
    private val LINKEDIN = Regex("(?:https?://)?(?:www\\.)?linkedin\\.com/\\S+", RegexOption.IGNORE_CASE)
    private val YEARS_TEXT = Regex("(\\d{1,2})\\+?\\s*(?:years|yrs)", RegexOption.IGNORE_CASE)
    private val ROLE = Regex(
        "((?:[A-Z][\\w&/+.-]*\\s+){0,4}(?:" + Dictionary.ROLE_NOUNS.joinToString("|") + "))\\b"
    )
    private val TITLE_ADJECTIVES = setOf("results", "experienced", "skilled", "dedicated", "motivated", "passionate", "seasoned",
        "dynamic", "certified", "aspiring", "creative", "proactive", "highly", "detail", "accomplished", "a", "an", "as")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun ym(s: String, isEnd: Boolean): YearMonth? {
        val t = s.lowercase().trim()
        if (t in listOf("present", "current", "now", "till date", "date", "ongoing")) return YearMonth.now()
        Regex("($MONTH)\\s*'?(\\d{2,4})").find(t)?.let { m ->
            val mon = MONTHS.indexOf(m.groupValues[1].take(3)) + 1
            var y = m.groupValues[2].toInt(); if (y < 100) y += 2000
            return runCatching { YearMonth.of(y, mon) }.getOrNull()
        }
        Regex("(\\d{1,2})[/.-](\\d{2,4})").find(t)?.let { m ->
            var y = m.groupValues[2].toInt(); if (y < 100) y += 2000
            return runCatching { YearMonth.of(y, m.groupValues[1].toInt()) }.getOrNull()
        }
        Regex("(\\d{4})").find(t)?.let { return YearMonth.of(it.groupValues[1].toInt(), if (isEnd) 12 else 1) }
        return null
    }

    private fun norm(line: String) = line.lowercase().replace(Regex("[^a-z& ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun sectionOf(line: String): String? {
        val n = norm(line)
        if (n.isEmpty() || line.length > 60 || n.split(" ").size > 6) return null
        for ((sec, words) in Dictionary.SECTION_HEADERS) {
            if (words.any { n == it || n.startsWith("$it ") || n.endsWith(" $it") }) return sec
        }
        return null
    }

    private fun has(text: String, word: String) = Match.has(text, word)

    fun parse(raw: String): Profile {
        val lines = raw.replace("\r", "").lines().map { it.replace('\t', ' ').replace(Regex(" {2,}"), "  ").trim() }.filter { it.isNotEmpty() }
        val low = raw.lowercase()

        // split into sections
        val sections = linkedMapOf<String, MutableList<String>>()
        var cur = "header"
        for (l in lines) {
            val s = sectionOf(l)
            if (s != null) { cur = s; sections.getOrPut(cur) { mutableListOf() }; continue }
            sections.getOrPut(cur) { mutableListOf() } += l
        }
        val header = sections["header"].orEmpty()

        // contacts
        val email = EMAIL.find(raw)?.value.orEmpty()
        val phone = PHONE.findAll(raw).map { it.value.trim() }.firstOrNull { it.count(Char::isDigit) in 10..14 }.orEmpty()
        val linkedin = LINKEDIN.find(raw)?.value.orEmpty()
        val name = header.firstOrNull { l ->
            !EMAIL.containsMatchIn(l) && l.count(Char::isDigit) == 0 && l.split(" ").size in 1..5 &&
                l.all { it.isLetter() || it == ' ' || it == '.' } && Dictionary.ROLE_NOUNS.none { r -> l.contains(r, true) }
        }?.split(" ")?.joinToString(" ") { w -> if (w.length > 1 && w == w.uppercase()) w.lowercase().replaceFirstChar(Char::uppercase) else w }.orEmpty()
        val contactLine = header.firstOrNull { EMAIL.containsMatchIn(it) || PHONE.containsMatchIn(it) }.orEmpty()
        val city = contactLine.split("|", "·", "•").map { it.trim() }.firstOrNull { p ->
            p.isNotEmpty() && !EMAIL.containsMatchIn(p) && !PHONE.containsMatchIn(p) && !p.contains("linkedin", true) &&
                !p.contains("open to", true) && p.count(Char::isDigit) == 0 && p.length < 40
        }.orEmpty()
        val headline = (header.firstOrNull { l -> l != name && l != contactLine && Dictionary.ROLE_NOUNS.any { has(l.lowercase(), it.lowercase()) } && l.length < 120 }.orEmpty())
            .replace(Regex("\\s{2,}"), " ")

        // experience entries: every line with a date range in the experience section
        val expLines = sections["experience"] ?: lines
        val entries = mutableListOf<Experience>()
        val ranges = mutableListOf<Pair<YearMonth, YearMonth>>()
        var i = 0
        while (i < expLines.size) {
            val l = expLines[i]
            val m = RANGE.find(l)
            if (m == null || Dictionary.DEGREE_WORDS.any { l.contains(it, true) }) { i++; continue }
            val start = ym(m.groupValues[1], false); val end = ym(m.groupValues[2], true)
            if (start != null && end != null && !end.isBefore(start)) ranges += start to end
            var text = (l.substring(0, m.range.first) + " " + l.substring(m.range.last + 1)).trim().trim('|', '-', '–', ',', '(', ')').trim()
            if (text.isEmpty() && i > 0) text = expLines[i - 1]
            val parts = text.split("|", " at ", " – ", " - ", ",").map { it.trim() }.filter { it.isNotEmpty() }
            val role = parts.firstOrNull { p -> Dictionary.ROLE_NOUNS.any { has(p.lowercase(), it.lowercase()) } } ?: parts.firstOrNull().orEmpty()
            val company = parts.filter { it != role }.joinToString(", ")
            var loc = ""
            val points = mutableListOf<String>()
            var j = i + 1
            while (j < expLines.size && RANGE.find(expLines[j]) == null) {
                val p = expLines[j]
                if (p.startsWith("📍") || (points.isEmpty() && loc.isEmpty() && p.length < 80 && p.contains(",") && !p.endsWith("."))) {
                    loc = p.removePrefix("📍").trim().substringBefore("(").trim()
                } else points += p.trimStart('•', '-', '*', '·', ' ')
                j++
            }
            entries += Experience(role, company, m.value.trim(), loc, points.joinToString("\n"))
            i = j
        }
        // years: merge overlapping periods
        var months = 0
        ranges.sortedBy { it.first }.fold(null as Pair<YearMonth, YearMonth>?) { acc, r ->
            when {
                acc == null -> r
                !r.first.isAfter(acc.second) -> acc.first to maxOf(acc.second, r.second)
                else -> { months += (acc.second.year - acc.first.year) * 12 + acc.second.monthValue - acc.first.monthValue + 1; r }
            }
        }?.let { months += (it.second.year - it.first.year) * 12 + it.second.monthValue - it.first.monthValue + 1 }
        val years = if (months > 0) (months + 6) / 12 else YEARS_TEXT.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // skills: dictionary words found, ranked by how often they appear; skills section counts double
        val skillText = sections["skills"].orEmpty().joinToString("\n").lowercase()
        val found = Dictionary.SKILLS.map { s ->
            val c = Regex(Regex.escape(s.lowercase())).findAll(low).count()
            s to (c + if (has(skillText, s.lowercase())) 2 else 0)
        }.filter { (s, c) -> c > 0 && has(low, s.lowercase()) }.sortedByDescending { it.second }.map { it.first }
            .distinctBy { it.lowercase() }
        // plus anything listed in the skills section that is not in the dictionary
        val listed = sections["skills"].orEmpty().flatMap { l ->
            l.substringAfter(":", l).split("·", "|", ",", ";", "•", "  ").map { it.trim().trimEnd('.') }
        }.filter { it.length in 2..30 && it.split(" ").size <= 4 && found.none { f -> f.equals(it, true) } && !it.endsWith(":") }
        val key = found.take(10)
        val other = (found.drop(10) + listed).distinctBy { it.lowercase() }.take(30)

        // job titles to search for
        val titles = (listOf(headline) + entries.map { it.role } + lines.take(40))
            .flatMap { l -> ROLE.findAll(l.replace("&", "and")).map { it.groupValues[1].trim() } }
            .map { it.replace(" and ", " & ").trim() }
            // drop leading adjectives like "Results-driven", "Experienced"
            .map { t -> t.split(" ").dropWhile { w -> "-" in w || w.lowercase() in TITLE_ADJECTIVES }.joinToString(" ") }
            .filter { t -> t.split(" ").size >= 2 && t.split(" ").any { w -> w.lowercase() !in Dictionary.GENERIC_TITLE_WORDS } }
            .distinctBy { it.lowercase() }.take(6)

        val domains = Dictionary.DOMAINS.filter { has(low, it.lowercase()) }.take(8)
        val country = Dictionary.COUNTRIES.firstOrNull { has(city.lowercase() + " " + contactLine.lowercase(), it.lowercase()) }
            ?.let { if (it == "Korea") "South Korea" else if (it == "USA") "United States" else if (it == "UK") "United Kingdom" else it }

        fun sec(k: String) = sections[k].orEmpty().joinToString("\n")
        val education = sections["education"]?.joinToString("\n")
            ?: lines.filter { l -> Dictionary.DEGREE_WORDS.any { l.contains(it, true) } }.joinToString("\n")
        return Profile(
            name = name, headline = headline.ifBlank { titles.firstOrNull().orEmpty() }, email = email, phone = phone,
            city = city, linkedin = linkedin, summary = sec("summary").take(1200), years = years,
            keySkills = key, otherSkills = other, experience = entries.take(8),
            education = education, certifications = sec("certifications"), languages = sec("languages"),
            targetTitles = titles, domains = domains, preferredCountries = listOfNotNull(country),
            noticePeriod = Regex("(\\d+\\s*days?)\\s*notice|notice period[:\\s]*(\\d+\\s*days?)", RegexOption.IGNORE_CASE).find(raw)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty(),
            relocation = if (low.contains("open to relocation") || low.contains("willing to relocate")) "Yes – open to relocation" else "",
        )
    }
}
