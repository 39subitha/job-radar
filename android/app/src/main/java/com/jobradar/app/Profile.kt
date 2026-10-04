package com.jobradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Experience(
    val role: String = "",
    val company: String = "",
    val period: String = "",
    val location: String = "",
    val points: String = "",   // one achievement per line
)

/** The user's resume + job preferences. Lives only on this phone. */
data class Profile(
    // resume
    val name: String = "",
    val headline: String = "",
    val email: String = "",
    val phone: String = "",
    val city: String = "",
    val linkedin: String = "",
    val summary: String = "",
    val years: Int = 0,
    val keySkills: List<String> = emptyList(),
    val otherSkills: List<String> = emptyList(),
    val experience: List<Experience> = emptyList(),
    val education: String = "",       // one per line
    val certifications: String = "",  // one per line
    val languages: String = "",       // one per line
    // matching
    val targetTitles: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val preferredCountries: List<String> = emptyList(),
    // quick-apply answers
    val noticePeriod: String = "",
    val currentCtc: String = "",
    val expectedCtc: String = "",
    val relocation: String = "",
    val visaStatus: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("headline", headline); put("email", email); put("phone", phone); put("city", city)
        put("linkedin", linkedin); put("summary", summary); put("years", years)
        put("keySkills", JSONArray(keySkills)); put("otherSkills", JSONArray(otherSkills))
        put("experience", JSONArray(experience.map {
            JSONObject().put("role", it.role).put("company", it.company).put("period", it.period)
                .put("location", it.location).put("points", it.points)
        }))
        put("education", education); put("certifications", certifications); put("languages", languages)
        put("targetTitles", JSONArray(targetTitles)); put("domains", JSONArray(domains))
        put("preferredCountries", JSONArray(preferredCountries))
        put("noticePeriod", noticePeriod); put("currentCtc", currentCtc); put("expectedCtc", expectedCtc)
        put("relocation", relocation); put("visaStatus", visaStatus)
    }

    companion object {
        private fun JSONObject.list(k: String) = optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

        fun from(o: JSONObject) = Profile(
            name = o.optString("name"), headline = o.optString("headline"), email = o.optString("email"),
            phone = o.optString("phone"), city = o.optString("city"), linkedin = o.optString("linkedin"),
            summary = o.optString("summary"), years = o.optInt("years"),
            keySkills = o.list("keySkills"), otherSkills = o.list("otherSkills"),
            experience = o.optJSONArray("experience")?.let { a ->
                List(a.length()) {
                    val e = a.getJSONObject(it)
                    Experience(e.optString("role"), e.optString("company"), e.optString("period"), e.optString("location"), e.optString("points"))
                }
            } ?: emptyList(),
            education = o.optString("education"), certifications = o.optString("certifications"), languages = o.optString("languages"),
            targetTitles = o.list("targetTitles"), domains = o.list("domains"), preferredCountries = o.list("preferredCountries"),
            noticePeriod = o.optString("noticePeriod"), currentCtc = o.optString("currentCtc"), expectedCtc = o.optString("expectedCtc"),
            relocation = o.optString("relocation"), visaStatus = o.optString("visaStatus"),
        )

        /** Starting point for a tooling / fixture design engineer. No personal details. */
        val DEFAULT = Profile(
            headline = "Tooling & Fixture Design Engineer",
            years = 3,
            keySkills = listOf("CATIA", "Fixture", "Jig", "Welding fixture", "Tooling", "Tool design", "Bogie", "Rolling stock"),
            otherSkills = listOf("Enovia", "PLM", "GD&T", "SolidWorks", "AutoCAD", "Gauge", "Ergonomics", "Cost reduction", "2D drawing", "3D model", "Welding"),
            targetTitles = listOf("Fixture", "Jig", "Tooling", "Tool design", "Tool designer", "Tool engineer", "Outillage", "Utillaje", "Vorrichtung"),
            domains = listOf("Rail", "Railway", "Rolling stock", "Metro", "Locomotive", "Bogie", "Train", "Automotive", "Aerospace"),
            preferredCountries = listOf("India"),
        )
    }
}

class ProfileStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(): Profile = sp.getString("p", null)?.let { runCatching { Profile.from(JSONObject(it)) }.getOrNull() } ?: Profile.DEFAULT

    fun save(p: Profile) = sp.edit().putString("p", p.toJson().toString()).apply()
}

fun splitList(text: String): List<String> = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
