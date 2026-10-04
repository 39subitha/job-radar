package com.jobradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.OffsetDateTime

data class Job(
    val id: String,
    val title: String,
    val company: String,
    val location: String,
    val url: String,
    val posted: String,
    val desc: String,
    val score: Int,
    val matched: List<String>,
    val minYears: Int?,
    val firstSeen: String,
    val india: Boolean,
    val country: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("company", company).put("location", location)
        .put("url", url).put("posted", posted).put("desc", desc).put("score", score)
        .put("matched", JSONArray(matched)).put("min_years", minYears ?: JSONObject.NULL)
        .put("first_seen", firstSeen).put("india", india).put("country", country)

    companion object {
        fun from(o: JSONObject) = Job(
            id = o.getString("id"),
            title = o.optString("title"),
            company = o.optString("company"),
            location = o.optString("location"),
            url = o.optString("url"),
            posted = o.optString("posted"),
            desc = o.optString("desc"),
            score = o.optInt("score"),
            matched = o.optJSONArray("matched")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            minYears = if (o.isNull("min_years")) null else o.optInt("min_years"),
            firstSeen = o.optString("first_seen"),
            india = o.optBoolean("india"),
            country = o.optString("country").ifBlank { if (o.optBoolean("india")) "India" else "Other" },
        )
    }
}

data class Company(val name: String, val industry: String, val status: String, val jobs: Int, val note: String, val careers: String)

data class Feed(val generated: String, val jobs: List<Job>, val companies: List<Company>)

/** Downloads the daily job list and keeps a copy for offline use. */
class Repo(private val ctx: Context) {
    private val cache = File(ctx.filesDir, "jobs.json")

    fun cached(): Feed? = runCatching { parse(cache.readText()) }.getOrNull()

    fun download(): Feed {
        val conn = URL(BuildConfig.DATA_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) error("Server returned ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val feed = parse(text)
            cache.writeText(text)
            return feed
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Feed {
        val o = JSONObject(text)
        val jobs = o.getJSONArray("jobs").let { a -> List(a.length()) { Job.from(a.getJSONObject(it)) } }
        val comps = o.optJSONArray("companies")?.let { a ->
            List(a.length()) {
                val c = a.getJSONObject(it)
                Company(c.optString("name"), c.optString("industry"), c.optString("status"), c.optInt("jobs"),
                    c.optString("note"), c.optString("careers"))
            }
        } ?: emptyList()
        return Feed(o.optString("generated"), jobs, comps)
    }
}

data class Tracked(val status: Status, val job: Job, val at: Long)

enum class Status(val label: String) { SAVED("Saved"), APPLIED("Applied"), INTERVIEW("Interview"), OFFER("Offer"), REJECTED("Rejected") }

/** Things the user decides on this phone: followed companies, application status, what was already seen. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)

    var followed: Set<String>
        get() = sp.getStringSet("followed", null) ?: emptySet()
        set(v) = sp.edit().putStringSet("followed", v).apply()

    /** id -> status + snapshot of the job, so tracked jobs stay even after the posting closes */
    fun tracked(): Map<String, Tracked> {
        val o = JSONObject(sp.getString("tracked", "{}")!!)
        return o.keys().asSequence().mapNotNull { id ->
            runCatching {
                val e = o.getJSONObject(id)
                id to Tracked(Status.valueOf(e.getString("status")), Job.from(e.getJSONObject("job")), e.optLong("at"))
            }.getOrNull()
        }.toMap()
    }

    fun setStatus(job: Job, status: Status?) {
        val o = JSONObject(sp.getString("tracked", "{}")!!)
        if (status == null) o.remove(job.id)
        else o.put(job.id, JSONObject().put("status", status.name).put("job", job.toJson()).put("at", System.currentTimeMillis()))
        sp.edit().putString("tracked", o.toString()).apply()
    }

    /** Last time the user opened the app before this session, to mark "new" jobs. */
    var lastVisit: String
        get() = sp.getString("lastVisit", "") ?: ""
        set(v) = sp.edit().putString("lastVisit", v).apply()
}

fun ago(iso: String): String = runCatching {
    val d = Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now())
    when {
        d.toHours() < 1 -> "just now"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        d.toDays() < 30 -> "${d.toDays()}d ago"
        else -> "${d.toDays() / 30}mo ago"
    }
}.getOrDefault("")

fun isNewerThan(iso: String, ref: String): Boolean = runCatching {
    ref.isEmpty() || OffsetDateTime.parse(iso).isAfter(OffsetDateTime.parse(ref))
}.getOrDefault(false)

fun isWithinHours(iso: String, h: Long): Boolean = runCatching {
    Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).toHours() < h
}.getOrDefault(false)
