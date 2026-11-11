package com.hermes.studio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

private val Context.store by preferencesDataStore("hermes_studio")
private val KEY_BASE = stringPreferencesKey("base_url")
private val KEY_TOKEN = stringPreferencesKey("bearer")

interface StudioApi {
    @GET("api/v1/health") suspend fun health(): Health
    @GET("api/v1/capabilities") suspend fun capabilities(): Capabilities
    @GET("api/v1/status") suspend fun status(): Map<String, Any?>
    @GET("api/v1/jobs") suspend fun jobs(@Query("limit") limit: Int = 50): List<Job>
    @GET("api/v1/jobs/{id}") suspend fun job(@Path("id") id: String): Job
    @POST("api/v1/jobs") suspend fun createJob(@Body b: JobCreate): Job
    @POST("api/v1/jobs/{id}/cancel") suspend fun cancel(@Path("id") id: String): Job
    @GET("api/v1/assets") suspend fun assets(@Query("kind") kind: String? = null): List<Asset>
}

class StudioRepo(private val ctx: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    suspend fun baseUrl(): String =
        ctx.store.data.map { it[KEY_BASE] ?: "http://100.94.223.14:8650/" }.first().let {
            if (it.endsWith("/")) it else "$it/"
        }

    suspend fun token(): String = ctx.store.data.map { it[KEY_TOKEN] ?: "" }.first()

    suspend fun save(base: String, token: String) {
        ctx.store.edit { it[KEY_BASE] = base.trim(); it[KEY_TOKEN] = token.trim() }
    }

    suspend fun api(): StudioApi {
        val tok = token()
        val cli = OkHttpClient.Builder().addInterceptor(Interceptor { c ->
            val r = c.request().newBuilder()
            if (tok.isNotBlank()) r.addHeader("Authorization", "Bearer $tok")
            c.proceed(r.build())
        }).build()
        return Retrofit.Builder().baseUrl(baseUrl()).client(cli)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            .create(StudioApi::class.java)
    }

    fun assetUrl(a: Asset, thumb: Boolean): String {
        // base + relative path; done lazily by callers with runBlocking-free state
        return (if (thumb) a.thumb else a.path) ?: a.path
    }
}
