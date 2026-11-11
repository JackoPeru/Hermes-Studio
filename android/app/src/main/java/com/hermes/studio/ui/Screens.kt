package com.hermes.studio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hermes.studio.*
import com.hermes.studio.update.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Bg = Color(0xFF0B0B12)
val Card = Color(0xFF171726)
val AccentV = Color(0xFF9B5CFF)
val AccentB = Color(0xFF4AA8FF)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card,
            primary = AccentV, secondary = AccentB, tertiary = AccentV
        ),
        content = content
    )
}

@Composable
fun HeaderChip(online: Boolean, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(if (online) "Server connesso · $label" else "Server offline") },
        leadingIcon = {
            Icon(Icons.Filled.Circle, null, tint = if (online) Color(0xFF3DFF88) else Color.Red,
                modifier = Modifier.size(10.dp))
        }
    )
}

@Composable
fun HomeScreen(repo: StudioRepo, updater: AppUpdateManager, onNav: (String) -> Unit) {
    var caps by remember { mutableStateOf<Capabilities?>(null) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var urls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val loader = remember { mutableStateOf<coil.ImageLoader?>(null) }

    suspend fun refresh() {
        try {
            val api = repo.api()
            caps = api.capabilities()
            jobs = api.jobs(10)
            val al = api.assets()
            assets = al
            loader.value = repo.imageLoader()
            urls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            err = null
        } catch (e: Exception) { err = e.message }
    }
    LaunchedEffect(Unit) {
        refresh()
        // Polling coda ogni 5s (SSE server esiste ma V1 usa polling semplice e robusto).
        while (true) {
            delay(5000)
            try {
                val api = repo.api()
                jobs = api.jobs(10)
            } catch (_: Exception) {}
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Bentornato in Hermes Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Crea immagini con l'AI del tuo server, in modo semplice e veloce.",
                        color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onNav("create") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("Genera immagine")
                        }
                        Button(onClick = { onNav("video") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Crea video")
                        }
                    }
                }
            }
        }
        if (err != null) item { Text("Offline: $err — i job restano sul server e si recuperano al ritorno.", color = Color(0xFFFF9D9D)) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Coda di rendering (Server)", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { scope.launch { refresh() } }) { Text("Aggiorna") }
            }
            val active = jobs.filter { it.status in listOf("queued", "preparing", "running", "cancelling") }
            Text(if (active.isEmpty()) "Nessuna attività in corso" else "${active.size} attività in corso", color = Color.Gray)
            active.take(5).forEach { j ->
                ListItem(
                    headlineContent = { Text(j.prompt.take(60)) },
                    supportingContent = { Text("${j.status} · ${j.engine} · ${(j.progress ?: 0.0)}") },
                    trailingContent = {
                        if (j.status in listOf("queued", "running", "preparing")) {
                            TextButton(onClick = { scope.launch { try { repo.api().cancel(j.id); refresh() } catch (_: Exception) {} } }) { Text("Annulla") }
                        }
                    }
                )
            }
        }
        item {
            Text("Modelli disponibili", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                caps?.image?.forEach { m ->
                    ElevatedCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Qwen Image", fontWeight = FontWeight.Bold)
                            Text("Generazione immagini di alta qualità", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            AssistChip(onClick = {}, label = { Text(if (m.enabled) "Pronto" else "Non pronto") })
                            if (!m.enabled && m.disabled_reason != null) Text(m.disabled_reason!!, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
            }
            caps?.video?.firstOrNull()?.let { v ->
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text("MiniMax H3 — ${if (v.enabled) "Pronto" else "Disabilitato"}", fontWeight = FontWeight.Bold)
                        Text(v.disabled_reason ?: v.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Progetti recenti", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { onNav("library") }) { Text("Vedi tutto") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(assets.take(6)) { a ->
                    ElevatedCard(Modifier.size(150.dp, 170.dp)) {
                        val url = urls[a.id]
                        if (url != null && loader.value != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
                                imageLoader = loader.value!!,
                                contentDescription = a.id,
                                modifier = Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Crop
                            )
                        }
                        Text(a.id.take(14), modifier = Modifier.padding(6.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun CreateImageScreen(repo: StudioRepo) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("cinematico") }
    var format by remember { mutableStateOf("1:1") }
    var quality by remember { mutableStateOf("Alta") }
    var count by remember { mutableStateOf(1) }
    var state by remember { mutableStateOf("Pronto") }
    var recent by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var recentUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val ctx = LocalContext.current
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }
    LaunchedEffect(Unit) {
        try {
            val al = repo.api().assets("image").take(6)
            recent = al
            loader = repo.imageLoader()
            recentUrls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
        } catch (_: Exception) {}
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Genera immagine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Crea immagini con l'AI a partire da una descrizione.", color = Color.Gray) }
        item {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, minLines = 3,
                placeholder = { Text("Descrivi l'immagine che vuoi creare…") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text("Stile (solo modello di prompt, tracciato nel job)", style = MaterialTheme.typography.labelLarge)
            val styles = listOf("cinematico" to "Cinematico", "anime" to "Anime", "realistico" to "Realistico", "fantasy" to "Fantasy")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(styles) { (k, label) ->
                    FilterChip(selected = preset == k, onClick = { preset = k }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val formats = mapOf("1:1" to (1024 to 1024), "16:9" to (1344 to 768), "9:16" to (768 to 1344))
                formats.keys.forEach { k ->
                    FilterChip(selected = format == k, onClick = { format = k }, label = { Text(k) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Bozza", "Alta").forEach { q ->
                    FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q) })
                }
                listOf(1, 2, 4).forEach { n ->
                    FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n") })
                }
            }
            ElevatedCard {
                Column(Modifier.padding(12.dp)) {
                    Text("Immagini di riferimento (opzionale)", fontWeight = FontWeight.SemiBold)
                    Text("La pipeline image-to-image non è ancora validata end-to-end: UI disabilitata, non un pulsante morto.",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Button(onClick = {
                val dims = mapOf("1:1" to (1024 to 1024), "16:9" to (1344 to 768), "9:16" to (768 to 1344))[format] ?: (1024 to 1024)
                val steps = if (quality == "Bozza") 12 else 20
                state = "Accodamento…"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val api = repo.api()
                        repeat(count) {
                            api.createJob(
                                JobCreate("image", prompt, null, null, null, dims.first, dims.second, steps, null, preset,
                                    emptyList(), "app-${System.currentTimeMillis()}-$it-${prompt.hashCode()}")
                            )
                        }
                        state = "Inserito in coda: apri Home per lo stato reale (la coda sopravvive alla chiusura)."
                    } catch (e: Exception) { state = "Errore: ${e.message}" }
                }
            }, enabled = prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Genera immagine")
            }
            Text(state, color = Color.Gray)
            Text("La generazione continua sul server anche ad app chiusa.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Generazioni recenti", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { a ->
                    val url = recentUrls[a.id]
                    if (url != null && loader != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
                            imageLoader = loader!!,
                            contentDescription = a.id,
                            modifier = Modifier.size(110.dp), contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CreateVideoScreen() {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Crea video", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Anima un'immagine con l'AI, in modo semplice e veloce.", color = Color.Gray) }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF2A1530))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Video non disponibile su questo server", fontWeight = FontWeight.Bold)
                    Text("LICENSE_GATE: h3.enabled=false — la licenza community MiniMax esclude EU/Italy. " +
                        "I pesi sono presenti ma il manager li tiene disabilitati; nessuna generazione H3 finché " +
                        "il proprietario non fornisce autorizzazione scritta MiniMax.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC9D1))
                    Text("L'app mostra solo controlli realmente supportati: per ora nessun flusso text-to-video / image-to-video.",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Prompt di movimento (disabilitato: pipeline non autorizzata)") })
        }
        item {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Avvia rendering") }
        }
    }
}

@Composable
fun LibraryScreen(repo: StudioRepo) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tutto") }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var urls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }
    fun reload() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val api = repo.api()
                val fav = filter == "Preferiti"
                val kind = when (filter) {
                    "Immagini" -> "image"
                    "Video" -> "video"
                    else -> null
                }
                val all = if (fav) api.assets(favorite = true) else api.assets(kind = kind)
                loader = repo.imageLoader()
                val map = all.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
                urls = map
                assets = all.filter { a ->
                    q.isBlank() || a.id.contains(q, true) || (a.job_id ?: "").contains(q, true)
                }
                err = null
            } catch (e: Exception) { err = e.message }
        }
    }
    LaunchedEffect(filter) { reload() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Libreria", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Tutte le tue generazioni, organizzate sul server.", color = Color.Gray)
        OutlinedTextField(value = q, onValueChange = { q = it; reload() }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Cerca per ID job o asset…") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Tutto", "Immagini", "Video", "Preferiti")) { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
            }
        }
        if (err != null) Text("Offline: $err", color = Color(0xFFFF9D9D))
        LazyVerticalGrid(GridCells.Adaptive(140.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(assets) { a ->
                ElevatedCard {
                    val url = urls[a.id]
                    if (url != null && loader != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
                            imageLoader = loader!!,
                            contentDescription = a.id,
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(a.id.take(16), modifier = Modifier.padding(6.dp), maxLines = 1,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(repo: StudioRepo, updater: AppUpdateManager) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val updateState by updater.state.collectAsState()
    LaunchedEffect(Unit) {
        base = repo.baseUrl(); tok = repo.token()
        scope.launch { updater.checkForUpdates() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Impostazioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(base, { base = it }, label = { Text("Indirizzo backend (privato, es. http://hermes.tail85940c.ts.net:8650/)") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tok, { tok = it }, label = { Text("Token di pairing (mai hardcoded)") },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            repo.save(base, tok)
                            repo.testConnection()
                            msg = "Connessione OK (capabilities autenticate). Token salvato in DataStore."
                        } catch (e: Exception) { msg = "Errore: ${e.message}" }
                    }
                }) { Text("Salva e testa") }
            }
            Text(msg, color = Color.Gray)
            Text("Nessuna porta pubblica, nessuna telemetria esterna, nessun cloud. Solo tailnet privata + bearer.",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        item {
            ElevatedCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aggiornamenti app", fontWeight = FontWeight.Bold)
                    Text("Versione installata ${updateState.currentVersionName} (${updateState.currentVersionCode})",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text(updateState.statusMessage, style = MaterialTheme.typography.bodySmall,
                        color = if (updateState.signatureBlocked || updateState.lastError != null) Color(0xFFFF9D9D) else Color.Gray)
                    if (updateState.isDownloading) {
                        LinearProgressIndicator(progress = { updateState.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        if (updateState.downloadLabel.isNotBlank()) Text(updateState.downloadLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    updateState.lastError?.let { Text(it, color = Color(0xFFFF9D9D), style = MaterialTheme.typography.bodySmall) }
                    updateState.availableRelease?.let { r ->
                        Text("Release: ${r.versionName} (${r.versionCode})", color = AccentB, style = MaterialTheme.typography.bodyMedium)
                        r.notes.take(3).forEach { n -> Text("• $n", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { updater.checkForUpdates(force = true) } }, enabled = !updateState.isChecking && !updateState.isDownloading) {
                            Text(if (updateState.isChecking) "Controllo..." else "Controlla ora")
                        }
                        if (updateState.availableRelease != null && updateState.downloadedApkPath == null) {
                            TextButton(onClick = { scope.launch { updater.downloadAvailableUpdate() } }, enabled = !updateState.isDownloading) { Text("Scarica update") }
                        }
                        if (updateState.availableRelease != null && updateState.downloadedApkPath != null) {
                            TextButton(onClick = { updater.installDownloadedUpdate() }) { Text("Installa") }
                        }
                        updateState.rollbackVersionCode?.let { v ->
                            if (updateState.rollbackApkPath != null) {
                                TextButton(onClick = { updater.installRollbackUpdate() }) { Text("Torna a $v") }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Controllo automatico", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = updateState.autoCheckEnabled, onCheckedChange = { updater.setAutoCheckEnabled(it) })
                    }
                }
            }
        }
    }
}
