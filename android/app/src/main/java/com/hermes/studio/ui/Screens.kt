package com.hermes.studio.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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

// ---------- Home (mockup 01) ----------

@Composable
fun HomeScreen(repo: StudioRepo, onNav: (String) -> Unit) {
    var caps by remember { mutableStateOf<Capabilities?>(null) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var urls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    suspend fun refresh() {
        try {
            // Parallelo: 3 round-trip in uno solo (perf su tailnet).
            val api = repo.api()
            val capsD = scope.async(kotlinx.coroutines.Dispatchers.IO) { api.capabilities() }
            val jobsD = scope.async(kotlinx.coroutines.Dispatchers.IO) { api.jobs(10) }
            val assetsD = scope.async(kotlinx.coroutines.Dispatchers.IO) { api.assets() }
            caps = capsD.await()
            val freshJobs = jobsD.await()
            if (freshJobs != jobs) jobs = freshJobs
            val al = assetsD.await()
            if (al != assets) {
                assets = al
                urls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
            }
            loader = repo.imageLoader()
            err = null
        } catch (e: Exception) { err = e.message }
    }
    suspend fun reuse(a: Asset, go: (String) -> Unit) {
        val p = try { a.job_id?.let { repo.api().job(it).prompt } } catch (_: Exception) { null }
        go(if (p.isNullOrBlank()) "create" else "create?prompt=" + Uri.encode(p))
    }
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            kotlinx.coroutines.delay(5000)
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
                            GradientButton("Crea video", Icons.Filled.Videocam, onClick = { onNav("video") }, blue = true)
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
                        Box(Modifier.size(150.dp, 190.dp).clip(RoundedCornerShape(16.dp)).background(Card)) {
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
            SectionHeader(Icons.Filled.Layers, "Coda di rendering (Server)")
            Spacer(Modifier.height(4.dp))
            Text(
                if (active.isEmpty()) "Nessuna attività in corso" else "${active.size} attività in corso",
                color = Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (active.isEmpty()) {
                        Text("La coda è vuota. I nuovi job appariranno qui in tempo reale.", color = Muted, fontSize = 13.sp)
                    }
                    active.take(4).forEach { j ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Bg, border = BorderStroke(1.dp, CardBorder)) {
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
                            if (vid?.enabled == true) "Generazione video avanzata" else "Disabilitato (license-gate)",
                            color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2
                        )
                        StatusPill(if (vid?.enabled == true) "Pronto" else "Disabilitato", vid?.enabled == true)
                    }
                }
            }
        }
    }
}

// ---------- Genera immagine (mockup 02) ----------

data class StylePreset(val key: String, val label: String, val icon: ImageVector)

@Composable
fun CreateImageScreen(repo: StudioRepo, initialPrompt: String? = null, onDone: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt ?: "") }
    var preset by remember { mutableStateOf("cinematico") }
    var format by remember { mutableStateOf("1:1") }
    var quality by remember { mutableStateOf("Alta") }
    var count by remember { mutableStateOf(1) }
    var state by remember { mutableStateOf("Pronto") }
    var recent by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var recentUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loader by remember { mutableStateOf<coil.ImageLoader?>(null) }

    val styles = listOf(
        StylePreset("cinematico", "Cinematico", Icons.Filled.Movie),
        StylePreset("anime", "Anime", Icons.Filled.Face),
        StylePreset("realistico", "Realistico", Icons.Filled.CameraAlt),
        StylePreset("fantasy", "Fantasy", Icons.Filled.AutoAwesome)
    )
    val dims = mapOf("1:1" to (1024 to 1024), "16:9" to (1344 to 768), "9:16" to (768 to 1344))

    suspend fun refreshRecent() {
        try {
            val al = repo.api().assets("image").take(6)
            recent = al
            loader = repo.imageLoader()
            recentUrls = al.associate { a -> a.id to ((repo.assetUrl(a, true) ?: repo.assetUrl(a, false)) ?: "") }
        } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) { refreshRecent() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Genera immagine", fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text("Crea immagini con l'AI a partire da una descrizione.", color = Muted, fontSize = 14.sp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DropDownField("Formato", format, listOf("1:1", "16:9", "9:16"), { format = it }, Icons.Filled.Crop) }
                Box(Modifier.weight(1f)) { DropDownField("Qualità", quality, listOf("Bozza", "Alta"), { quality = it }, Icons.Filled.Tune) }
                Box(Modifier.weight(1f)) { DropDownField("Numero", "$count", listOf("1", "2", "4"), { count = it.toInt() }, Icons.Filled.Layers) }
            }
        }
        item {
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Immagini di riferimento (opzionale)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "La pipeline image-to-image non è ancora validata end-to-end: per ora disabilitata.",
                        color = Muted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(4) {
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .drawBehind {
                                        drawRoundRect(
                                            color = Muted.copy(alpha = 0.5f), style = Stroke(
                                                width = 3f,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                                            ),
                                            cornerRadius = CornerRadius(30f, 30f)
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Image, null, tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(26.dp)) }
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
            GradientButton("Genera immagine", Icons.Filled.AutoAwesome, enabled = prompt.isNotBlank(), onClick = {
                val d = dims[format] ?: (1024 to 1024)
                val steps = if (quality == "Bozza") 12 else 20
                state = "Accodamento…"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val api = repo.api()
                        repeat(count) {
                            api.createJob(JobCreate("image", prompt, null, null, null, d.first, d.second, steps, null, preset, emptyList(), "app-${System.currentTimeMillis()}-$it-${prompt.hashCode()}"))
                        }
                        state = "Inserito in coda: segui lo stato in Home."
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

// ---------- Crea video (mockup 03, pipeline in gate) ----------

@Composable
fun CreateVideoScreen() {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Videocam, null, tint = AccentV, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Crea video", fontWeight = FontWeight.Bold, fontSize = 28.sp)
            }
            Text("Anima un'immagine con l'AI, in modo semplice e veloce.", color = Muted, fontSize = 14.sp)
        }
        item {
            Surface(shape = CardShape, color = Color(0xFF2A1530), border = BorderStroke(1.dp, AccentV.copy(alpha = 0.4f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Video non disponibile su questo server", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "LICENSE_GATE: h3.enabled=false — la licenza community MiniMax esclude EU/Italy. Nessuna generazione H3 finché il proprietario non fornisce autorizzazione scritta.",
                        color = Warn, fontSize = 13.sp
                    )
                }
            }
        }
        item {
            SectionHeader(Icons.Filled.Image, "Immagine di partenza")
            Spacer(Modifier.height(10.dp))
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF2A1B4E), Color(0xFF141428)))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Image, null, tint = Muted, modifier = Modifier.size(36.dp)) }
                    Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Cambia immagine" to Icons.Filled.Image, "Ritaglia" to Icons.Filled.Crop, "Rimuovi" to Icons.Filled.Delete).forEach { (t, ic) ->
                            Surface(shape = RoundedCornerShape(10.dp), color = Bg, border = BorderStroke(1.dp, CardBorder)) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ic, null, tint = Muted, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text("Prompt di movimento", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("Suggerisci", color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = CardShape, color = Card, border = BorderStroke(1.dp, CardBorder)) {
                Box(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Prompt di movimento (disabilitato: pipeline non autorizzata)", color = Muted, fontSize = 14.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DropDownField("Durata", "—", listOf("—"), {}, Icons.Filled.Schedule) }
                Box(Modifier.weight(1f)) { DropDownField("Formato", "—", listOf("—"), {}, Icons.Filled.Crop) }
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
                        Text("MiniMax H3", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Generazione video (text-to-video)", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        StatusPill("Disabilitato", false)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Muted)
                }
            }
        }
        item {
            GradientButton("Avvia rendering", Icons.Filled.PlayArrow, enabled = false, onClick = {})
            Text("Disponibile solo quando la pipeline video sarà autorizzata.", color = Muted, fontSize = 12.sp)
        }
    }
}

