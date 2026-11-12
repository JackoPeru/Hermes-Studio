package com.hermes.studio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hermes.studio.*
import com.hermes.studio.update.AppUpdateManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// ---------- Design system (dai mockup approvati) ----------
val Bg = Color(0xFF0B0B12)
val Card = Color(0xFF171726)
val CardBorder = Color.White.copy(alpha = 0.08f)
val AccentV = Color(0xFF9B5CFF)
val AccentB = Color(0xFF4AA8FF)
val Good = Color(0xFF3DFF88)
val Warn = Color(0xFFFFC9D1)
val Muted = Color(0xFF9AA0B4)

val CardShape = RoundedCornerShape(18.dp)
val HeroGradient = Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF1B1740)))
val CtaGradient = Brush.horizontalGradient(listOf(AccentV, AccentB))
val BlueGradient = Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8)))
val Scrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))

/** ImageRequest stabile fra recompose: evita reload/flicker di Coil (perf). */
@Composable
fun rememberImgReq(url: String?, loader: coil.ImageLoader?): coil.request.ImageRequest? {
    val ctx = LocalContext.current
    return remember(url, loader) {
        if (url == null || loader == null) null
        else coil.request.ImageRequest.Builder(ctx).data(url).crossfade(true).build()
    }
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Card, surfaceVariant = Card,
            primary = AccentV, secondary = AccentB, tertiary = AccentV,
            onBackground = Color.White, onSurface = Color.White
        ),
        content = content
    )
}

// ---------- Condivisi ----------

