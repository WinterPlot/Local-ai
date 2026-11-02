package app.localai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.localai.data.ModelEntity
import app.localai.engine.EngineState
import app.localai.engine.MemoryVerdict

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val imp by vm.importUi.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var advice by remember { mutableStateOf<LoadAdvice?>(null) }
    var removing by remember { mutableStateOf<ModelEntity?>(null) }
    var details by remember { mutableStateOf<ModelEntity?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Storage Access Framework: no storage permission is requested.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }

    val loadedName = (engine as? EngineState.Ready)?.info?.name
    val busy = engine is EngineState.LoadingModel || engine is EngineState.Generating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model library") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) }, enabled = !imp.active && !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("Import a GGUF model") }

            if (imp.active) {
                Column {
                    if (imp.total > 0) LinearProgressIndicator(progress = { (imp.copied.toFloat() / imp.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "Copying into app storage: ${formatBytes(imp.copied)}" + if (imp.total > 0) " of ${formatBytes(imp.total)}" else "",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::cancelImport) { Text("Cancel") }
                    }
                }
            }
            if (engine is EngineState.LoadingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Loading model into memory…", style = MaterialTheme.typography.bodySmall)
            }
            if (loadedName != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Loaded: ${loadedName.ifBlank { "model" }}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = vm::unloadModel, enabled = !busy) { Text("Unload") }
                }
            }

            if (models.isEmpty() && !imp.active) {
                EmptyState(
                    "No models yet",
                    "Pick a .gguf file, for example from Downloads. The file is copied into the app's private storage; your original is never modified or deleted. " +
                        "Smaller models (around 1B parameters, 4-bit) work best on phones.",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(models, key = { it.id }) { m ->
                        ModelCard(
                            m, available = true, active = settings.activeModelId == m.id && loadedName != null, busy = busy,
                            onLoad = { advice = vm.adviceFor(m) }, onDetails = { details = m }, onRemove = { removing = m },
                        )
                    }
                }
            }
        }
    }

    advice?.let { a ->
        val gb = { b: Long -> formatBytes(b) }
        AlertDialog(
            onDismissRequest = { advice = null },
            title = { Text(if (a.verdict == MemoryVerdict.OK || a.verdict == MemoryVerdict.UNKNOWN) "Load ${a.model.displayName}?" else "Memory warning") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Estimated memory (rough, not measured):", style = MaterialTheme.typography.labelMedium)
                    Text("• Weights: ${gb(a.estimate.weightsBytes)}")
                    Text(if (a.kvKnown) "• KV cache (context ${settings.contextSize}): ${gb(a.estimate.kvCacheBytes)}" else "• KV cache: unknown (the file does not report its dimensions)")
                    Text("• Overhead: ≈${gb(a.estimate.overheadBytes)}")
                    Text("Free RAM right now: ${gb(a.availableBytes)}")
                    when (a.verdict) {
                        MemoryVerdict.LIKELY_TO_FAIL -> Text("This model will probably not fit. Loading it may crash the app or the device may become unresponsive. Try a smaller model, a smaller context, or close other apps.", color = MaterialTheme.colorScheme.error)
                        MemoryVerdict.TIGHT -> Text("This is tight. Loading may be slow or fail; closing other apps helps.")
                        else -> Unit
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.loadModel(a.model); advice = null }) { Text(if (a.verdict == MemoryVerdict.LIKELY_TO_FAIL) "Load anyway" else "Load") } },
            dismissButton = { TextButton(onClick = { advice = null }) { Text("Cancel") } },
        )
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.displayName}?") },
            text = { Text("This deletes the copy stored inside LocalAI (${formatBytes(m.sizeBytes)}). Your original file is not touched.") },
            confirmButton = { TextButton(onClick = { vm.deleteModel(m); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    details?.let { m ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(m.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("File", m.originalFileName)
                    DetailRow("Size", formatBytes(m.sizeBytes))
                    DetailRow("Architecture", m.architecture)
                    DetailRow("Quantization", m.quantization)
                    DetailRow("Trained context", m.contextTrain?.toString())
                    DetailRow("Layers", m.blockCount?.toString())
                    DetailRow("Embedding size", m.embeddingLength?.toString())
                    DetailRow("Attention heads", m.headCount?.toString())
                    DetailRow("KV heads", m.headCountKv?.toString())
                    DetailRow("Chat template", if (m.hasChatTemplate) "provided by the model" else "none in file (generic fallback is used)")
                    DetailRow("Stored at", "app-private storage")
                    Text("“Not reported” means the GGUF file does not contain that field. Whether llama.cpp can run the architecture is only known when loading.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.4f))
        Text(value ?: "Not reported", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun ModelCard(m: ModelEntity, available: Boolean, active: Boolean, busy: Boolean, onLoad: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                listOfNotNull(m.architecture, m.quantization, formatBytes(m.sizeBytes)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!m.hasChatTemplate) Text("No chat template in this file: replies may be lower quality.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad, enabled = available && !active && !busy) { Text(if (active) "Loaded" else "Load") }
                OutlinedButton(onClick = onDetails) { Text("Details") }
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            }
        }
    }
}
