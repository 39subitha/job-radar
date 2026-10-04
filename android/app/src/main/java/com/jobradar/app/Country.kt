package com.jobradar.app

/** Country from a location text. Short version of backend/country.py for jobs found on the phone. */
object Country {
    private val NAMES = linkedMapOf(
        "India" to listOf("india", "bengaluru", "bangalore", "chennai", "hyderabad", "pune", "mumbai", "delhi", "noida", "gurgaon",
            "gurugram", "kolkata", "ahmedabad", "vadodara", "coimbatore", "hosur", "kochi", "nagpur", "sri city", "savli"),
        "United States" to listOf("united states", "usa", "u.s."), "United Kingdom" to listOf("united kingdom", "england", "scotland", "wales"),
        "South Korea" to listOf("korea", "seoul", "changwon", "ulsan", "incheon", "busan", "daejeon", "suwon", "yongin", "cheonan"),
        "Germany" to listOf("germany", "deutschland"), "France" to listOf("france"), "Spain" to listOf("spain", "españa"),
        "Italy" to listOf("italy", "italia"), "Canada" to listOf("canada"), "Mexico" to listOf("mexico", "méxico"),
        "China" to listOf("china", "shanghai", "beijing", "suzhou"), "Japan" to listOf("japan", "tokyo"),
        "Singapore" to listOf("singapore"), "Malaysia" to listOf("malaysia", "penang"), "Australia" to listOf("australia"),
        "United Arab Emirates" to listOf("united arab emirates", "uae", "dubai", "abu dhabi"), "Saudi Arabia" to listOf("saudi"),
        "Poland" to listOf("poland"), "Netherlands" to listOf("netherlands"), "Belgium" to listOf("belgium"),
        "Switzerland" to listOf("switzerland"), "Austria" to listOf("austria"), "Sweden" to listOf("sweden"),
        "Czech Republic" to listOf("czech"), "Hungary" to listOf("hungary"), "Romania" to listOf("romania"),
        "Portugal" to listOf("portugal"), "Brazil" to listOf("brazil", "brasil"), "Vietnam" to listOf("vietnam"),
        "Thailand" to listOf("thailand", "bangkok"), "Egypt" to listOf("egypt", "cairo"), "Morocco" to listOf("morocco"),
    )
    private val ISO2 = mapOf("US" to "United States", "GB" to "United Kingdom", "UK" to "United Kingdom", "DE" to "Germany",
        "FR" to "France", "ES" to "Spain", "IT" to "Italy", "MX" to "Mexico", "CN" to "China", "JP" to "Japan", "KR" to "South Korea",
        "SG" to "Singapore", "AU" to "Australia", "PL" to "Poland", "NL" to "Netherlands", "BE" to "Belgium", "CH" to "Switzerland",
        "AT" to "Austria", "SE" to "Sweden", "CZ" to "Czech Republic", "BR" to "Brazil", "AE" to "United Arab Emirates")
    private val US_STATES = setOf("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA",
        "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
        "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY")

    fun of(location: String, fallback: String = ""): String {
        val loc = location.trim()
        val low = loc.lowercase()
        if (Regex("^(US|USA)\\b").containsMatchIn(loc) || low.startsWith("united states")) return "United States"
        NAMES.forEach { (c, words) -> if (words.any { Regex("(?<![a-z])" + Regex.escape(it) + "(?![a-z])").containsMatchIn(low) }) return c }
        val last = loc.split(",").map { it.trim() }.lastOrNull { it.isNotEmpty() && it.none(Char::isDigit) }.orEmpty()
        if (Regex("[A-Z]{2}").matches(last)) {
            if (last == "DE") return "Germany"
            if (last in US_STATES) return "United States"
            ISO2[last]?.let { return it }
        }
        if (low.contains("remote")) return "Remote"
        return fallback.ifBlank { "Other" }
    }
}
