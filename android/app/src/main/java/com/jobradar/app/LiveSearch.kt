package com.jobradar.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Searches company career sites directly from the phone, for the job titles in the selected resume.
 * Covers the common systems (Workday, SuccessFactors, SmartRecruiters, Greenhouse, Lever, Eightfold, Oracle, Capgemini).
 */
class LiveSearch(private val ctx: Context) {
    private val companiesUrl = BuildConfig.DATA_URL.replace("data/jobs.json", "backend/companies.json")
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    private val DETAILS_PER_COMPANY = 6

    private fun cacheFile(profileId: String) = File(ctx.filesDir, "live_$profileId.json")

    data class Cached(val at: String, val jobs: List<Job>)

    fun cached(profileId: String): Cached? = runCatching {
        val o = JSONObject(cacheFile(profileId).readText())
        val a = o.getJSONArray("jobs")
        Cached(o.getString("at"), List(a.length()) { Job.from(a.getJSONObject(it)) })
    }.getOrNull()

    // ------------------------------------------------------------ http

    private fun http(url: String, body: String? = null, headers: Map<String, String> = emptyMap(), timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clean(html: String) = html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    private class Raw(val title: String, val url: String, val location: String, val posted: String = "", var desc: String = "", val detail: (() -> String)? = null)

    // ------------------------------------------------------------ one function per career-site system

    private fun workday(p: JSONObject, q: String): List<Raw> {
        val base = "https://${p.getString("tenant")}.wd${p.getInt("wdn")}.myworkdayjobs.com"
        val api = "$base/wday/cxs/${p.getString("tenant")}/${p.getString("site")}"
        val body = JSONObject().put("appliedFacets", JSONObject()).put("limit", 20).put("offset", 0).put("searchText", q).toString()
        val a = JSONObject(http("$api/jobs", body, mapOf("Accept" to "application/json"))).optJSONArray("jobPostings") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it) }.filter { it.has("externalPath") }.map { j ->
            val path = j.getString("externalPath")
            Raw(j.optString("title"), "$base/en-US/${p.getString("site")}$path", j.optString("locationsText"), j.optString("postedOn")) {
                JSONObject(http(api + path, headers = mapOf("Accept" to "application/json")))
                    .optJSONObject("jobPostingInfo")?.optString("jobDescription").orEmpty()
            }
        }
    }

    private fun successfactors(p: JSONObject, q: String): List<Raw> {
        val kw = if (" " in q) "\"$q\"" else q
        val xml = http("https://${p.getString("host")}/services/rss/job/?locale=${p.getString("locale")}&keywords=${enc("($kw)")}")
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            fun tag(t: String) = Regex("<$t>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</$t>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
            var title = clean(tag("title"))
            val loc = Regex("\\s*\\(([^()]*)\\)\\s*$").find(title)
            if (loc != null) title = title.substring(0, loc.range.first)
            Raw(title, tag("link").substringBefore("?"), loc?.groupValues?.get(1).orEmpty(), tag("pubDate"), clean(tag("description")))
        }.toList()
    }

    private fun smartrecruiters(p: JSONObject, q: String): List<Raw> {
        val co = p.getString("company")
        val a = JSONObject(http("https://api.smartrecruiters.com/v1/companies/$co/postings?q=${enc(q)}&limit=100")).optJSONArray("content") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it) }.map { j ->
            val l = j.optJSONObject("location")
            val loc = listOf(l?.optString("city"), l?.optString("region"), l?.optString("country")?.uppercase()).filter { !it.isNullOrBlank() }.joinToString(", ")
            Raw(j.optString("name"), "https://jobs.smartrecruiters.com/$co/${j.optString("id")}", loc, j.optString("releasedDate")) {
                val s = JSONObject(http("https://api.smartrecruiters.com/v1/companies/$co/postings/${j.optString("id")}"))
                    .optJSONObject("jobAd")?.optJSONObject("sections")
                s?.keys()?.asSequence()?.mapNotNull { k -> s.optJSONObject(k)?.optString("text") }?.joinToString("\n").orEmpty()
            }
        }
    }

    private fun greenhouse(p: JSONObject): List<Raw> {
        val token = p.getString("token")
        val a = JSONObject(http("https://boards-api.greenhouse.io/v1/boards/$token/jobs", timeout = 30000)).getJSONArray("jobs")
        return List(a.length()) { a.getJSONObject(it) }.map { j ->
            Raw(j.optString("title"), j.optString("absolute_url"), j.optJSONObject("location")?.optString("name").orEmpty(), j.optString("updated_at")) {
                JSONObject(http("https://boards-api.greenhouse.io/v1/boards/$token/jobs/${j.optLong("id")}")).optString("content")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            }
        }
    }

    private fun lever(p: JSONObject): List<Raw> {
        val a = JSONArray(http("https://api.lever.co/v0/postings/${p.getString("company")}?mode=json", timeout = 30000))
        return List(a.length()) { a.getJSONObject(it) }.map { j ->
            Raw(j.optString("text"), j.optString("hostedUrl"), j.optJSONObject("categories")?.optString("location").orEmpty(), "", j.optString("descriptionPlain"))
        }
    }

    private fun eightfold(p: JSONObject, q: String): List<Raw> {
        val host = p.getString("host")
        val a = JSONObject(http("https://$host/api/pcsx/search?domain=${p.getString("domain")}&query=${enc(q)}&location=&start=0"))
            .optJSONObject("data")?.optJSONArray("positions") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it) }.map { j ->
            val locs = j.optJSONArray("locations")?.let { l -> List(l.length()) { l.getString(it) } }.orEmpty()
            Raw(j.optString("name"), "https://$host${j.optString("positionUrl")}", locs.joinToString("; "))
        }
    }

    private fun oracle(p: JSONObject, q: String): List<Raw> {
        val url = "https://${p.getString("host")}/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true" +
            "&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=${p.getString("site")}," +
            "keyword=%22${enc(q).replace("+", "%20")}%22,limit=25,offset=0,sortBy=POSTING_DATES_DESC"
        val items = JSONObject(http(url)).optJSONArray("items")?.optJSONObject(0)?.optJSONArray("requisitionList") ?: return emptyList()
        return List(items.length()) { items.getJSONObject(it) }.map { j ->
            Raw(j.optString("Title"), "${p.getString("public_base")}/job/${j.optString("Id")}", j.optString("PrimaryLocation"),
                j.optString("PostedDate"), j.optString("ShortDescriptionStr"))
        }
    }

    private fun capgemini(q: String): List<Raw> {
        val a = JSONObject(http("https://cg-jobstream-api.azurewebsites.net/api/job-search?page=1&size=50&search=${enc(q)}")).optJSONArray("data") ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it) }.map { j ->
            Raw(j.optString("title"), j.optString("apply_job_url"), j.optString("location"), j.optString("updated_at"),
                j.optString("description_stripped").ifBlank { j.optString("description") })
        }
    }

    // ------------------------------------------------------------ search

    private fun companies(): List<JSONObject> {
        val f = File(ctx.filesDir, "companies.json")
        val text = runCatching { http(companiesUrl).also { f.writeText(it) } }.getOrElse { f.readText() }
        val a = JSONArray(text)
        return List(a.length()) { a.getJSONObject(it) }.filter { it.optString("status") == "ok" && it.optString("type") in SUPPORTED }
    }

    /** Search terms: the most specific titles from the resume (max 3). */
    fun queries(p: Profile): List<String> = p.targetTitles.map { it.trim() }.filter { it.isNotEmpty() }
        .sortedByDescending { t -> t.split(" ").count { it.lowercase() !in Dictionary.GENERIC_TITLE_WORDS } }
        .distinctBy { it.lowercase() }.take(3)

    suspend fun search(profileId: String, p: Profile, onProgress: (done: Int, total: Int, found: Int) -> Unit): List<Job> = withContext(Dispatchers.IO) {
        val qs = queries(p)
        if (qs.isEmpty()) return@withContext emptyList()
        val words = Match.titleWords(p)
        val list = companies()
        val done = AtomicInteger(0); val found = AtomicInteger(0)
        val gate = Semaphore(8)
        val old = cached(profileId)?.jobs?.associateBy { it.id }.orEmpty()
        val now = OffsetDateTime.now().toString()
        onProgress(0, list.size, 0)
        val results = coroutineScope {
            list.map { c ->
                async {
                    gate.withPermit {
                        val jobs = runCatching { searchCompany(c, qs, p, words) }.getOrDefault(emptyList())
                        found.addAndGet(jobs.size)
                        onProgress(done.incrementAndGet(), list.size, found.get())
                        jobs.map { r ->
                            val id = sha(r.url.ifBlank { c.getString("name") + r.title + r.location })
                            val country = Country.of(r.location, c.optString("country"))
                            Job(id, r.title.trim(), c.getString("name"), r.location.trim(), r.url, r.posted, clean(r.desc).take(6000),
                                0, emptyList(), null, old[id]?.firstSeen ?: now, country == "India", country)
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
        val o = JSONObject().put("at", now).put("jobs", JSONArray(results.map { it.toJson() }))
        cacheFile(profileId).writeText(o.toString())
        results
    }

    private fun searchCompany(c: JSONObject, qs: List<String>, p: Profile, words: Set<String>): List<Raw> {
        val prm = c.optJSONObject("params") ?: JSONObject()
        val raw = when (val type = c.getString("type")) {
            "greenhouse" -> greenhouse(prm)
            "lever" -> lever(prm)
            else -> qs.flatMap { q ->
                runCatching {
                    when (type) {
                        "workday" -> workday(prm, q)
                        "successfactors" -> successfactors(prm, q)
                        "smartrecruiters" -> smartrecruiters(prm, q)
                        "eightfold" -> eightfold(prm, q)
                        "oracle" -> oracle(prm, q)
                        "capgemini" -> capgemini(q)
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }
        }
        val relevant = raw.filter { it.title.isNotBlank() && it.url.isNotBlank() && Match.titleRelevant(it.title, p, words) }.distinctBy { it.url }
        // fetch full descriptions for the best-looking titles only
        relevant.filter { it.desc.isBlank() && it.detail != null }
            .sortedByDescending { r -> words.count { Match.has(r.title.lowercase(), it) } }
            .take(DETAILS_PER_COMPANY)
            .forEach { r -> r.desc = runCatching { r.detail!!.invoke() }.getOrDefault("") }
        return relevant
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    companion object {
        val SUPPORTED = setOf("workday", "successfactors", "smartrecruiters", "greenhouse", "lever", "eightfold", "oracle", "capgemini")
    }
}
