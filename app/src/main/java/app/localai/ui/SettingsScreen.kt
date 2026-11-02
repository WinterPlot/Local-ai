package app.localai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.localai.data.AppSettings
import app.localai.engine.SamplingParams
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var temp by remember(saved) { mutableStateOf(saved.sampling.temperature.toString()) }
    var topK by remember(saved) { mutableStateOf(saved.sampling.topK.toString()) }
    var topP by remember(saved) { mutableStateOf(saved.sampling.topP.toString()) }
    var minP by remember(saved) { mutableStateOf(saved.sampling.minP.toString()) }
    var rep by remember(saved) { mutableStateOf(saved.sampling.repeatPenalty.toString()) }
    var repN by remember(saved) { mutableStateOf(saved.sampling.repeatLastN.toString()) }
    var seed by remember(saved) { mutableStateOf(saved.sampling.seed.toString()) }
    var maxNew by remember(saved) { mutableStateOf(saved.sampling.maxNewTokens.toString()) }
    var stops by remember(saved) { mutableStateOf(saved.sampling.stopSequences.joinToString("\n")) }
    var ctxSize by remember(saved) { mutableStateOf(saved.contextSize) }
    var threads by remember(saved) { mutableStateOf(saved.threads.toString()) }
    var system by remember(saved) { mutableStateOf(saved.systemPrompt) }
    var confirmDeleteChats by remember { mutableStateOf(false) }
    var confirmDeleteModels by remember { mutableStateOf(false) }

    fun save() {
        val s = try {
            AppSettings(
                sampling = SamplingParams(
                    temperature = temp.toFloat(), topK = topK.toInt(), topP = topP.toFloat(), minP = minP.toFloat(),
                    repeatPenalty = rep.toFloat(), repeatLastN = repN.toInt(), seed = seed.toLong(),
                    maxNewTokens = maxNew.toInt(), stopSequences = stops.split('\n').filter { it.isNotEmpty() },
                ),
                contextSize = ctxSize, threads = threads.toInt(), systemPrompt = system, activeModelId = saved.activeModelId,
            )
        } catch (e: NumberFormatException) {
            scope.launch { snackbar.showSnackbar("One of the numeric fields is not a valid number") }
            return
        }
        scope.launch {
            val err = vm.saveSettings(s)
            snackbar.showSnackbar(err ?: if (s.contextSize != saved.contextSize || s.threads != saved.threads) "Saved. Reload the model for context/thread changes to apply." else "Saved")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Assistant")
            OutlinedTextField(system, { system = it }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            Hint("Applied to every reply immediately. Sent through the model's own chat template.")

            Section("Sampling (applies immediately)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num(temp, { temp = it }, "Temperature", Modifier.weight(1f))
                Num(topP, { topP = it }, "Top-p", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num(topK, { topK = it }, "Top-k", Modifier.weight(1f), int = true)
                Num(minP, { minP = it }, "Min-p", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num(rep, { rep = it }, "Repeat penalty", Modifier.weight(1f))
                Num(repN, { repN = it }, "Repeat window", Modifier.weight(1f), int = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num(maxNew, { maxNew = it }, "Max new tokens", Modifier.weight(1f), int = true)
                Num(seed, { seed = it }, "Seed (-1 = random)", Modifier.weight(1f), int = true)
            }
            OutlinedTextField(stops, { stops = it }, label = { Text("Stop sequences (one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Hint("Temperature 0 means greedy decoding. Higher context sizes need more RAM.")

            Section("Model loading (requires reloading the model)")
            Text("Context size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.CONTEXT_CHOICES.forEach { c ->
                    FilterChip(selected = ctxSize == c, onClick = { ctxSize = c }, label = { Text(c.toString()) })
                }
            }
            Num(threads, { threads = it }, "CPU threads", Modifier.fillMaxWidth(), int = true)
            Hint("Context is capped at the model's trained maximum. GPU offload is not available in this build (CPU only).")

            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
            OutlinedButton(onClick = {
                val d = AppSettings()
                temp = d.sampling.temperature.toString(); topK = d.sampling.topK.toString(); topP = d.sampling.topP.toString()
                minP = d.sampling.minP.toString(); rep = d.sampling.repeatPenalty.toString(); repN = d.sampling.repeatLastN.toString()
                seed = d.sampling.seed.toString(); maxNew = d.sampling.maxNewTokens.toString(); stops = ""
                ctxSize = d.contextSize; threads = d.threads.toString(); system = d.systemPrompt
            }, modifier = Modifier.fillMaxWidth()) { Text("Reset fields to defaults (then Save)") }

            HorizontalDivider()
            Section("Privacy and storage")
            Text(
                "• Conversations, settings and imported models are stored only on this device.\n" +
                    "• Inference runs locally. This app declares no network permission and uses no analytics or ads.\n" +
                    "• Prompts and replies are never written to the system log.\n" +
                    "• Backups and device transfer of app data are disabled.\n" +
                    "• Importing a model copies it into app-private storage; the original file is never changed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { confirmDeleteChats = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete all conversations") }
            OutlinedButton(onClick = { confirmDeleteModels = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete all app-managed model files") }

            HorizontalDivider()
            Section("About and diagnostics")
            Text("LocalAI 0.1.0 · inference by llama.cpp (MIT)", style = MaterialTheme.typography.bodyMedium)
            Text("Runtime CPU features reported by llama.cpp:", style = MaterialTheme.typography.labelMedium)
            Text(remember { vm.systemInfo() }, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmDeleteChats) ConfirmDialog("Delete all conversations?", "Every conversation and message will be permanently deleted.", { vm.deleteAllConversations(); confirmDeleteChats = false }) { confirmDeleteChats = false }
    if (confirmDeleteModels) ConfirmDialog("Delete model files?", "This removes every model copy stored inside LocalAI. Your original files are not touched.", { vm.deleteAllModels(); confirmDeleteModels = false }) { confirmDeleteModels = false }
}

@Composable
private fun Section(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Num(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, int: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (int) KeyboardType.Number else KeyboardType.Decimal),
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