// ---------- Libreria (mockup 04) ----------

@Composable
fun LibraryScreen(repo: StudioRepo, onReuse: (String) -> Unit) {
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
            onReuse(if (p.isNullOrBlank()) "create" else "create?prompt=" + Uri.encode(p))
        }
    }
    LaunchedEffect(filter) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { reload() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding()) {
        Text("Libreria", fontWeight = FontWeight.Bold, fontSize = 28.sp)
        Text("Tutte le tue generazioni, organizzate sul server.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Surface(shape = CardShape, color = Color(0xFF1E1440), border = BorderStroke(1.dp, AccentV.copy(alpha = 0.35f))) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, tint = AccentV, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Generazioni sul server", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Sicure, sempre disponibili, sincronizzate.", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filters = listOf("Tutto" to Icons.Filled.Apps, "Immagini" to Icons.Filled.Image, "Video" to Icons.Filled.Videocam, "Preferiti" to Icons.Filled.Favorite)
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
        Spacer(Modifier.height(12.dp))
        SectionHeader(Icons.Filled.Layers, "I tuoi progetti")
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(projects, key = { it.id }) { p ->
                val req = rememberImgReq(urls[p.asset_ids.firstOrNull()], loader)
                Surface(shape = RoundedCornerShape(16.dp), color = Card, border = BorderStroke(1.dp, CardBorder)) {
                    Box(Modifier.size(150.dp, 130.dp)) {
                        if (req != null && loader != null) {
                            AsyncImage(
                                model = req, imageLoader = loader!!,
                                imageLoader = loader!!, contentDescription = p.name,
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
                    Column(Modifier.size(110.dp, 130.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.Add, null, tint = AccentV, modifier = Modifier.size(26.dp))
                        Text("Nuova\nraccolta", color = AccentV, fontSize = 12.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionHeader(Icons.Filled.History, "Generazioni recenti")
        Spacer(Modifier.height(10.dp))
        if (err != null) Text("Offline: $err", color = Warn, fontSize = 13.sp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(visible, key = { it.id }) { a ->
                val req = rememberImgReq(urls[a.id], loader)
                val isFav = favIds.contains(a.id)
                Box(Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(14.dp)).background(Card)) {
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
    val updateState by updater.state.collectAsState()
    LaunchedEffect(Unit) {
        base = repo.baseUrl(); tok = repo.token()
        scope.launch {
            try {
                val s = repo.api().status()
                storage = s.storage; queueLen = s.queue_length
            } catch (_: Exception) {}
        }
        scope.launch { updater.checkForUpdates() }
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
                                val s = repo.api().status()
                                storage = s.storage; queueLen = s.queue_length
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
                    Text(
                        "Nessuna porta pubblica, nessuna telemetria esterna, nessun cloud. Solo tailnet privata + bearer.",
                        color = Muted, fontSize = 12.sp
                    )
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
