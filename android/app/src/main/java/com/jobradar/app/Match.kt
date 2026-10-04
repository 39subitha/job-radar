package com.jobradar.app

/** On-phone match score, so every user gets scores for their own profile. Mirrors backend/score.py. */
object Match {
    private val MEDIUM_TITLES = listOf(
        "manufacturing engineer", "mechanical design", "design engineer", "process engineer", "industrial engineer",
        "production engineer", "methods engineer", "industrialization", "industrialisation", "welding engineer", "cad engineer",
        "konstrukteur", "dessinateur", "ingénieur méthodes", "ingeniero de procesos", "ingeniero de diseño",
    )
    private val SENIOR = listOf("senior manager", "director", "head of", "vice president", "vp", "chief", "principal", "general manager")
    private val SLIGHTLY_SENIOR = listOf("senior", "lead", "sr", "staff")

    private val cache = HashMap<String, Regex>()
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

    fun score(job: Job, p: Profile): Result {
        val t = job.title.lowercase()
        val d = job.desc.lowercase()
        val full = "$t \n $d"
        var pts = 0
        val matched = mutableListOf<String>()

        pts += when {
            hits(t, p.targetTitles).isNotEmpty() -> 50
            hits(t, MEDIUM_TITLES).isNotEmpty() -> 20
            else -> 0
        }
        val key = hits(full, p.keySkills)
        val other = hits(full, p.otherSkills)
        pts += minOf(key.size * 7, 28) + minOf(other.size * 3, 12)
        matched += key + other
        if (hits(full, p.domains).isNotEmpty()) pts += 10

        val yrs = YEARS.findAll(d).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..25 }.minOrNull()
        pts += when {
            yrs == null -> 8
            yrs <= p.years + 2 -> 15
            yrs >= p.years + 5 -> -20
            else -> 0
        }
        if (yrs != null && yrs <= p.years + 2) matched += "$yrs+ yrs ok"
        pts -= when {
            hits(t, SENIOR).isNotEmpty() -> 15
            hits(t, SLIGHTLY_SENIOR).isNotEmpty() -> 5
            else -> 0
        }
        return Result(pts.coerceIn(0, 100), matched.distinctBy { it.lowercase() }.take(8), yrs)
    }

    fun rescore(jobs: List<Job>, p: Profile): List<Job> = jobs.map {
        val r = score(it, p)
        it.copy(score = r.score, matched = r.matched, minYears = r.minYears)
    }

    /** Skills that many good-fit jobs ask for but are missing from the profile. */
    private val KNOWN_SKILLS = listOf(
        "CATIA", "NX", "Unigraphics", "Creo", "SolidWorks", "AutoCAD", "Inventor", "Teamcenter", "Windchill", "Enovia",
        "3DEXPERIENCE", "Delmia", "Process Simulate", "Tecnomatix", "Robcad", "ANSYS", "HyperMesh", "FEA", "GD&T",
        "Tolerance stack-up", "DFM", "DFMEA", "PFMEA", "FMEA", "PPAP", "APQP", "Lean", "Six Sigma", "Kaizen", "5S",
        "Sheet metal", "Welding", "Casting", "Injection molding", "Press tool", "Die design", "Stamping", "CNC", "CAM",
        "Mastercam", "PLC", "Robotics", "Automation", "Metrology", "CMM", "MES", "SAP", "Excel", "Python",
        "ISO 9001", "IATF 16949", "EN 15085", "Hydraulics", "Pneumatics", "BIW", "Composites",
    )

    fun skillGap(jobs: List<Job>, p: Profile, minScore: Int = 40): List<Pair<String, Int>> {
        val mine = (p.keySkills + p.otherSkills).map { it.lowercase() }.toSet()
        val good = jobs.filter { it.score >= minScore }
        return KNOWN_SKILLS.filter { it.lowercase() !in mine }
            .map { s -> s to good.count { has(it.title.lowercase() + " " + it.desc.lowercase(), s) } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(10)
    }
}
