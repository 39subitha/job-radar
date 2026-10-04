package com.jobradar.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        // remember the previous visit for "new since last time" dots, then stamp this visit
        val previousVisit = prefs.lastVisit
        prefs.lastVisit = OffsetDateTime.now().toString()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF9CC3E6), secondary = Color(0xFFFFC000))
            else lightColorScheme(primary = Color(0xFF1F4E79), secondary = Color(0xFFB07D00))
            MaterialTheme(colorScheme = scheme) { App(Repo(this), prefs, ProfileStore(this), previousVisit) }
        }
    }
}

private enum class Tab(val label: String) { JOBS("Jobs"), COMPANIES("Companies"), TRACKER("Tracker"), PROFILE("Profile") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(repo: Repo, prefs: Prefs, store: ProfileStore, previousVisit: String) {
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(repo.cached()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.JOBS) }
    var open by remember { mutableStateOf<Job?>(null) }
    var followed by remember { mutableStateOf(prefs.followed) }
    var tracked by remember { mutableStateOf(prefs.tracked()) }
    var companyFilter by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf(store.load()) }
    // scores are worked out on the phone from this user's profile
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var gaps by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(feed, profile) {
        val f = feed ?: return@LaunchedEffect
        val scored = withContext(Dispatchers.Default) { Match.rescore(f.jobs, profile) }
        jobs = scored
        gaps = withContext(Dispatchers.Default) { Match.skillGap(scored, profile) }
    }

    fun refresh() {
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.download() } }
                .onSuccess { feed = it }
                .onFailure { error = "Could not update: ${it.message ?: "no internet?"}" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // kept here (not inside the Jobs tab) so filters and scroll position survive opening a job or switching tabs
    val filters = remember { JobFilters() }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Job Radar", fontWeight = FontWeight.Bold)
                        val f = feed
                        Text(
                            if (f == null) "Loading…" else "${f.jobs.size} open jobs · updated ${ago(f.generated)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = {
                            Icon(when (t) {
                                Tab.JOBS -> Icons.Default.Work
                                Tab.COMPANIES -> Icons.Default.Business
                                Tab.TRACKER -> Icons.Default.Checklist
                                Tab.PROFILE -> Icons.Default.Person
                            }, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val f = feed
            val scored = jobs
            if (tab == Tab.PROFILE) {
                ProfileScreen(profile, gaps) { profile = it; store.save(it) }
                return@Column
            }
            if (f == null || scored == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (loading) "Getting jobs…" else "No data yet. Pull refresh when online.")
                }
                return@Column
            }
            when (tab) {
                Tab.JOBS -> JobList(scored, filters, listState, followed, tracked, previousVisit, companyFilter, profile.preferredCountries.toSet(),
                    onClearCompany = { companyFilter = null }) { open = it }
                Tab.COMPANIES -> CompanyList(f, followed,
                    onToggle = { name ->
                        followed = if (name in followed) followed - name else followed + name
                        prefs.followed = followed
                    },
                    onShowJobs = { companyFilter = it; tab = Tab.JOBS })
                Tab.TRACKER -> Tracker(tracked, scored, profile.preferredCountries.toSet()) { open = it }
                Tab.PROFILE -> Unit
            }
        }
    }

    val current = open
    if (current != null) {
        BackHandler { open = null }
        Surface(Modifier.fillMaxSize()) {
            JobDetail(current, tracked[current.id]?.status, profile, onBack = { open = null }) { st ->
                prefs.setStatus(current, st); tracked = prefs.tracked()
            }
        }
    }
    }
}

// ---------------------------------------------------------------- Jobs

private enum class Region(val label: String) { ALL("India + abroad"), INDIA("🇮🇳 India"), ABROAD("🌍 Abroad") }

private enum class MinScore(val label: String, val v: Int) { ALL("All", 0), GOOD("50%+", 50), TOP("70%+", 70) }

