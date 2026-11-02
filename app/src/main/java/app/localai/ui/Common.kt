package app.localai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareText(context: Context, text: String, title: String = "Share") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, title))
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024))
    else -> "$b B"
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        action()
    }
}

/** Splits assistant text into plain and fenced-code segments. Pure and unit-tested. */
data class Segment(val text: String, val isCode: Boolean, val language: String = "")

fun splitCodeFences(src: String): List<Segment> {
    val out = ArrayList<Segment>()
    val lines = src.split("\n")
    val plain = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in lines) {
        val t = line.trimStart()
        if (t.startsWith("```")) {
            if (!inCode) {
                if (plain.isNotEmpty()) { out.add(Segment(plain.toString().trimEnd('\n'), false)); plain.clear() }
                inCode = true; lang = t.removePrefix("```").trim()
            } else {
                out.add(Segment(code.toString().trimEnd('\n'), true, lang)); code.clear(); inCode = false
            }
        } else if (inCode) code.append(line).append('\n') else plain.append(line).append('\n')
    }
    // Unterminated fence (still streaming): show what we have as code.
    if (inCode) out.add(Segment(code.toString().trimEnd('\n'), true, lang))
    else if (plain.isNotBlank()) out.add(Segment(plain.toString().trimEnd('\n'), false))
    return out
}
