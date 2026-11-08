package com.lebarock.lebarock_desktop.data.remote

import com.lebarock.lebarock_desktop.AppInfo
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * Builds the Retrofit service for the caisse's address (Réglages → Serveur), as the handheld's
 * ApiClient does. Every call carries `Accept: application/json` and, once signed in, the account's
 * Bearer token. The HTTPS trust follows [TrustStore] (Windows store + imported root certificates);
 * [reloadTrust] rebuilds the client after a certificate is imported.
 */
class ApiClient(
    private val tokenProvider: () -> String?,
    private val trustManagerFactory: () -> X509TrustManager = { TrustStore.trustManager() },
) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        // The caisse numifies digit-like strings, so "12" can arrive as 12 (and back): accept both.
        isLenient = true
    }

    @Volatile private var http: OkHttpClient = build()
    @Volatile private var cached: Pair<String, AltosApi>? = null

    private fun build(): OkHttpClient {
        val trust = trustManagerFactory()
        return OkHttpClient.Builder()
            .sslSocketFactory(TrustStore.sslContext(trust).socketFactory, trust)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = tokenProvider()
                val original = chain.request()
                val request = original.newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", "${AppInfo.NAME}-Desktop/${AppInfo.version}")
                    // A call that brings its own token (the print agent's admin sign-in) keeps it.
                    .apply { if (!token.isNullOrBlank() && original.header("Authorization") == null) header("Authorization", "Bearer $token") }
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /** After a certificate was imported or removed in Réglages. */
    fun reloadTrust() {
        http = build()
        cached = null
        images = null // rebuilt with the new trust, on the same disk cache
    }

    /**
     * The HTTP client of the print agent: same trust and timeouts, but never the signed-in
     * account's token (the agent works with the branch key, whoever is signed in).
     */
    fun agentHttp(): OkHttpClient {
        val trust = trustManagerFactory()
        return OkHttpClient.Builder()
            .sslSocketFactory(TrustStore.sslContext(trust).socketFactory, trust)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Accept", "application/json")
                        .header("User-Agent", "${AppInfo.NAME}-Desktop/${AppInfo.version}")
                        .build(),
                )
            }
            .build()
    }

    /**
     * The POS's item photos (D3): same trust as the API, no token (the caisse's photos are public files), and an
     * HTTP cache in [cacheDir] that follows the caisse's cache headers (`/user-uploads/`: 7 days), so a photo seen
     * once is not fetched again at each POS.
     */
    fun images(): OkHttpClient = images ?: imageHttp().also { images = it }

    /** Where the photos' HTTP cache lives (`%APPDATA%\AltosPOS\cache\images`); null = no disk cache (tests). */
    var imageCacheDir: java.io.File? = null

    @Volatile private var images: OkHttpClient? = null
    private val imageCache: okhttp3.Cache? by lazy { imageCacheDir?.let { okhttp3.Cache(it, 50L * 1024 * 1024) } }

    private fun imageHttp(): OkHttpClient {
        val trust = trustManagerFactory()
        return OkHttpClient.Builder()
            .sslSocketFactory(TrustStore.sslContext(trust).socketFactory, trust)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .apply { imageCache?.let { cache(it) } }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Accept", "image/*")
                        .header("User-Agent", "${AppInfo.NAME}-Desktop/${AppInfo.version}")
                        .build(),
                )
            }
            .build()
    }

    /** The service for [baseUrl] (cached per address). */
    fun apiFor(baseUrl: String): AltosApi {
        cached?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AltosApi::class.java)
        cached = baseUrl to api
        return api
    }

    /**
     * A service with short timeouts for "Tester la connexion": a wrong address must answer
     * within seconds, not after the normal read timeout.
     */
    fun probeFor(baseUrl: String): AltosApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AltosApi::class.java)
}
