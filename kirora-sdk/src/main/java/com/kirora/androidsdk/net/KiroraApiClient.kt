package com.kirora.androidsdk.net

import com.kirora.androidsdk.net.models.AppOpenRequest
import com.kirora.androidsdk.net.models.BatchEventsRequest
import com.kirora.androidsdk.net.models.DeviceRegistrationRequest
import com.kirora.androidsdk.net.models.PushConsentRequest
import com.kirora.androidsdk.net.models.PushTrackingRequest
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.SdkInfo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal object KiroraApiClient {

    /**
     * Request fields that are null are left out (a device detail that can't be read), but inside an
     * event's properties and context a null is the app's own value and is sent as null.
     */
    internal val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(JsonElement::class.java, NullKeepingJsonAdapter)
        // As measured for the batch budget: `<`, `>`, `&`, `=` and `'` stay one byte, not six.
        .disableHtmlEscaping()
        .create()

    private const val NO_CREDENTIALS = "Kirora-No-Credentials"

    private val DEVICE_ID = Regex("(/devices/)[^/]+")
    private val TRACKING_TOKEN = Regex("(/t/p/).+")

    /** [path] with the installation id and a push's signed delivery token left out, for the log. */
    internal fun redactedPath(path: String): String =
        path.replace(DEVICE_ID, "$1…").replace(TRACKING_TOKEN, "$1…")

    /** OkHttp and Retrofit are built on first use, keeping `initialize()` off the startup path. */
    fun create(
        baseUrl: String,
        clientKeyProvider: () -> String,
        userTokenProvider: () -> String?
    ): KiroraApiService {
        val delegate = lazy { build(baseUrl, clientKeyProvider, userTokenProvider) }
        return object : KiroraApiService {
            override suspend fun getPushApp(platform: String, pushAppId: String?, authorization: String?) =
                delegate.value.getPushApp(platform, pushAppId, authorization)

            override suspend fun registerDevice(externalInstallationId: String, request: DeviceRegistrationRequest, authorization: String?) =
                delegate.value.registerDevice(externalInstallationId, request, authorization)

            override suspend fun deleteDevice(externalInstallationId: String, lastSeenAt: String?, authorization: String?) =
                delegate.value.deleteDevice(externalInstallationId, lastSeenAt, authorization)

            override suspend fun updatePushConsent(request: PushConsentRequest, authorization: String?) =
                delegate.value.updatePushConsent(request, authorization)

            override suspend fun sendEventBatch(request: BatchEventsRequest, authorization: String?) =
                delegate.value.sendEventBatch(request, authorization)

            override suspend fun postAppOpen(externalInstallationId: String, request: AppOpenRequest, authorization: String?) =
                delegate.value.postAppOpen(externalInstallationId, request, authorization)

            override suspend fun reportPush(url: String, body: PushTrackingRequest) =
                delegate.value.reportPush(url, body)
        }
    }

    private fun build(
        baseUrl: String,
        clientKeyProvider: () -> String,
        userTokenProvider: () -> String?
    ): KiroraApiService {
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val headerInterceptor = Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder().removeHeader(NO_CREDENTIALS)
            if (original.header(NO_CREDENTIALS) == null) {
                builder.header("Kirora-Client-Key", clientKeyProvider())
                builder.header("Kirora-SDK", SdkInfo.HEADER)
                if (original.header("Authorization") == null) {
                    val userToken = userTokenProvider()
                    if (!userToken.isNullOrEmpty()) {
                        builder.header("Authorization", "Bearer $userToken")
                    }
                }
            }
            chain.proceed(builder.build())
        }

        // Method, path and status only: never headers or bodies (they carry tokens and user data).
        val logInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            KiroraLogger.d("[HTTP] ${request.method} ${redactedPath(request.url.encodedPath)} -> ${response.code}")
            response
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .addInterceptor(logInterceptor)
            // The client key header would follow a redirect to another host.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(KiroraApiService::class.java)
    }
}

/** Writes a JSON value as it is, its null members included, whatever the writer's own setting. */
private object NullKeepingJsonAdapter : TypeAdapter<JsonElement>() {
    override fun write(out: JsonWriter, value: JsonElement?) {
        val previous = out.serializeNulls
        out.serializeNulls = true
        try {
            writeElement(out, value)
        } finally {
            out.serializeNulls = previous
        }
    }

    private fun writeElement(out: JsonWriter, element: JsonElement?) {
        when {
            element == null || element.isJsonNull -> out.nullValue()
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> out.value(primitive.asBoolean)
                    primitive.isNumber -> out.value(primitive.asNumber)
                    else -> out.value(primitive.asString)
                }
            }
            element.isJsonArray -> {
                out.beginArray()
                element.asJsonArray.forEach { writeElement(out, it) }
                out.endArray()
            }
            else -> {
                out.beginObject()
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    out.name(key)
                    writeElement(out, value)
                }
                out.endObject()
            }
        }
    }

    override fun read(reader: JsonReader): JsonElement = JsonParser.parseReader(reader)
}
