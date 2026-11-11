package com.hermes.studio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import coil.ImageLoader
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

private val Context.store by preferencesDataStore("hermes_studio")
private val KEY_BASE = stringPreferencesKey("base_url")
private val KEY_TOKEN = stringPreferencesKey("bearer")

@JsonClass(generateAdapter = true)
data class StorageOut(val free_gb: Double = 0.0, val total_gb: Double = 0.0)

@JsonClass(generateAdapter = true)
data class StatusOut(
    val backend: String = "",
    val queue_length: Int = 0,
    val storage: StorageOut? = null
)

interface StudioApi {
    @GET("api/v1/health") suspend fun health(): Health
    @GET("api/v1/capabilities") suspend fun capabilities(): Capabilities
    @GET("api/v1/status") suspend fun status(): StatusOut
    @GET("api/v1/jobs") suspend fun jobs(@Query("limit") limit: Int = 50): List<Job>
    @GET("api/v1/jobs/{id}") suspend fun job(@Path("id") id: String): Job
    @POST("api/v1/jobs") suspend fun createJob(@Body b: JobCreate): Job
    @POST("api/v1/jobs/{id}/cancel") suspend fun cancel(@Path("id") id: String): Job
    @GET("api/v1/assets")
    suspend fun assets(
        @Query("kind") kind: String? = null,
        @Query("favorite") favorite: Boolean? = null
    ): List<Asset>
    @GET("api/v1/assets/{id}") suspend fun asset(@Path("id") id: String): Asset
    @POST("api/v1/assets/{id}/favorite")
    suspend fun favorite(@Path("id") id: String, @Query("on") on: Boolean = true): ResponseBody
    @POST("api/v1/projects/{pid}/assets/{aid}")
    suspend fun addToProject(@Path("pid") pid: String, @Path("aid") aid: String): ResponseBody
    @DELETE("api/v1/assets/{id}") suspend fun deleteAsset(@Path("id") id: String): ResponseBody
    @GET("api/v1/projects") suspend fun projects(): List<Project>
    @POST("api/v1/projects") suspend fun createProject(@Body b: ProjectCreate): Project
    @POST("api/v1/projects/{pid}/assets/{aid}")
    suspend fun addToProject(@Path("pid") pid: String, @Path("aid") aid: String): ResponseBody
}

class StudioRepo(private val ctx: Context) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private var cachedBase = ""
    private var cachedApi: StudioApi? = null
    private var cachedToken = "__unset__"
    private var cachedLoader: ImageLoader? = null
    private var loaderToken = "__unset__"

    suspend fun baseUrl(): String =
        ctx.store.data.map { it[KEY_BASE] ?: "http://hermes.tail85940c.ts.net:8650/" }.first().let {
            if (it.endsWith("/")) it else "$it/"
        }

    suspend fun token(): String = ctx.store.data.map { it[KEY_TOKEN] ?: "" }.first()

    suspend fun save(base: String, token: String) {
        ctx.store.edit { it[KEY_BASE] = base.trim(); it[KEY_TOKEN] = token.trim() }
        cachedApi = null; cachedLoader = null
    }

    private fun buildClient(tok: String): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { c ->
                val r = c.request().newBuilder()
                if (tok.isNotBlank()) r.addHeader("Authorization", "Bearer $tok")
                c.proceed(r.build())
            }).build()

    suspend fun api(): StudioApi {
        val base = baseUrl()
        val tok = token()
        if (cachedApi != null && base == cachedBase && tok == cachedToken) return cachedApi!!
        val cli = buildClient(tok)
        val api = Retrofit.Builder().baseUrl(base).client(cli)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(StudioApi::class.java)
        cachedBase = base; cachedToken = tok; cachedApi = api
        return api
    }

    suspend fun absoluteUrl(relativeOrAbsolute: String?): String? {
        if (relativeOrAbsolute.isNullOrBlank()) return null
        if (relativeOrAbsolute.startsWith("http")) return relativeOrAbsolute
        val base = baseUrl().trimEnd('/')
        val rel = if (relativeOrAbsolute.startsWith("/")) relativeOrAbsolute else "/$relativeOrAbsolute"
        return base + rel
    }

    suspend fun assetUrl(a: Asset, thumb: Boolean): String? {
        val rel = if (thumb) a.thumb ?: a.path else a.path
        return absoluteUrl(rel)
    }

    suspend fun imageLoader(): ImageLoader {
        val tok = token()
        if (cachedLoader != null && tok == loaderToken) return cachedLoader!!
        val loader = ImageLoader.Builder(ctx).okHttpClient(buildClient(tok)).crossfade(true).build()
        cachedLoader = loader; loaderToken = tok
        return loader
    }

    suspend fun testConnection(): String {
        api().capabilities()
        return baseUrl()
    }

    suspend fun authedClient(): OkHttpClient = buildClient(token())

    suspend fun deleteAsset(id: String) {
        api().deleteAsset(id)
    }

    /** SSE live: chiama onJobsChanged a ogni evento job (con reconnect del client). */
    suspend fun watchEvents(listener: EventSourceListener): EventSource {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + "/api/v1/events").build()
        return EventSources.createFactory(buildClient(token())).newEventSource(req, listener)
    }

    /** Scarica l'originale in cache (per share/galleria). */
    suspend fun downloadToCache(a: Asset): java.io.File = withContext(Dispatchers.IO) {
        val url = absoluteUrl(a.path) ?: throw IllegalStateException("URL asset mancante")
        val dir = java.io.File(ctx.cacheDir, "hermes-share").apply { mkdirs() }
        val out = java.io.File(dir, a.id + if (a.kind == "video") ".mp4" else ".jpg")
        authedClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Download fallito (${r.code})")
            r.body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        out
    }

    fun shareFile(file: java.io.File, kind: String): android.content.Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (kind == "video") "video/*" else "image/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Salva in Galleria via MediaStore. Ritorna Uri di sistema. */
    suspend fun saveToGallery(a: Asset): Uri = withContext(Dispatchers.IO) {
        val file = downloadToCache(a)
        val resolver = ctx.contentResolver
        val isVideo = a.kind == "video"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "hermes_${a.id}${if (isVideo) ".mp4" else ".jpg"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/HermesStudio" else "Pictures/HermesStudio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore non disponibile")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Scrittura galleria fallita")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }
}
