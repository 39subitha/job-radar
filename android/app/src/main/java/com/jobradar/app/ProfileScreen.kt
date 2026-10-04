package com.jobradar.app

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Draft being edited: list fields are kept as typed text until saved. */
private data class Draft(
    val p: Profile,
    val keySkills: String = p.keySkills.joinToString(", "),
    val otherSkills: String = p.otherSkills.joinToString(", "),
    val targetTitles: String = p.targetTitles.joinToString(", "),
    val domains: String = p.domains.joinToString(", "),
    val countries: String = p.preferredCountries.joinToString(", "),
    val years: String = if (p.years > 0) p.years.toString() else "",
) {
    fun toProfile() = p.copy(
        keySkills = splitList(keySkills), otherSkills = splitList(otherSkills), targetTitles = splitList(targetTitles),
        domains = splitList(domains), preferredCountries = splitList(countries), years = years.trim().toIntOrNull() ?: 0,
        experience = p.experience.filter { it.role.isNotBlank() || it.company.isNotBlank() || it.points.isNotBlank() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(profile: Profile, gaps: List<Pair<String, Int>>, onSave: (Profile) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember(profile) { mutableStateOf(Draft(profile)) }
    val changed = d.toProfile() != profile

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Profile.from(JSONObject(text))
        }.onSuccess { onSave(it); Toast.makeText(ctx, "Profile imported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Not a Job Radar profile file", Toast.LENGTH_LONG).show() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 90.dp)) {
            Text("Your resume and job preferences. Matching scores use this profile. It stays only on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))

            // ---- resume actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { ResumePdf.build(ctx, d.toProfile()) }
                        ResumePdf.open(ctx, f)
                    }
                }) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("Resume PDF") }
                OutlinedButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) { Text("Import") }
                OutlinedButton({
                    val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "JobRadar_profile.json")
                    f.writeText(d.toProfile().toJson().toString(2))
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Back up profile"))
                }) { Text("Backup") }
            }

            // ---- skill gap
            if (gaps.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Skills employers ask for", fontWeight = FontWeight.Bold)
                        Text("Found in your matching jobs but not in your profile. Add the ones you really have; learn the others.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            gaps.forEach { (skill, n) ->
                                val added = splitList(d.otherSkills + "," + d.keySkills).any { it.equals(skill, true) }
                                AssistChip(
                                    onClick = { if (!added) d = d.copy(otherSkills = (splitList(d.otherSkills) + skill).joinToString(", ")) },
                                    label = { Text("$skill · $n jobs") },
                                    leadingIcon = { Icon(if (added) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                }
            }

            Section("About you")
            Field("Full name", d.p.name) { d = d.copy(p = d.p.copy(name = it)) }
            Field("Headline (e.g. Tooling & Fixture Design Engineer)", d.p.headline) { d = d.copy(p = d.p.copy(headline = it)) }
            Field("Email", d.p.email, KeyboardType.Email) { d = d.copy(p = d.p.copy(email = it)) }
            Field("Phone", d.p.phone, KeyboardType.Phone) { d = d.copy(p = d.p.copy(phone = it)) }
            Field("City, Country", d.p.city) { d = d.copy(p = d.p.copy(city = it)) }
            Field("LinkedIn URL", d.p.linkedin, KeyboardType.Uri) { d = d.copy(p = d.p.copy(linkedin = it)) }
            Field("Years of experience", d.years, KeyboardType.Number) { d = d.copy(years = it.filter(Char::isDigit).take(2)) }
            Field("Professional summary", d.p.summary, lines = 4) { d = d.copy(p = d.p.copy(summary = it)) }

            Section("Skills (comma separated)")
            Field("Key skills (count most)", d.keySkills, lines = 2) { d = d.copy(keySkills = it) }
            Field("Other skills", d.otherSkills, lines = 3) { d = d.copy(otherSkills = it) }

            Section("Jobs I want")
            Field("Job title words (e.g. Fixture, Jig, Tooling)", d.targetTitles, lines = 2) { d = d.copy(targetTitles = it) }
            Field("Industries / domains (e.g. Rail, Automotive)", d.domains, lines = 2) { d = d.copy(domains = it) }
            Field("Preferred countries (shown first)", d.countries) { d = d.copy(countries = it) }

            Section("Work experience")
            d.p.experience.forEachIndexed { i, e ->
                OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        fun upd(x: Experience) { d = d.copy(p = d.p.copy(experience = d.p.experience.toMutableList().also { it[i] = x })) }
                        Field("Role", e.role) { upd(e.copy(role = it)) }
                        Field("Company", e.company) { upd(e.copy(company = it)) }
                        Field("Period (e.g. Aug 2025 – Present)", e.period) { upd(e.copy(period = it)) }
                        Field("Location", e.location) { upd(e.copy(location = it)) }
                        Field("What you did (one point per line)", e.points, lines = 5) { upd(e.copy(points = it)) }
                        TextButton({ d = d.copy(p = d.p.copy(experience = d.p.experience.toMutableList().also { it.removeAt(i) })) }) {
                            Icon(Icons.Default.Delete, null); Text("Remove")
                        }
                    }
                }
            }
            OutlinedButton({ d = d.copy(p = d.p.copy(experience = listOf(Experience()) + d.p.experience)) }) {
                Icon(Icons.Default.Add, null); Text("Add job (newest first)")
            }

            Section("Education, training, languages (one per line)")
            Field("Education", d.p.education, lines = 3) { d = d.copy(p = d.p.copy(education = it)) }
            Field("Training & certifications", d.p.certifications, lines = 3) { d = d.copy(p = d.p.copy(certifications = it)) }
            Field("Languages", d.p.languages, lines = 3) { d = d.copy(p = d.p.copy(languages = it)) }

            Section("Saved answers for applications")
            Field("Notice period (e.g. 90 days)", d.p.noticePeriod) { d = d.copy(p = d.p.copy(noticePeriod = it)) }
            Field("Current CTC", d.p.currentCtc) { d = d.copy(p = d.p.copy(currentCtc = it)) }
            Field("Expected CTC", d.p.expectedCtc) { d = d.copy(p = d.p.copy(expectedCtc = it)) }
            Field("Willing to relocate? (e.g. Yes – India & abroad)", d.p.relocation) { d = d.copy(p = d.p.copy(relocation = it)) }
            Field("Visa / work permit (e.g. Indian citizen, needs visa abroad)", d.p.visaStatus) { d = d.copy(p = d.p.copy(visaStatus = it)) }
        }

        if (changed) {
            ExtendedFloatingActionButton(
                onClick = { onSave(d.toProfile()); Toast.makeText(ctx, "Saved – scores updated", Toast.LENGTH_SHORT).show() },
                icon = { Icon(Icons.Default.Save, null) }, text = { Text("Save") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
