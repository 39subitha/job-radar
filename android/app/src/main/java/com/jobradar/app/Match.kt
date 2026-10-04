package com.jobradar.app

/** On-phone match score, so every user gets scores for their own profile. Mirrors backend/score.py. */
object Match {
    private val SENIOR = listOf("senior manager", "director", "head of", "vice president", "vp", "chief", "principal", "general manager")
    private val SLIGHTLY_SENIOR = listOf("senior", "lead", "sr", "staff")
    private val JUNIOR = listOf("junior", "jr", "intern", "trainee", "graduate", "apprentice", "entry level")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val HANGUL = Regex("[\\uAC00-\\uD7A3]")
    private fun rx(word: String) = cache.getOrPut(word.lowercase()) {
        // Korean words join with particles (치공구설계), so match them anywhere
        if (HANGUL.containsMatchIn(word)) Regex(Regex.escape(word))
        else Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word.lowercase()) + "(?:s|es)?(?![\\p{L}\\p{N}])")
    }

    fun has(text: String, word: String) = word.isNotBlank() && rx(word).containsMatchIn(text)
    private fun hits(text: String, words: List<String>) = words.filter { has(text, it) }

    private val YEARS = Regex("(\\d{1,2})\\s*(?:\\+|-|–|to)?\\s*(\\d{1,2})?\\s*\\+?\\s*(?:years|yrs|year|ans|jahre)", RegexOption.IGNORE_CASE)

    data class Result(val score: Int, val matched: List<String>, val minYears: Int?)

    /** Words of the wanted job titles that actually say what the job is ("tooling", "fixture", "quality"). */
    fun titleWords(p: Profile): Set<String> = p.targetTitles.flatMap { t ->
        t.lowercase().split(" ", "/", "&", ",", "-", "(", ")").map { it.trim() }
    }.filter { it.length > 1 && it !in Dictionary.GENERIC_TITLE_WORDS }.toSet()

    /** Is this job title worth showing to this person at all? */
    fun titleRelevant(title: String, p: Profile, words: Set<String> = titleWords(p)): Boolean {
        val t = title.lowercase()
        return p.targetTitles.any { has(t, it.lowercase()) } || words.any { has(t, it) }
    }

    fun score(job: Job, p: Profile, words: Set<String> = titleWords(p)): Result {
        val t = job.title.lowercase()
        val d = job.desc.lowercase()
        val full = "$t \n $d"
        var pts = 0
        val matched = mutableListOf<String>()

        // 1. job title vs the titles in the resume
        val overlap = words.count { has(t, it) }
        pts += when {
            p.targetTitles.any { has(t, it.lowercase()) } -> 45
            overlap >= 2 -> 38
            overlap == 1 -> 22
            else -> 0
        }
        // 2. skills from the resume found in the job
        val key = hits(full, p.keySkills)
        val other = hits(full, p.otherSkills.filter { it.split(" ").size <= 3 })
        pts += minOf(key.size * 6, 30) + minOf(other.size * 2, 10)
        matched += key + other
        // 3. same industry
        if (hits(full, p.domains).isNotEmpty()) pts += 5

        // 4. experience asked vs experience in the resume
        val yrs = YEARS.findAll(d).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..25 }.minOrNull()
        pts += when {
            yrs == null -> 8
            yrs <= p.years + 2 -> 15
            yrs >= p.years + 5 -> -20
            else -> 0
        }
        if (yrs != null && yrs <= p.years + 2) matched += "$yrs+ yrs ok"
        // 5. seniority of the title vs experience
        pts -= when {
            hits(t, SENIOR).isNotEmpty() && p.years < 10 -> 15
            hits(t, SLIGHTLY_SENIOR).isNotEmpty() && p.years < 6 -> 5
            hits(t, JUNIOR).isNotEmpty() && p.years >= 6 -> 10
            else -> 0
        }
        return Result(pts.coerceIn(0, 100), matched.distinctBy { it.lowercase() }.take(8), yrs)
    }

    fun rescore(jobs: List<Job>, p: Profile): List<Job> {
        val words = titleWords(p)
        return jobs.map {
        val r = score(it, p, words)
        it.copy(score = r.score, matched = r.matched, minYears = r.minYears)
        }
    }

    /** Skills that many good-fit jobs ask for but are missing from the profile. */
    fun skillGap(jobs: List<Job>, p: Profile, minScore: Int = 40): List<Pair<String, Int>> {
        val mine = (p.keySkills + p.otherSkills).map { it.lowercase() }.toSet()
        val good = jobs.filter { it.score >= minScore }
        return Dictionary.SKILLS.filter { it.lowercase() !in mine }
            .map { s -> s to good.count { has(it.title.lowercase() + " " + it.desc.lowercase(), s) } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(10)
    }
}
