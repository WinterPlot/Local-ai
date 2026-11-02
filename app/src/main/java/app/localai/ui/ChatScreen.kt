package app.localai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.localai.data.MessageEntity
import app.localai.engine.EngineState
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onModels: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val ready = engine is EngineState.Ready || engine is EngineState.Generating
    val lastAssistantId = messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id

    LaunchedEffect(messages.size, ui.streaming) {
        val count = messages.size + (if (ui.generating) 1 else 0)
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: "Chat", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Conversation menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export as Markdown") }, onClick = {
                            menu = false
                            scope.launch { shareText(ctx, vm.exportMarkdown(), "Export conversation") }
                        })
                        DropdownMenuItem(text = { Text("Copy conversation") }, onClick = {
                            menu = false
                            scope.launch { copyToClipboard(ctx, "Conversation", vm.exportMarkdown()) }
                        })
                        DropdownMenuItem(text = { Text("Clear messages") }, enabled = !ui.generating, onClick = { menu = false; confirmClear = true })
                        DropdownMenuItem(text = { Text("Model library") }, onClick = { menu = false; onModels() })
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                ui.notice?.let { StatusLine(it) }
                ui.error?.let { ErrorBar(it, vm::dismissError) }
                StatusLine(statusText(engine, ui))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        placeholder = { Text(if (ready) "Message" else "Load a model first") },
                        modifier = Modifier.weight(1f), maxLines = 6, enabled = ready,
                    )
                    Box(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                        if (ui.generating) {
                            FilledIconButton(onClick = vm::stop) { Icon(Icons.Filled.Close, contentDescription = "Stop generating") }
                        } else {
                            FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = ready && input.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty() && !ui.generating) {
            EmptyState(
                title = if (ready) "Ask anything" else "No model loaded",
                body = if (ready) "Everything runs on this device." else "Open the model library, import a GGUF file, and load it.",
                modifier = Modifier.padding(padding),
            ) { if (!ready) TextButton(onClick = onModels) { Text("Open model library") } }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        role = m.role, text = m.content,
                        footer = if (m.role == "assistant") statsLine(m) else null,
                        canRegenerate = m.id == lastAssistantId && !ui.generating,
                        canEdit = m.role == "user" && !ui.generating,
                        onCopy = { copyToClipboard(ctx, "Message", m.content) },
                        onShare = { shareText(ctx, m.content) },
                        onRegenerate = vm::regenerate,
                        onEdit = { editing = m },
                    )
                }
                if (ui.generating) {
                    item(key = "streaming") {
                        if (ui.streaming.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                                Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            MessageBubble("assistant", ui.streaming, null, false, false, {}, {}, {}, {}, showActions = false)
                        }
                    }
                }
            }
        }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 8)
                    Text(
                        "Messages after this one will be replaced by a new reply.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.editAndResend(m.id, text); editing = null }, enabled = text.isNotBlank()) { Text("Resend") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this conversation?") },
            text = { Text("All messages in this conversation will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { scope.launch { vm.clearConversation() }; confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun statusText(engine: EngineState, ui: ChatUiState): String {
    val ctx = if (ui.contextUsed != null && ui.contextSize != null) " · context ${ui.contextUsed}/${ui.contextSize} tokens" else ""
    return when (engine) {
        EngineState.Idle -> "No model loaded"
        EngineState.LoadingModel -> "Loading model…"
        is EngineState.Ready -> "${engine.info.name.ifBlank { engine.info.arch }} · ready$ctx"
        EngineState.Generating -> "Generating…$ctx"
        is EngineState.Failed -> "Model failed to load"
    }
}

private fun statsLine(m: MessageEntity): String? {
    val parts = ArrayList<String>()
    m.generatedTokens?.let { parts += "$it tokens" }
    m.tokensPerSecond?.let { parts += String.format(Locale.US, "%.1f tok/s", it) }
    when (m.stopReason) {
        "CANCELLED" -> parts += "stopped"
        "MAX_TOKENS" -> parts += "length limit reached"
        "CONTEXT_FULL" -> parts += "context full"
        "ERROR" -> parts += "interrupted by an error"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun ErrorBar(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MessageBubble(
    role: String, text: String, footer: String?,
    canRegenerate: Boolean, canEdit: Boolean,
    onCopy: () -> Unit, onShare: () -> Unit, onRegenerate: () -> Unit, onEdit: () -> Unit,
    showActions: Boolean = true,
) {
    val isUser = role == "user"
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            SelectionContainer {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isUser) Text(text, style = MaterialTheme.typography.bodyLarge)
                    else for (seg in splitCodeFences(text)) {
                        if (seg.isCode) CodeBlock(seg, onCopy = { copyToClipboard(ctx, "Code", seg.text) })
                        else Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (footer != null || text.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (footer != null) Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                if (showActions) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onShare) { Text("Share") }
                    if (canEdit) TextButton(onClick = onEdit) { Text("Edit") }
                    if (canRegenerate) TextButton(onClick = onRegenerate) { Text("Regenerate") }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(seg: Segment, onCopy: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(seg.language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopy) { Text("Copy code") }
            }
            Box(Modifier.padding(12.dp)) {
                // Rendered as inert text: no HTML or script is ever interpreted.
                Text(seg.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