private class JobFilters {
    var query by mutableStateOf("")
    var onlyNew by mutableStateOf(false)
    var onlyFollowed by mutableStateOf(false)
    var minScore by mutableStateOf(MinScore.ALL)
    var region by mutableStateOf(Region.ALL)
    var countries by mutableStateOf(emptySet<String>())
    var sortNewest by mutableStateOf(false)
    var lastKey: Any? = null   // filter values the list was last shown with
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobList(
    jobs: List<Job>, fs: JobFilters, listState: LazyListState, followed: Set<String>, tracked: Map<String, Tracked>,
    previousVisit: String, companyFilter: String?, preferred: Set<String>, onClearCompany: () -> Unit, onOpen: (Job) -> Unit,
) {
    var query by fs::query
    var onlyNew by fs::onlyNew
    var onlyFollowed by fs::onlyFollowed
    var minScore by fs::minScore
    var region by fs::region
    var countries by fs::countries
    var sortNewest by fs::sortNewest
    var pickCountry by remember { mutableStateOf(false) }

    val shown = remember(jobs, preferred, region, countries, query, onlyNew, onlyFollowed, minScore, sortNewest, followed, companyFilter) {
        val q = query.trim().lowercase()
        jobs.filter { j ->
            (companyFilter == null || j.company == companyFilter) &&
                j.score >= minScore.v &&
                (region == Region.ALL || (region == Region.INDIA) == j.india) &&
                (countries.isEmpty() || j.country in countries) &&
                (!onlyNew || isWithinHours(j.firstSeen, 48)) &&
                (!onlyFollowed || j.company in followed) &&
                (q.isEmpty() || q.split(" ").all { w ->
                    j.title.lowercase().contains(w) || j.company.lowercase().contains(w) || j.location.lowercase().contains(w) || j.country.lowercase().contains(w)
                })
        }.let { l -> if (sortNewest) l.sortedByDescending { it.firstSeen } else l.sortedWith(compareBy<Job>({ if (preferred.isEmpty()) !it.india else it.country !in preferred }, { -it.score })) }
    }

    // new filter -> start from the top; coming back from a job or another tab -> stay where the user was
    val key = listOf(region, countries, query, onlyNew, onlyFollowed, minScore, sortNewest, companyFilter)
    LaunchedEffect(key) {
        if (fs.lastKey != null && fs.lastKey != key) listState.scrollToItem(0)
        fs.lastKey = key
    }

    Column {
        OutlinedTextField(
            query, { query = it }, singleLine = true,
            placeholder = { Text("Search title, company, country, city") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (companyFilter != null) {
                InputChip(true, onClearCompany, { Text(companyFilter) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
            }
            FilterChip(countries.isNotEmpty(), { pickCountry = true },
                { Text(if (countries.isEmpty()) "Country" else countries.sorted().joinToString(", ").let { if (it.length > 24) "${countries.size} countries" else it }) },
                leadingIcon = { Icon(Icons.Default.Public, null, Modifier.size(16.dp)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) })
            Region.entries.forEach { r -> FilterChip(region == r, { region = r }, { Text(r.label) }) }
            FilterChip(onlyNew, { onlyNew = !onlyNew }, { Text("New (48h)") })
            FilterChip(onlyFollowed, { onlyFollowed = !onlyFollowed }, { Text("★ Following") })
            MinScore.entries.forEach { m -> FilterChip(minScore == m, { minScore = m }, { Text(m.label) }) }
            FilterChip(sortNewest, { sortNewest = !sortNewest }, { Text(if (sortNewest) "Newest first" else "Preferred countries first") },
                leadingIcon = { Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp)) })
        }
        Text("${shown.size} jobs", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { j ->
                JobCard(j, j.company in followed, tracked[j.id]?.status, isNewerThan(j.firstSeen, previousVisit)) { onOpen(j) }
            }
            if (shown.isEmpty()) item {
                Text("No jobs match these filters.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (pickCountry) CountryPicker(jobs, countries, onDone = { countries = it; pickCountry = false }, onDismiss = { pickCountry = false })
}

@Composable
private fun CountryPicker(jobs: List<Job>, selected: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    // India first, then by number of jobs
    val counts = remember(jobs) {
        jobs.groupingBy { it.country }.eachCount().toList()
            .sortedWith(compareBy({ it.first != "India" }, { it.first == "Other" }, { -it.second }))
    }
    var picked by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Countries") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(counts, key = { it.first }) { (c, n) ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (c in picked) picked - c else picked + c },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c in picked, { picked = if (c in picked) picked - c else picked + c })
                        Text(c, Modifier.weight(1f))
                        Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onDone(picked) }) { Text("Show jobs") } },
        dismissButton = { TextButton({ onDone(emptySet()) }) { Text("All countries") } },
    )
}

@Composable
private fun ScoreBadge(score: Int, size: Int = 46) {
    val c = when {
        score >= 70 -> Color(0xFF1A7F37)
        score >= 50 -> Color(0xFFB08800)
        else -> Color(0xFF6E7781)
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text("$score%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 3.6).sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(j: Job, followed: Boolean, status: Status?, isNew: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp)) {
            ScoreBadge(j.score)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNew) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text((if (followed) "★ " else "") + j.company, style = MaterialTheme.typography.bodyMedium)
                Text(j.location.ifBlank { "Location not given" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (j.matched.isNotEmpty()) {
                    Text(j.matched.take(5).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1A7F37), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    Text("found ${ago(j.firstSeen)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (status != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("• ${status.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun JobDetail(j: Job, status: Status?, profile: Profile, onBack: () -> Unit, onStatus: (Status?) -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(j.company, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "${j.title} – ${j.company}, ${j.location}\n${j.url}")
                    ctx.startActivity(Intent.createChooser(send, "Share job"))
                }) { Icon(Icons.Default.Share, "Share") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(j.score, 56)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(j.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(j.location.ifBlank { "Location not given" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                j.matched.forEach { AssistChip({}, { Text(it) }, leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) }
                j.minYears?.let { AssistChip({}, { Text("Asks $it+ yrs") }) }
            }
            val posted = when {
                j.posted.isBlank() -> ""
                j.posted.startsWith("Posted", ignoreCase = true) -> " · ${j.posted}"   // Workday: "Posted 22 Days Ago"
                j.posted.first().isDigit() && j.posted.length >= 10 -> " · posted ${j.posted.take(10)}"
                else -> " · posted ${j.posted.take(16)}"
            }
            Text("Found ${ago(j.firstSeen)}$posted",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(j.url)))
                    if (status == null) onStatus(Status.SAVED)
                },
                modifier = Modifier.fillMaxWidth(), enabled = j.url.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open & apply on company site")
            }
            Spacer(Modifier.height(12.dp))
            Text("My status", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Status.entries.forEach { s ->
                    FilterChip(status == s, { onStatus(if (status == s) null else s) }, { Text(s.label) })
                }
            }
            Spacer(Modifier.height(12.dp))
            QuickApplyPanel(j, profile)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Job description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(j.desc.ifBlank { "No description in the feed. Tap the button above to read it on the company site." },
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- Companies

@Composable
private fun CompanyList(feed: Feed, followed: Set<String>, onToggle: (String) -> Unit, onShowJobs: (String) -> Unit) {
    val ctx = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    val sorted = remember(feed, followed) {
        feed.companies.sortedWith(compareBy({ it.name !in followed }, { it.status != "ok" }, { -it.jobs }, { it.name }))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text("Tap ★ to follow a company. Followed companies show first and can be filtered in Jobs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 8.dp))
            OutlinedButton({ showAdd = true }, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Request a new company")
            }
        }
        items(sorted, key = { it.name }) { c ->
            val isF = c.name in followed
            ListItem(
                modifier = Modifier.clickable(enabled = c.jobs > 0) { onShowJobs(c.name) },
                headlineContent = { Text(c.name, fontWeight = if (isF) FontWeight.Bold else FontWeight.Normal) },
                supportingContent = {
                    Text(when (c.status) {
                        "ok" -> "${c.jobs} matching jobs · ${c.industry}"
                        "error" -> "⚠ Could not check today"
                        else -> "⚠ Not trackable automatically – check their site"
                    }, style = MaterialTheme.typography.bodySmall)
                },
                trailingContent = {
                    IconButton({ onToggle(c.name) }) {
                        if (isF) Icon(Icons.Default.Star, "Unfollow", tint = MaterialTheme.colorScheme.secondary)
                        else Icon(Icons.Outlined.StarOutline, "Follow")
                    }
                },
            )
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Request a new company") },
            text = {
                Column {
                    Text("This opens a request on the project page. The company is added to the daily search once its career site is connected.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Company name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton({
                    val repoUrl = BuildConfig.DATA_URL.replace("raw.githubusercontent.com", "github.com").substringBefore("/main/")
                    val url = "$repoUrl/issues/new?title=" + Uri.encode("Add company: $name") +
                        "&body=" + Uri.encode("Please add $name to Job Radar.\nCareers page (if known): ")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    showAdd = false
                }, enabled = name.isNotBlank()) { Text("Send request") }
            },
            dismissButton = { TextButton({ showAdd = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Tracker

private const val FOLLOW_UP_DAYS = 10

@Composable
private fun Tracker(tracked: Map<String, Tracked>, jobs: List<Job>, preferred: Set<String>, onOpen: (Job) -> Unit) {
    // strong matches not yet saved or applied to, preferred countries first
    val ready = remember(tracked, jobs, preferred) {
        jobs.filter { it.score >= 70 && it.id !in tracked }
            .sortedWith(compareBy<Job>({ if (preferred.isEmpty()) !it.india else it.country !in preferred }, { -it.score })).take(30)
    }
    val groups = tracked.values.groupBy { it.status }
    val now = System.currentTimeMillis()
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Status.entries.forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${groups[s]?.size ?: 0}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(s.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Status.entries.forEach { s ->
            val list = groups[s]?.sortedByDescending { it.at } ?: return@forEach
            item {
                Text(s.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
            }
            items(list, key = { "t" + it.job.id }) { t ->
                val days = if (t.at > 0) ((now - t.at) / 86_400_000L).toInt() else 0
                val followUp = s == Status.APPLIED && days >= FOLLOW_UP_DAYS
                ListItem(
                    modifier = Modifier.clickable { onOpen(t.job) },
                    leadingContent = { ScoreBadge(t.job.score, 38) },
                    headlineContent = { Text(t.job.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text("${t.job.company} · ${t.job.location}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (followUp) Text("⏰ Applied $days days ago, no update. Time to follow up?",
                                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            else if (t.at > 0) Text("${s.label} ${if (days == 0) "today" else "$days days ago"}", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            }
        }
        item {
            Text("Ready to apply – strong matches (70%+)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            Text("Not saved or applied yet. Open one and use Quick apply.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (ready.isEmpty()) item {
            Text("Nothing waiting right now.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(ready, key = { "r" + it.id }) { j ->
            ListItem(
                modifier = Modifier.clickable { onOpen(j) },
                leadingContent = { ScoreBadge(j.score, 38) },
                headlineContent = { Text(j.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${j.company} · ${j.location}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