@Composable
fun HermesTopBar(online: Boolean, label: String, onClick: () -> Unit) {
    Surface(color = Bg) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(CtaGradient),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).height(30.dp).background(Muted.copy(alpha = 0.4f)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Hermes Studio", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("AI CREATIVE SERVER", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, maxLines = 1)
            }
            Surface(
                shape = RoundedCornerShape(16.dp), color = Card,
                border = BorderStroke(1.dp, CardBorder), onClick = onClick
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) Good else Color.Red))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.widthIn(max = 130.dp)) {
                        Text(if (online) "Server connesso" else "Server offline", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label.ifBlank { "—" }, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun SectionHeader(icon: ImageVector, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AccentV, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(action, color = AccentV, fontSize = 14.sp, maxLines = 1)
                Icon(Icons.Filled.ChevronRight, null, tint = AccentV, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun GradientButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, blue: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(shape)
            .background(if (enabled) (if (blue) BlueGradient else CtaGradient) else Brush.linearGradient(listOf(Card, Card)))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f))
            Spacer(Modifier.width(10.dp))
            Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StatusPill(text: String, good: Boolean) {
    val c = if (good) Good else AccentV
    Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.dp, c.copy(alpha = 0.7f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (good) { Icon(Icons.Filled.Check, null, tint = c, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
            Text(text, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

fun jobLabel(s: String): String = when (s) {
    "queued" -> "In coda"
    "preparing" -> "Preparazione"
    "running" -> "Rendering"
    "completed" -> "Completato"
    "failed" -> "Fallito"
    "cancelling" -> "Annullamento"
    "cancelled" -> "Annullato"
    else -> s
}

fun jobGood(s: String): Boolean = s == "completed"

fun formatDuration(s: Double?): String? {
    if (s == null || s <= 0) return null
    val total = s.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

// ---------- Home (mockup 01) ----------

@Composable
fun HomeScreen(repo: StudioRepo, onNav: (String) -> Unit) {
    var caps by remember { mutableStateOf<Capabilities?>(null) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var urls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    var managerState by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    suspend fun refreshLight() {
        // Poll leggero: assegna solo se cambiato, niente recompose a vuoto (perf).
        try {
            val api = repo.api()
            val freshJobs = api.jobs(10)
            if (freshJobs != jobs) jobs = freshJobs
            val al = api.assets()
            if (al != assets) {
                assets = al
                urls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            }
        } catch (_: Exception) {}
    }
    suspend fun refresh() {
        try {
            // Parallelo: 3 round-trip in uno solo (perf su tailnet).
            val api = repo.api()
            val capsD = scope.async(kotlinx.coroutines.Dispatchers.IO) { api.capabilities() }
            caps = capsD.await()
            try {
                val st = api.status()
                val ms = st.manager_state.orEmpty()
                managerState = if (ms.isNotBlank()) {
                    "Manager ${st.manager_desired.orEmpty()} · $ms"
                } else ""
            } catch (_: Exception) {}
            refreshLight()
            loader = repo.imageLoader()
            err = null
        } catch (e: Exception) { err = e.message }
    }
    suspend fun reuse(a: Asset, go: (String) -> Unit) {
        val p = try { a.job_id?.let { repo.api().job(it).prompt } } catch (_: Exception) { null }
        go(if (p.isNullOrBlank()) "create" else "create?tab=image&prompt=" + Uri.encode(p))
    }
    var live by remember { mutableStateOf(false) }
    var lastEventAt by remember { mutableStateOf(0L) }
    var esRef by remember { mutableStateOf<okhttp3.sse.EventSource?>(null) }
    fun markEvent() { lastEventAt = System.currentTimeMillis() }
    LaunchedEffect(Unit) { refresh() }
    // SSE live dal server: aggiornamento istantaneo senza polling (perf + batteria).
    // Watchdog: se il proxy mette in buffer e non arriva nulla per 45s, si torna al poll.
    DisposableEffect(Unit) {
        val task = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                esRef = repo.watchEvents(object : okhttp3.sse.EventSourceListener() {
                    override fun onOpen(e: okhttp3.sse.EventSource, response: okhttp3.Response) {
                        scope.launch { live = true; markEvent() }
                    }
                    override fun onEvent(e: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                        scope.launch {
                            markEvent()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { refreshLight() }
                        }
                    }
                    override fun onFailure(e: okhttp3.sse.EventSource, t: Throwable?, r: okhttp3.Response?) {
                        scope.launch { live = false }
                    }
                })
            } catch (_: Exception) { scope.launch { live = false } }
        }
        onDispose { task.cancel(); try { esRef?.cancel() } catch (_: Exception) {}; esRef = null }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10000)
            if (live && System.currentTimeMillis() - lastEventAt > 45000) {
                try { esRef?.cancel() } catch (_: Exception) {}
                esRef = null
                live = false
            }
        }
    }
    // Fallback se SSE non disponibile + rete di sicurezza ogni 30s comunque.
    LaunchedEffect(live) {
        if (!live) {
            while (true) {
                kotlinx.coroutines.delay(15000)
                refreshLight()
            }
        } else {
            while (true) {
                kotlinx.coroutines.delay(30000)
                refreshLight()
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item {
            // Hero compatto (niente decorazione a tutto schermo): gradiente + glow.
            Box(
                Modifier.fillMaxWidth().clip(CardShape).background(HeroGradient)
                    .background(Brush.radialGradient(listOf(AccentV.copy(alpha = 0.35f), Color.Transparent), radius = 600f))
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bentornato\nin Hermes Studio", fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp)
                    Text("Crea immagini e video con l'AI del tuo server, in modo semplice e veloce.", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            GradientButton("Genera immagine", Icons.Filled.Image, onClick = { onNav("create") })
                        }
                        Box(Modifier.weight(1f)) {
                            GradientButton("Crea video", Icons.Filled.Videocam, onClick = { onNav("create?tab=video") }, blue = true)
                        }
                    }
                }
            }
        }
        if (err != null) item {
            Text("Offline: $err — i job restano sul server e si recuperano al ritorno.", color = Warn, fontSize = 13.sp)
        }
        item {
            SectionHeader(Icons.Filled.History, "Progetti recenti", "Vedi tutto", onAction = { onNav("library") })
            Spacer(Modifier.height(10.dp))
            if (assets.isEmpty()) {
                Text("Nessuna generazione ancora. Crea la prima immagine.", color = Muted, fontSize = 13.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(assets.take(6), key = { it.id }) { a ->
                        val req = rememberImgReq(urls[a.id], loader)
                        Box(
                            Modifier.size(150.dp, 190.dp).clip(RoundedCornerShape(16.dp)).background(Card)
                                .clickable { onNav("asset/${a.id}") }
                        ) {
                            if (req != null && loader != null) {
                                AsyncImage(
                                    model = req, imageLoader = loader!!,
                                    contentDescription = a.id,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                )
                            }
                            Box(Modifier.fillMaxSize().background(Scrim))
                            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (a.kind == "video") Icons.Filled.Videocam else Icons.Filled.Image, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        a.job_id?.take(18) ?: a.id.take(14),
                                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    if (a.width != null && a.height != null) "${a.width} × ${a.height}" else a.kind,
                                    color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1
                                )
                            }
                            if (a.job_id != null) {
                                Surface(
                                    shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                ) {
                                    IconButton(onClick = { scope.launch { reuse(a, onNav) } }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.ContentCopy, "Riusa prompt", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            val active = jobs.filter { it.status in listOf("queued", "preparing", "running", "cancelling") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Layers, null, tint = AccentV, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Coda di rendering (Server)", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (live) Text("· Live", color = Good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (active.isEmpty()) "Nessuna attività in corso" else "${active.size} attività in corso" +
                    (if (managerState.isNotBlank()) " · $managerState" else ""),
                color = Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (active.isEmpty()) {
                        Text("La coda è vuota. I nuovi job appariranno qui in tempo reale.", color = Muted, fontSize = 13.sp)
                    }
                    active.take(4).forEach { j ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Bg, border = BorderStroke(1.dp, CardBorder), onClick = { onNav("job/${j.id}") }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(j.prompt.take(48), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.width(8.dp))
                                    StatusPill(jobLabel(j.status), jobGood(j.status))
                                }
                                Text("${j.engine} · ${j.model}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { ((j.progress ?: 0.0).toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                                        color = AccentV, trackColor = Color.White.copy(alpha = 0.12f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("${(((j.progress ?: 0.0) * 100).toInt())}%", color = AccentV, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                if (j.status in listOf("queued", "running", "preparing")) {
                                    TextButton(onClick = { scope.launch { try { repo.api().cancel(j.id); refresh() } catch (_: Exception) {} } }, contentPadding = PaddingValues(0.dp)) {
                                        Text("Annulla job", color = Warn, fontSize = 13.sp)
                                    }
                                }
                                if (!j.error.isNullOrBlank()) {
                                    Text(j.error!!.take(160), color = Warn, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(Icons.Filled.AutoAwesome, "Modelli disponibili")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val img = caps?.image?.firstOrNull()
                val vid = caps?.video?.firstOrNull()
                Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentV.copy(alpha = 0.35f)), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(CtaGradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Text("Qwen Image", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Generazione immagini di alta qualità", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                        StatusPill(if (img?.enabled == true) "Pronto" else "Non pronto", img?.enabled == true)
                    }
                }
                Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentB.copy(alpha = 0.35f)), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BlueGradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Movie, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Text("MiniMax H3", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (vid?.enabled == true) "Generazione video image-to-video (turbo)" else "Video non pronto",
                            color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2
                        )
                        StatusPill(if (vid?.enabled == true) "Pronto" else "Disabilitato", vid?.enabled == true)
                    }
                }
            }
        }
    }
}

// ---------- Crea unificata (selettore Immagine/Video in top) ----------

@Composable
fun CreateScreen(
    repo: StudioRepo,
    initialTab: String? = null,
    initialPrompt: String? = null,
    onOpenAsset: (String) -> Unit
) {
    var tab by rememberSaveable(initialTab) { mutableStateOf(if (initialTab == "video") 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            SegmentedButton(
                selected = tab == 0, onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp)) },
                label = { Text("Immagine") }
            )
            SegmentedButton(
                selected = tab == 1, onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Filled.Videocam, null, modifier = Modifier.size(18.dp)) },
                label = { Text("Video") }
            )
        }
        if (tab == 0) {
            CreateImageScreen(repo, initialPrompt = initialPrompt, showHeader = false)
        } else {
            CreateVideoScreen(repo, onOpenAsset = onOpenAsset, showHeader = false)
        }
    }
}

// ---------- Genera immagine (mockup 02) ----------

data class StylePreset(val key: String, val label: String, val icon: ImageVector)

@Composable
fun CreateImageScreen(repo: StudioRepo, initialPrompt: String? = null, onDone: (() -> Unit)? = null, showHeader: Boolean = true) {
    val scope = rememberCoroutineScope()
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt ?: "") }
    var preset by remember { mutableStateOf("cinematico") }
    var format by remember { mutableStateOf("1:1") }
    var count by remember { mutableStateOf(1) }
    var state by remember { mutableStateOf("Pronto") }
    // Fino a 2 riferimenti (limite reale del template Qwen edit).
    var refIds by remember { mutableStateOf(setOf<String>()) }
    var recent by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var recentUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    val styles = listOf(
        StylePreset("cinematico", "Cinematico", Icons.Filled.Movie),
        StylePreset("anime", "Anime", Icons.Filled.Face),
        StylePreset("realistico", "Realistico", Icons.Filled.CameraAlt),
        StylePreset("fantasy", "Fantasy", Icons.Filled.AutoAwesome)
    )
    // Tier nomi familiari, pixel esatti. Tetto immagini 2K (2560×1440) dal manager;
    // lo snap a 32px resta di sicurezza (dettaglio job mostra i reali).
    fun resOptions(f: String): List<Triple<String, Int, Int>> = when (f) {
        "16:9" -> listOf(
            Triple("2K · 2560 × 1440", 2560, 1440),
            Triple("Full HD · 1920 × 1080", 1920, 1080),
            Triple("HD · 1280 × 720", 1280, 720),
            Triple("480p · 854 × 480", 854, 480)
        )
        "9:16" -> listOf(
            Triple("2K · 1440 × 2560", 1440, 2560),
            Triple("Full HD · 1080 × 1920", 1080, 1920),
            Triple("HD · 720 × 1280", 720, 1280),
            Triple("480p · 480 × 854", 480, 854)
        )
        else -> listOf(
            Triple("2K · 1920 × 1920", 1920, 1920),
            Triple("Full HD · 1080 × 1080", 1080, 1080),
            Triple("HD · 720 × 720", 720, 720),
            Triple("480p · 480 × 480", 480, 480)
        )
    }
    // Passi fissi: la qualità si sceglie con la risoluzione, non con un flag separato.
    val fixedSteps = 20

    suspend fun refreshRecent() {
        try {
            val al = repo.api().assets("image").take(6)
            recent = al
            loader = repo.imageLoader()
            recentUrls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
        } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) { refreshRecent() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = "Caricamento foto…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val up = repo.uploadImage(uri)
                val id = up.asset_id.ifBlank { null } ?: up.asset_upload_id
                if (refIds.size >= 2 && !refIds.contains(id)) {
                    state = "Max 2 riferimenti: deselezionane uno prima."
                } else {
                    refIds = refIds + id
                    state = "Foto caricata: verrà usata come riferimento."
                }
                refreshRecent()
            } catch (e: Exception) { state = "Upload fallito: ${e.message}" }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        if (showHeader) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Genera immagine", fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Text("Crea immagini con l'AI a partire da una descrizione.", color = Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentV.copy(alpha = 0.35f))) {
                Column(Modifier.padding(6.dp)) {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, minLines = 3,
                        placeholder = { Text("Descrivi l'immagine che vuoi creare…") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (prompt.isNotEmpty()) {
                                IconButton(onClick = { prompt = "" }) { Icon(Icons.Filled.Close, "Pulisci", tint = Muted) }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(styles, key = { it.key }) { s ->
                    val sel = preset == s.key
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = Card,
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) AccentV else CardBorder),
                        onClick = { preset = s.key }
                    ) {
                        Box(Modifier.size(104.dp, 118.dp)) {
                            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A1B4E), Color(0xFF141428)))))
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(s.icon, null, tint = if (sel) AccentV else Muted, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            if (sel) {
                                Surface(shape = CircleShape, color = AccentV, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            val resOpts = resOptions(format)
            // Default prudente: Full HD (indice 1), non il massimo. Scelta esplicita rispettata.
            val resSel = resOpts.firstOrNull { it.first == resolution } ?: resOpts.getOrNull(1) ?: resOpts.first()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { DropDownField("Formato", format, listOf("1:1", "16:9", "9:16"), { format = it }, Icons.Filled.Crop) }
                    Box(Modifier.weight(1f)) {
                        DropDownField(
                            "Risoluzione", resSel.first,
                            resOpts.map { it.first },
                            { resolution = it }, Icons.Filled.AspectRatio
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { DropDownField("Numero", "$count", listOf("1", "2", "4"), { count = it.toInt() }, Icons.Filled.Layers) }
                }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Immagini di riferimento (opzionale)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${refIds.size}/2", color = Muted, fontSize = 13.sp)
                        if (refIds.isNotEmpty()) {
                            TextButton(onClick = { refIds = emptySet() }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("Rimuovi", color = Warn, fontSize = 13.sp)
                            }
                        }
                    }
                    Text(
                        if (refIds.isEmpty()) "Con riferimenti, il job diventa ritocco (preset edit Qwen, max 2)."
                        else "Ritocco attivo (${refIds.size}): descrivi la modifica nel prompt.",
                        color = Muted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp), color = Color.Transparent,
                                border = BorderStroke(1.dp, AccentV.copy(alpha = 0.6f)),
                                onClick = { picker.launch("image/*") }
                            ) {
                                Column(
                                    Modifier.size(104.dp, 118.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Filled.AddPhotoAlternate, null, tint = AccentV, modifier = Modifier.size(26.dp))
                                    Text("Carica foto", color = AccentV, fontSize = 12.sp)
                                }
                            }
                        }
                        items(recent, key = { it.id }) { a ->
                            val req = rememberImgReq(recentUrls[a.id], loader)
                            val sel = refIds.contains(a.id)
                            Surface(
                                shape = RoundedCornerShape(12.dp), color = Card,
                                border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) AccentV else CardBorder),
                                onClick = {
                                    refIds = if (sel) refIds - a.id
                                    else if (refIds.size >= 2) refIds
                                    else refIds + a.id
                                }
                            ) {
                                Box(Modifier.size(104.dp, 118.dp)) {
                                    if (req != null && loader != null) {
                                        AsyncImage(
                                            model = req, imageLoader = loader!!, contentDescription = a.id,
                                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                        )
                                    }
                                    if (sel) {
                                        Surface(shape = CircleShape, color = AccentV, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentV.copy(alpha = 0.25f))) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(CtaGradient), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Qwen Image", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Generazione immagini di alta qualità", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        StatusPill("Pronto", true)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Muted)
                }
            }
        }
        item {
            val isEdit = refIds.isNotEmpty()
            GradientButton(if (isEdit) "Ritocca immagine" else "Genera immagine", Icons.Filled.AutoAwesome, enabled = prompt.isNotBlank(), onClick = {
                val opts = resOptions(format)
                val sel = opts.firstOrNull { it.first == resolution } ?: opts.first()
                val steps = fixedSteps
                val refs = refIds.toList()
                state = "Accodamento…"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val api = repo.api()
                        repeat(count) {
                            api.createJob(JobCreate("image", prompt, null, null, null, sel.second, sel.third, steps, null, null, preset, refs, "app-${System.currentTimeMillis()}-$it-${prompt.hashCode()}"))
                        }
                        state = (if (isEdit) "Ritocco" else "Generazione") + " in coda (${sel.second}×${sel.third}, $steps passi): segui lo stato in Home."
                        refreshRecent()
                        onDone?.invoke()
                    } catch (e: Exception) { state = "Errore: ${e.message}" }
                }
            })
            Spacer(Modifier.height(4.dp))
            Text(state, color = Muted, fontSize = 13.sp)
            Text("La generazione continua sul server anche ad app chiusa.", color = Muted, fontSize = 12.sp)
        }
        item {
            SectionHeader(Icons.Filled.History, "Generazioni recenti")
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it.id }) { a ->
                    val req = rememberImgReq(recentUrls[a.id], loader)
                    Box(Modifier.size(150.dp, 170.dp).clip(RoundedCornerShape(14.dp)).background(Card)) {
                        if (req != null && loader != null) {
                            AsyncImage(
                                model = req, imageLoader = loader!!, contentDescription = a.id,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropDownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit, icon: ImageVector) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = !open }) {
        Surface(shape = RoundedCornerShape(14.dp), color = Card, border = BorderStroke(1.dp, CardBorder)) {
            Row(
                Modifier.menuAnchor().fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = AccentV, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ExpandMore, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

// ---------- Crea video (mockup 03, image-to-video H3 turbo) ----------

@Composable
fun CreateVideoScreen(repo: StudioRepo, onOpenAsset: (String) -> Unit, onDone: (() -> Unit)? = null, showHeader: Boolean = true) {
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var imgUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var recentVideos by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var videoUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var prompt by remember { mutableStateOf("") }
    // Solo 720p/480p: le uniche risoluzioni offerte per H3 (il manager allinea ai multipli di 32).
    val videoFormats = mapOf(
        "16:9 · 720p" to (1280 to 720),
        "16:9 · 480p" to (854 to 480),
        "9:16 · 720p" to (720 to 1280),
        "9:16 · 480p" to (480 to 854)
    )
    var format by remember { mutableStateOf("16:9 · 720p") }
    // Max start frame reali: 1 per 16:9, 2 per 9:16 (template H3 verificati).
    fun maxRefs(): Int = if (format.startsWith("9:16")) 2 else 1
    var duration by remember { mutableStateOf("5 s") }
    var state by remember { mutableStateOf("Pronto") }
    var capsVideo by remember { mutableStateOf<com.hermes.studio.CapTask?>(null) }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }
    val maxPrompt = 1000

    val camHints = listOf(
        "Dolly in" to "dolly in",
        "Pan" to "panoramica laterale",
        "Loop" to "loop continuo",
        "Cinematico" to "stile cinematografico"
    )

    suspend fun refresh() {
        try {
            val api = repo.api()
            capsVideo = api.capabilities().video.firstOrNull { it.task == "image-to-video" }
            val imgs = api.assets("image").take(12)
            images = imgs
            loader = repo.imageLoader()
            imgUrls = imgs.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            val vids = api.assets("video").take(6)
            recentVideos = vids
            videoUrls = vids.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            selectedIds = selectedIds.filter { id -> imgs.any { it.id == id } }.take(maxRefs()).toSet()
        } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) { refresh() }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = "Caricamento foto…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val up = repo.uploadImage(uri)
                val id = up.asset_id.ifBlank { null } ?: up.asset_upload_id
                if (selectedIds.size >= maxRefs() && !selectedIds.contains(id)) {
                    state = "Max ${maxRefs()} start frame: deselezionane uno prima."
                } else {
                    selectedIds = selectedIds + id
                    state = "Foto caricata e selezionata."
                }
                refresh()
            } catch (e: Exception) { state = "Upload fallito: ${e.message}" }
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        if (showHeader) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Videocam, null, tint = AccentV, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Crea video", fontWeight = FontWeight.Bold, fontSize = 28.sp)
                }
                Text("Anima un'immagine con l'AI, in modo semplice e veloce.", color = Muted, fontSize = 14.sp)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(Icons.Filled.Image, "Immagine di partenza")
                Spacer(Modifier.weight(1f))
                Text("${selectedIds.size}/${maxRefs()}", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (images.isEmpty()) {
                Text("Nessuna immagine in libreria: genera o carica prima un'immagine.", color = Muted, fontSize = 13.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = Color.Transparent,
                            border = BorderStroke(1.dp, AccentB.copy(alpha = 0.6f)),
                            onClick = { videoPicker.launch("image/*") }
                        ) {
                            Column(
                                Modifier.size(130.dp, 150.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Filled.AddPhotoAlternate, null, tint = AccentB, modifier = Modifier.size(26.dp))
                                Text("Carica foto", color = AccentB, fontSize = 12.sp)
                            }
                        }
                    }
                    items(images, key = { it.id }) { a ->
                        val req = rememberImgReq(imgUrls[a.id], loader)
                        val sel = selectedIds.contains(a.id)
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = Card,
                            border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) AccentV else CardBorder),
                            onClick = {
                                selectedIds = if (sel) selectedIds - a.id
                                else if (selectedIds.size >= maxRefs()) selectedIds
                                else selectedIds + a.id
                            }
                        ) {
                            Box(Modifier.size(130.dp, 150.dp)) {
                                if (req != null && loader != null) {
                                    AsyncImage(
                                        model = req, imageLoader = loader!!, contentDescription = a.id,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                    )
                                }
                                if (sel) {
                                    Surface(shape = CircleShape, color = AccentV, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                                        Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = AccentV, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Prompt di movimento", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${prompt.length} / $maxPrompt", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentV.copy(alpha = 0.35f))) {
                OutlinedTextField(
                    value = prompt, onValueChange = { if (it.length <= maxPrompt) prompt = it },
                    minLines = 3, placeholder = { Text("Descrivi il movimento: che cosa si anima e come…") },
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
                )
            }
        }
        item {
            Text("Stile di movimento", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("I suggerimenti vengono aggiunti al prompt come testo.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(camHints, key = { it.first }) { (label, phrase) ->
                    val active = prompt.contains(phrase, true)
                    Surface(
                        shape = RoundedCornerShape(24.dp), color = if (active) AccentV.copy(alpha = 0.28f) else Card,
                        border = BorderStroke(1.dp, if (active) AccentV else CardBorder),
                        onClick = {
                            prompt = if (active) {
                                prompt.replace(phrase, "", true).replace(Regex("\\s{2,}"), " ").trim(' ', ',', '.', ';')
                            } else {
                                (if (prompt.isBlank()) "" else prompt.trimEnd() + ", ") + phrase
                            }.take(maxPrompt)
                        }
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DropDownField("Formato", format, videoFormats.keys.toList(), { format = it }, Icons.Filled.Crop) }
                Box(Modifier.weight(1f)) { DropDownField("Durata", duration, listOf("3 s", "5 s", "8 s"), { duration = it }, Icons.Filled.Schedule) }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, AccentB.copy(alpha = 0.25f))) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(BlueGradient), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Movie, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MiniMax H3 turbo", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Image-to-video 4-step, preset manager verificato", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        StatusPill(if (capsVideo?.enabled == true) "Pronto" else "Non pronto", capsVideo?.enabled == true)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Muted)
                }
            }
        }
        item {
            val dims = videoFormats[format] ?: (1280 to 720)
            val durSecs = duration.substringBefore(" ").toDoubleOrNull() ?: 5.0
            val sids = selectedIds.toList()
            GradientButton("Avvia rendering", Icons.Filled.PlayArrow, enabled = sids.isNotEmpty() && prompt.isNotBlank(), onClick = {
                if (sids.isEmpty()) return@GradientButton
                state = "Accodamento…"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        repo.api().createJob(
                            JobCreate("video", prompt, null, "minimax-h3-turbo-4step", null, dims.first, dims.second, 4, null, durSecs, null, sids, "app-v-${System.currentTimeMillis()}-${prompt.hashCode()}")
                        )
                        state = "Inserito in coda (~${durSecs.toInt()} s, ${sids.size} start): segui lo stato in Home."
                        onDone?.invoke()
                    } catch (e: Exception) { state = "Errore: ${e.message}" }
                }
            })
            Spacer(Modifier.height(4.dp))
            Text(state, color = Muted, fontSize = 13.sp)
            Text("Il rendering gira sul server anche ad app chiusa.", color = Muted, fontSize = 12.sp)
        }
        item {
            SectionHeader(Icons.Filled.History, "Video recenti", "Vedi tutto", onAction = { onOpenAsset("library") })
            Spacer(Modifier.height(10.dp))
            if (recentVideos.isEmpty()) {
                Text("Nessun video ancora. Il primo rendering apparirà qui.", color = Muted, fontSize = 13.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recentVideos, key = { it.id }) { a ->
                        val req = rememberImgReq(videoUrls[a.id], loader)
                        Surface(shape = RoundedCornerShape(14.dp), color = Card, border = BorderStroke(1.dp, CardBorder), onClick = { onOpenAsset("asset/${a.id}") }) {
                            Box(Modifier.size(170.dp, 130.dp)) {
                                if (req != null && loader != null) {
                                    AsyncImage(
                                        model = req, imageLoader = loader!!, contentDescription = a.id,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                    )
                                }
                                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.Center)) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(10.dp).size(26.dp))
                                }
                                formatDuration(a.duration)?.let { d ->
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                                        Text(d, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Libreria (mockup 04) ----------

@Composable
fun LibraryScreen(repo: StudioRepo, onAsset: (String) -> Unit, onReuse: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Tutto") }
    var sortNewest by remember { mutableStateOf(true) }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var favIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var urls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    var showNewProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    suspend fun reload() {
        try {
            // Un solo giro di rete: lista intera + preferiti + progetti in parallelo.
            val api = repo.api()
            val kind = when (filter) {
                "Immagini" -> "image"
                "Video" -> "video"
                else -> null
            }
            val allD = scope.async(kotlinx.coroutines.Dispatchers.IO) { api.assets(kind = kind) }
            val favD = scope.async(kotlinx.coroutines.Dispatchers.IO) {
                try { api.assets(favorite = true).map { it.id }.toSet() } catch (_: Exception) { favIds }
            }
            val prjD = scope.async(kotlinx.coroutines.Dispatchers.IO) {
                try { api.projects() } catch (_: Exception) { emptyList() }
            }
            val all = allD.await()
            if (all != assets) {
                assets = all
                urls = all.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            }
            favIds = favD.await()
            projects = prjD.await()
            loader = repo.imageLoader()
            err = null
        } catch (e: Exception) { err = e.message }
    }
    // Filtro/sort/ricerca TUTTI client-side: zero rete mentre digiti (perf).
    val visible by remember(q, assets, filter, sortNewest, favIds) {
        mutableStateOf(
            assets.filter { a ->
                (filter != "Preferiti" || favIds.contains(a.id)) &&
                    (q.isBlank() || a.id.contains(q, true) || (a.job_id ?: "").contains(q, true))
            }.let { if (sortNewest) it.sortedByDescending { a -> a.created_at } else it.sortedBy { a -> a.created_at } }
        )
    }
    fun toggleFav(a: Asset) {
        val on = !favIds.contains(a.id)
        favIds = if (on) favIds + a.id else favIds - a.id // ottimistico: UI immediata
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                repo.api().favorite(a.id, on)
            } catch (_: Exception) {
                favIds = if (on) favIds - a.id else favIds + a.id // rollback
            }
        }
    }
    fun reuse(a: Asset) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val p = try { a.job_id?.let { repo.api().job(it).prompt } } catch (_: Exception) { null }
            onReuse(if (p.isNullOrBlank()) "create" else "create?tab=image&prompt=" + Uri.encode(p))
        }
    }
    LaunchedEffect(filter) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { reload() }
    }
    val filters = listOf("Tutto" to Icons.Filled.Apps, "Immagini" to Icons.Filled.Image, "Video" to Icons.Filled.Videocam, "Preferiti" to Icons.Filled.Favorite)
    // Un'unica griglia scrollabile: header compatto che scorre via, chips sticky,
    // contenuto al centro. Niente più colonna fissa che ruba metà schermo.
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Libreria", fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${visible.size}", color = Muted, fontSize = 14.sp)
                }
                Text("Tutte le tue generazioni, sul server.", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        modifier = Modifier.weight(1f), placeholder = { Text("Cerca nelle tue generazioni…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
                        shape = RoundedCornerShape(24.dp), singleLine = true
                    )
                    Surface(shape = RoundedCornerShape(24.dp), color = Card, border = BorderStroke(1.dp, AccentV.copy(alpha = 0.5f))) {
                        TextButton(onClick = { sortNewest = !sortNewest }) {
                            Icon(Icons.Filled.Tune, null, tint = AccentV, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (sortNewest) "Recenti" else "Vecchi", color = AccentV, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                if (err != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Offline: $err", color = Warn, fontSize = 13.sp)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters, key = { it.first }) { (f, ic) ->
                    val sel = filter == f
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (sel) AccentV.copy(alpha = 0.28f) else Card,
                        border = BorderStroke(1.dp, if (sel) AccentV else CardBorder),
                        onClick = { filter = f }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, null, tint = if (sel) Color.White else Muted, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(f, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader(Icons.Filled.Layers, "I tuoi progetti")
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(projects, key = { it.id }) { p ->
                        val req = rememberImgReq(urls[p.asset_ids.firstOrNull()], loader)
                        Surface(shape = RoundedCornerShape(16.dp), color = Card, border = BorderStroke(1.dp, CardBorder)) {
                            Box(Modifier.size(140.dp, 112.dp)) {
                                if (req != null && loader != null) {
                                    AsyncImage(
                                        model = req, imageLoader = loader!!, contentDescription = p.name,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2A1B4E), Color(0xFF141428)))))
                                }
                                Box(Modifier.fillMaxSize().background(Scrim))
                                Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Folder, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("${p.asset_ids.size} elementi", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp), color = Color.Transparent,
                            border = BorderStroke(1.dp, AccentV.copy(alpha = 0.5f)), onClick = { showNewProject = true }
                        ) {
                            Column(Modifier.size(100.dp, 112.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Filled.Add, null, tint = AccentV, modifier = Modifier.size(26.dp))
                                Text("Nuova\nraccolta", color = AccentV, fontSize = 12.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(Icons.Filled.History, "Generazioni (${visible.size})")
        }
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (filter == "Preferiti") "Nessun preferito: tocca il cuore su un elemento."
                    else "Nessun elemento qui.",
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
            items(visible, key = { it.id }) { a ->
                val req = rememberImgReq(urls[a.id], loader)
                val isFav = favIds.contains(a.id)
                Box(
                    Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(14.dp)).background(Card)
                        .clickable { onAsset("asset/${a.id}") }
                ) {
                    if (req != null && loader != null) {
                        AsyncImage(
                            model = req, imageLoader = loader!!, contentDescription = a.id,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                    Box(Modifier.fillMaxSize().background(Scrim))
                    Surface(
                        shape = CircleShape, color = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    ) {
                        IconButton(onClick = { toggleFav(a) }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                "Preferito", tint = if (isFav) Color(0xFFFF6EA9) else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    if (a.kind == "video") {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                formatDuration(a.duration)?.let { d ->
                                    Spacer(Modifier.width(3.dp))
                                    Text(d, color = Color.White, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (a.kind == "video") Icons.Filled.Videocam else Icons.Filled.Image, null, tint = Color.White, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                a.job_id?.take(14) ?: a.id.take(10),
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            if (a.width != null && a.height != null) "${a.width} × ${a.height}" else a.kind,
                            color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1
                        )
                    }
                    if (a.job_id != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        ) {
                            IconButton(onClick = { reuse(a) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.ContentCopy, "Riusa", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    if (showNewProject) {
        AlertDialog(
            onDismissRequest = { showNewProject = false; newProjectName = "" },
            title = { Text("Nuova raccolta") },
            text = {
                OutlinedTextField(
                    value = newProjectName, onValueChange = { newProjectName = it },
                    placeholder = { Text("Nome raccolta") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            if (newProjectName.isNotBlank()) repo.api().createProject(ProjectCreate(newProjectName.trim()))
                            reload()
                        } catch (_: Exception) {}
                    }
                    showNewProject = false; newProjectName = ""
                }) { Text("Crea", color = AccentV) }
            },
            dismissButton = { TextButton(onClick = { showNewProject = false; newProjectName = "" }) { Text("Annulla", color = Muted) } }
        )
    }
}

// ---------- Impostazioni ----------

@Composable
fun SettingsScreen(repo: StudioRepo, updater: AppUpdateManager) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf<StorageOut?>(null) }
    var queueLen by remember { mutableStateOf<Int?>(null) }
    var gpus by remember { mutableStateOf<List<GpuInfo>>(emptyList()) }
    var gpuErr by remember { mutableStateOf<String?>(null) }
    var gpuLive by remember { mutableStateOf(false) }
    val updateState by updater.state.collectAsState()
    suspend fun refreshSystem(includeStorage: Boolean = true) {
        if (includeStorage) {
            try {
                val s = repo.api().status()
                storage = s.storage; queueLen = s.queue_length
            } catch (_: Exception) {}
        }
        try {
            gpus = repo.api().gpu()
            gpuErr = null
            gpuLive = true
        } catch (e: Exception) { gpuErr = e.message; gpuLive = false }
    }
    LaunchedEffect(Unit) {
        base = repo.baseUrl(); tok = repo.token()
        scope.launch { refreshSystem() }
        scope.launch { updater.checkForUpdates() }
    }
    // GPU live: refresh ogni 5s finché Impostazioni è visibile (perf: 1 call leggera).
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            try {
                gpus = repo.api().gpu()
                gpuErr = null
                gpuLive = true
            } catch (e: Exception) { gpuErr = e.message; gpuLive = false }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item { Text("Impostazioni", fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(Icons.Filled.Cloud, "Connessione server")
                    OutlinedTextField(
                        base, { base = it }, label = { Text("Indirizzo backend privato") },
                        placeholder = { Text("http://hermes.tail85940c.ts.net:8650/") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        tok, { tok = it }, label = { Text("Token di pairing") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    GradientButton("Salva e testa", Icons.Filled.Check, onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                repo.save(base, tok)
                                repo.testConnection()
                                refreshSystem()
                                msg = "Connessione OK (capabilities autenticate)."
                            } catch (e: Exception) { msg = "Errore: ${e.message}" }
                        }
                    })
                    if (msg.isNotBlank()) Text(msg, color = Muted, fontSize = 13.sp)
                    storage?.let {
                        Text(
                            "Spazio server: ${"%.1f".format(it.free_gb)} GB liberi su ${"%.0f".format(it.total_gb)} GB" +
                                (queueLen?.let { q -> " · $q job in coda" } ?: ""),
                            color = Muted, fontSize = 13.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val r = repo.api().prune(PruneBody())
                                    msg = "Pulizia: ${r.deleted_jobs} job, ${r.deleted_uploads} file (${r.freed_bytes / 1024} KB)."
                                    refreshSystem()
                                } catch (e: Exception) { msg = "Pulizia fallita: ${e.message}" }
                            }
                        }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Filled.DeleteSweep, null, tint = AccentV, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pulisci cronologia (falliti >30gg, upload >7gg)", color = AccentV, fontSize = 13.sp)
                        }
                    }
                    Text(
                        "Nessuna porta pubblica, nessuna telemetria esterna, nessun cloud. Solo tailnet privata + bearer.",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(Icons.Filled.Memory, "GPU server")
                        Spacer(Modifier.weight(1f))
                        if (gpuLive) Text("· Live", color = Good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { refreshSystem(false) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, "Aggiorna GPU", tint = AccentV, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (gpuErr != null) {
                        Text("GPU non leggibili: $gpuErr", color = Warn, fontSize = 13.sp)
                    } else if (gpus.isEmpty()) {
                        Text("Lettura GPU in corso…", color = Muted, fontSize = 13.sp)
                    }
                    gpus.forEach { g ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Bg, border = BorderStroke(1.dp, CardBorder)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("GPU ${g.index}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    if (g.temp_c != null) Text("${g.temp_c.toInt()}°C", color = if (g.temp_c >= 80) Warn else Good, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text(g.name.ifBlank { "—" }, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val used = g.mem_used_mb ?: 0.0
                                val total = g.mem_total_mb ?: 0.0
                                val frac = if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                                        color = AccentB, trackColor = Color.White.copy(alpha = 0.12f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (total > 0) "${"%.1f".format(used / 1024)} / ${"%.0f".format(total / 1024)} GB" else "—",
                                        color = Muted, fontSize = 12.sp, maxLines = 1
                                    )
                                }
                                val u = g.util_pct
                                Text(
                                    if (u != null) "Computazione: ${u.toInt()}%" else "Computazione: —",
                                    color = Muted, fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(Icons.Filled.Refresh, "Aggiornamenti app")
                    Text(
                        "Versione installata ${updateState.currentVersionName} (${updateState.currentVersionCode})",
                        color = Muted, fontSize = 13.sp
                    )
                    Text(
                        updateState.statusMessage, fontSize = 13.sp,
                        color = if (updateState.signatureBlocked || updateState.lastError != null) Warn else Muted
                    )
                    if (updateState.isDownloading) {
                        LinearProgressIndicator(
                            progress = { updateState.downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = AccentV, trackColor = Color.White.copy(alpha = 0.12f)
                        )
                        if (updateState.downloadLabel.isNotBlank()) Text(updateState.downloadLabel, color = Muted, fontSize = 12.sp)
                    }
                    updateState.lastError?.let { Text(it, color = Warn, fontSize = 13.sp) }
                    updateState.availableRelease?.let { r ->
                        Text("Release: ${r.versionName} (${r.versionCode})", color = AccentB, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        r.notes.take(3).forEach { n -> Text("• $n", color = Muted, fontSize = 13.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scope.launch { updater.checkForUpdates(force = true) } }, enabled = !updateState.isChecking && !updateState.isDownloading) {
                            Text(if (updateState.isChecking) "Controllo..." else "Controlla ora", color = AccentV, fontSize = 13.sp)
                        }
                        if (updateState.availableRelease != null && updateState.downloadedApkPath == null) {
                            TextButton(onClick = { scope.launch { updater.downloadAvailableUpdate() } }, enabled = !updateState.isDownloading) {
                                Text("Scarica update", color = AccentV, fontSize = 13.sp)
                            }
                        }
                        if (updateState.availableRelease != null && updateState.downloadedApkPath != null) {
                            TextButton(onClick = { updater.installDownloadedUpdate() }) { Text("Installa", color = AccentV, fontSize = 13.sp) }
                        }
                        updateState.rollbackVersionCode?.let { v ->
                            if (updateState.rollbackApkPath != null) {
                                TextButton(onClick = { updater.installRollbackUpdate() }) { Text("Torna a $v", color = AccentV, fontSize = 13.sp) }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Controllo automatico", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = updateState.autoCheckEnabled, onCheckedChange = { updater.setAutoCheckEnabled(it) })
                    }
                }
            }
        }
    }
}

// ---------- Dettaglio asset (V2) ----------

@Composable
fun AssetDetailScreen(repo: StudioRepo, assetId: String, onReusePrompt: (String) -> Unit, onDeleted: () -> Unit, onViewer: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var asset by remember { mutableStateOf<Asset?>(null) }
    var jobPrompt by remember { mutableStateOf<String?>(null) }
    var promptFull by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }
    var imgUrl by remember { mutableStateOf<String?>(null) }
    val req = rememberImgReq(imgUrl, loader)

    suspend fun load() {
        try {
            val a = repo.api().asset(assetId)
            asset = a
            loader = repo.imageLoader()
            imgUrl = repo.assetUrl(a, true) ?: repo.assetUrl(a, false)
            isFav = try { repo.api().assets(favorite = true).any { it.id == assetId } } catch (_: Exception) { isFav }
            jobPrompt = try { a.job_id?.let { repo.api().job(it).prompt } } catch (_: Exception) { null }
            projects = try { repo.api().projects() } catch (_: Exception) { emptyList() }
        } catch (e: Exception) { msg = "Errore: ${e.message}" }
    }
    LaunchedEffect(assetId) { scope.launch(kotlinx.coroutines.Dispatchers.IO) { load() } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item { Text("Dettaglio", fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                val ar = asset?.let { a ->
                    if (a.width != null && a.height != null && a.height > 0) {
                        (a.width.toFloat() / a.height).coerceIn(0.4f, 2.5f)
                    } else 1f
                } ?: 1f
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ar).clip(CardShape)
                        .background(Color(0xFF141428)).clickable {
                            // Solo immagini: i video si riproducono inline qui sotto.
                            asset?.takeIf { it.kind != "video" }?.let { onViewer("viewer/${it.id}") }
                        }
                ) {
                    if (asset?.kind == "video" && asset != null) {
                        VideoPlayerBox(repo, asset!!, Modifier.fillMaxSize(), onError = { msg = it })
                    } else if (req != null && loader != null && asset != null) {
                        AsyncImage(
                            model = req, imageLoader = loader!!, contentDescription = asset!!.id,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
        asset?.let { a ->
            item {
                Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (a.kind == "video") "Video" else "Immagine", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            StatusPill(if (isFav) "Preferito" else a.kind, isFav)
                        }
                        if (a.width != null && a.height != null) Text("Risoluzione: ${a.width} × ${a.height}", color = Muted, fontSize = 14.sp)
                        formatDuration(a.duration)?.let { Text("Durata: $it", color = Muted, fontSize = 14.sp) }
                        a.job_id?.let { Text("Job: $it", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        jobPrompt?.let {
                            Text(it, fontSize = 14.sp, maxLines = if (promptFull) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { promptFull = !promptFull }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (promptFull) "Mostra meno" else "Mostra tutto", color = AccentV, fontSize = 13.sp)
                            }
                        }
                        if (msg.isNotBlank()) Text(msg, color = Warn, fontSize = 13.sp)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            GradientButton("Scarica", Icons.Filled.Download, onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        repo.saveToGallery(a)
                                        msg = "Salvato in galleria."
                                    } catch (e: Exception) { msg = "Download fallito: ${e.message}" }
                                }
                            })
                        }
                        Box(Modifier.weight(1f)) {
                            GradientButton("Condividi", Icons.Filled.Share, blue = true, onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val f = repo.downloadToCache(a)
                                        ctx.startActivity(android.content.Intent.createChooser(repo.shareFile(f, a.kind), "Condividi"))
                                    } catch (e: Exception) { msg = "Condivisione fallita: ${e.message}" }
                                }
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            GradientButton(if (isFav) "Rimuovi preferito" else "Preferito", Icons.Filled.Favorite, onClick = {
                                val to = !isFav
                                isFav = to
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try { repo.api().favorite(a.id, to) } catch (_: Exception) { isFav = !to }
                                }
                            })
                        }
                        Box(Modifier.weight(1f)) {
                            GradientButton("Progetto", Icons.Filled.Folder, blue = true, onClick = { showProjects = true })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Riusa prompt solo per immagini (per i video il prompt è movimento, non soggetto).
                        if (a.job_id != null && a.kind != "video") {
                            Box(Modifier.weight(1f)) {
                                GradientButton("Riusa prompt", Icons.Filled.ContentCopy, onClick = {
                                    val p = jobPrompt
                                    onReusePrompt(if (p.isNullOrBlank()) "create" else "create?tab=image&prompt=" + Uri.encode(p))
                                })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            GradientButton("Elimina", Icons.Filled.Delete, onClick = { showDelete = true })
                        }
                    }
                }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Eliminare questo elemento?") },
            text = { Text("File, miniatura e riferimenti verranno rimossi dal server. L'azione è definitiva.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try { repo.deleteAsset(assetId); onDeleted() }
                        catch (e: Exception) { msg = "Eliminazione fallita: ${e.message}" }
                    }
                    showDelete = false
                }) { Text("Elimina", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Annulla", color = Muted) } }
        )
    }
    if (showProjects && asset != null) {
        val aid = asset!!.id
        AlertDialog(
            onDismissRequest = { showProjects = false },
            title = { Text("Assegna a progetto") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (projects.isEmpty()) {
                        item { Text("Nessun progetto: crealo dalla Libreria.", color = Muted, fontSize = 14.sp) }
                    }
                    items(projects, key = { it.id }) { p ->
                        TextButton(onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    repo.api().addToProject(p.id, aid)
                                    msg = "Aggiunto a ${p.name}."
                                } catch (e: Exception) { msg = "Errore: ${e.message}" }
                            }
                            showProjects = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${p.name} (${p.asset_ids.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {                         TextButton(onClick = { showProjects = false }) { Text("Chiudi", color = Muted) } }
        )
    }
}

// ---------- Dettaglio job (V2) ----------

@Composable
fun JobDetailScreen(repo: StudioRepo, jobId: String, onReusePrompt: (String) -> Unit, onOpenAsset: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var outAssets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var outUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var msg by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    suspend fun load() {
        try {
            val j = repo.api().job(jobId)
            job = j
            loader = repo.imageLoader()
            val found = j.outputs.mapNotNull { aid ->
                try { repo.api().asset(aid) } catch (_: Exception) { null }
            }
            outAssets = found
            outUrls = found.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
        } catch (e: Exception) { msg = "Errore: ${e.message}" }
    }
    LaunchedEffect(jobId) { scope.launch(kotlinx.coroutines.Dispatchers.IO) { load() } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item { Text("Dettaglio job", fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        job?.let { j ->
            item {
                Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(jobLabel(j.status), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            StatusPill(jobLabel(j.status), jobGood(j.status))
                        }
                        Text(j.prompt, fontSize = 14.sp)
                        Text(
                            buildString {
                                append("${j.engine} · ${j.model}")
                                if (j.width != null && j.height != null) append(" · ${j.width}×${j.height}")
                                j.steps?.let { append(" · $it step") }
                                j.seed?.let { append(" · seed $it") }
                            },
                            color = Muted, fontSize = 13.sp
                        )
                        LinearProgressIndicator(
                            progress = { ((j.progress ?: 0.0).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = AccentV, trackColor = Color.White.copy(alpha = 0.12f)
                        )
                        if (!j.error.isNullOrBlank()) Text(j.error!!.take(300), color = Warn, fontSize = 13.sp)
                        if (msg.isNotBlank()) Text(msg, color = Warn, fontSize = 13.sp)
                    }
                }
            }
            if (j.status in listOf("queued", "running", "preparing")) {
                item {
                    GradientButton("Annulla job", Icons.Filled.Close, onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try { job = repo.api().cancel(j.id) } catch (e: Exception) { msg = "Errore: ${e.message}" }
                        }
                    })
                }
            }
            if (outAssets.isNotEmpty()) {
                item {
                    SectionHeader(Icons.Filled.Image, "Risultati (${outAssets.size})")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(outAssets, key = { it.id }) { a ->
                            val req = rememberImgReq(outUrls[a.id], loader)
                            Surface(shape = RoundedCornerShape(14.dp), color = Card, border = BorderStroke(1.dp, CardBorder), onClick = { onOpenAsset("asset/${a.id}") }) {
                                Box(Modifier.size(140.dp, 160.dp).clip(RoundedCornerShape(14.dp))) {
                                    if (req != null && loader != null) {
                                        AsyncImage(
                                            model = req, imageLoader = loader!!, contentDescription = a.id,
                                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val reuseTab = if (j.taskType == "video") "video" else "image"
                GradientButton("Riusa prompt", Icons.Filled.ContentCopy, onClick = {
                    onReusePrompt("create?tab=$reuseTab&prompt=" + Uri.encode(j.prompt))
                })
            }
        } ?: item {
            if (msg.isNotBlank()) Text(msg, color = Warn, fontSize = 13.sp)
            else Text("Caricamento job…", color = Muted, fontSize = 14.sp)
        }
    }
}

// ---------- Visore a tutto schermo con zoom ----------

@Composable
fun FullscreenViewerScreen(repo: StudioRepo, assetId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val req = rememberImgReq(url, loader)
    val ts = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    LaunchedEffect(assetId) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val a = repo.api().asset(assetId)
                loader = repo.imageLoader()
                url = repo.assetUrl(a, false) ?: repo.assetUrl(a, true)
            } catch (_: Exception) {}
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (req != null && loader != null) {
            AsyncImage(
                model = req, imageLoader = loader!!, contentDescription = assetId,
                modifier = Modifier.fillMaxSize().transformable(ts)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit
            )
        }
        Surface(
            shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            onClick = onClose
        ) {
            Icon(Icons.Filled.Close, "Chiudi", tint = Color.White, modifier = Modifier.padding(10.dp).size(22.dp))
        }
        if (scale > 1f) {
            Surface(
                shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onClick = { scale = 1f; offset = Offset.Zero }
            ) {
                Text("Tocca per ripristinare", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}


// ---------- Player video in-app (ExoPlayer, niente app esterne) ----------

@Composable
fun VideoPlayerBox(repo: StudioRepo, asset: Asset, modifier: Modifier = Modifier, onError: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(asset.id) {
        var p: androidx.media3.exoplayer.ExoPlayer? = null
        val job = scope.launch {
            try {
                val url = repo.assetUrl(asset, false) ?: repo.assetUrl(asset, true)
                    ?: throw IllegalStateException("URL video mancante")
                val tok = repo.token()
                val dsf = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000).setReadTimeoutMs(60000)
                if (tok.isNotBlank()) dsf.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $tok"))
                val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dsf)
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
                p = androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().also { exo ->
                    exo.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(v: Boolean) { playing = v }
                        override fun onPlaybackStateChanged(s: Int) {
                            ready = s == androidx.media3.common.Player.STATE_READY
                            ended = s == androidx.media3.common.Player.STATE_ENDED
                            if (s == androidx.media3.common.Player.STATE_READY) {
                                durMs = exo.duration.coerceAtLeast(0L)
                            }
                        }
                    })
                    exo.setMediaSource(source)
                    exo.prepare()
                    exo.playWhenReady = true
                    player = exo
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onError("Riproduzione fallita: ${e.message}")
            }
        }
        onDispose {
            job.cancel()
            try { p?.release() } catch (_: Exception) {}
            if (player === p) player = null
        }
    }
    // Avanzamento tempo mentre riproduce.
    LaunchedEffect(playing, player) {
        while (playing) {
            kotlinx.coroutines.delay(500)
            player?.let { posMs = it.currentPosition.coerceAtLeast(0L) }
        }
    }
    val current = player
    Box(modifier.background(Color.Black)) {
        if (current != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c ->
                    androidx.media3.ui.PlayerView(c).apply {
                        this.player = current
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF141428)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentV, modifier = Modifier.size(40.dp))
            }
        }
        // Controller nostro: solo controlli veri (play/pausa + seek + tempi).
        if (current != null && ready) {
            Surface(
                shape = CircleShape, color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
                onClick = {
                    if (ended) current.seekTo(0)
                    if (playing) current.pause() else current.play()
                }
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pausa" else "Riproduci",
                    tint = Color.White, modifier = Modifier.padding(14.dp).size(34.dp)
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (durMs > 0) {
                    Slider(
                        value = posMs.toFloat().coerceIn(0f, durMs.toFloat()),
                        onValueChange = { posMs = it.toLong(); current.seekTo(it.toLong()) },
                        valueRange = 0f..durMs.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = AccentV, activeTrackColor = AccentV)
                    )
                }
                Text(
                    "${fmtMs(posMs)} / ${fmtMs(durMs)}",
                    color = Color.White, fontSize = 12.sp, maxLines = 1
                )
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

