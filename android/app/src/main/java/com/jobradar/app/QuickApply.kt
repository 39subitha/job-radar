package com.jobradar.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

/** Short, specific cover letter built from the profile and the job's matched skills. */
fun coverLetter(job: Job, p: Profile): String {
    val skills = job.matched.filterNot { it.endsWith("yrs ok") }.take(4)
    val yearsText = if (p.years > 0) "${p.years} years of" else "hands-on"
    val domain = p.domains.firstOrNull { Match.has(job.desc.lowercase() + " " + job.title.lowercase(), it) }
    val lastJob = p.experience.firstOrNull()
    return buildString {
        appendLine("Dear Hiring Team at ${job.company},")
        appendLine()
        append("I am writing to apply for the ${job.title} position")
        if (job.location.isNotBlank()) append(" in ${job.location.substringBefore(";")}")
        appendLine(".")
        append("I am a ${p.headline.substringBefore("|").trim().ifBlank { "design engineer" }} with $yearsText experience")
        if (domain != null) append(" in the ${domain.lowercase()} domain")
        if (lastJob != null && lastJob.company.isNotBlank()) append(", currently working as ${lastJob.role} at ${lastJob.company}")
        appendLine(".")
        appendLine()
        if (skills.isNotEmpty()) {
            // keep acronyms like CATIA / GD&T, lower-case normal words mid-sentence
            val words = skills.map { if (it == it.uppercase()) it else it.lowercase() }
            appendLine("Your requirements match my background closely, especially in ${words.joinToString(", ")}.")
        }
        val highlights = p.experience.flatMap { it.points.lines() }.map { it.trim().trimStart('•', '-').trim() }
            .filter { it.isNotEmpty() }.sortedByDescending { line -> skills.count { Match.has(line.lowercase(), it) } }.take(2)
        if (highlights.isNotEmpty()) {
            appendLine("Some highlights:")
            highlights.forEach { appendLine("• $it") }
        }
        appendLine()
        if (p.noticePeriod.isNotBlank()) append("My notice period is ${p.noticePeriod}. ")
        if (p.relocation.isNotBlank()) append("Relocation: ${p.relocation}. ")
        appendLine("I would welcome the opportunity to discuss how I can contribute to your team.")
        appendLine()
        appendLine("Kind regards,")
        appendLine(p.name.ifBlank { "[Your name]" })
        listOf(p.phone, p.email).filter { it.isNotBlank() }.forEach { appendLine(it) }
    }.trim()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickApplyPanel(job: Job, p: Profile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLetter by remember { mutableStateOf(false) }
    val letter = remember(job.id, p) { coverLetter(job, p) }
    val answers = listOf(
        "Email" to p.email, "Phone" to p.phone, "Notice period" to p.noticePeriod, "Current CTC" to p.currentCtc,
        "Expected CTC" to p.expectedCtc, "Relocation" to p.relocation, "Visa / work permit" to p.visaStatus,
        "LinkedIn" to p.linkedin, "Experience" to if (p.years > 0) "${p.years} years" else "",
    ).filter { it.second.isNotBlank() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Quick apply", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Tap to copy, then paste into the company form.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (answers.isEmpty()) {
                Text("Fill in your saved answers in the Profile tab (notice period, CTC, relocation…).",
                    style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                answers.forEach { (label, value) ->
                    AssistChip({ copy(ctx, label, value) }, { Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp)) },
                        modifier = Modifier.widthIn(max = 320.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { ResumePdf.build(ctx, p) }
                        ResumePdf.share(ctx, f)
                    }
                }) { Icon(Icons.Default.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Resume PDF") }
                OutlinedButton({ showLetter = !showLetter }) {
                    Text("Cover letter"); Icon(if (showLetter) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (showLetter) {
                Spacer(Modifier.height(6.dp))
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                    Text(letter, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ copy(ctx, "Cover letter", letter) }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") }
                    TextButton({
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Application: ${job.title}").putExtra(Intent.EXTRA_TEXT, letter), "Send cover letter"))
                    }) { Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Share / email") }
                }
            }
        }
    }
}
