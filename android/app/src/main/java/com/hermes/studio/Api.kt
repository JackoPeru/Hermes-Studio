package com.hermes.studio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import coil.ImageLoader
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
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
    @POST("api/v1/assets/{id}/favorite")
    suspend fun favorite(@Path("id") id: String, @Query("on") on: Boolean = true): ResponseBody
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
}
