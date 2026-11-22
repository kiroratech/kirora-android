package com.kirora.androidsdk.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kirora.androidsdk.net.models.AppOpenRequest
import com.kirora.androidsdk.net.models.BatchEventsRequest
import com.kirora.androidsdk.net.models.DataEnvelope
import com.kirora.androidsdk.net.models.DeviceAnswer
import com.kirora.androidsdk.net.models.DeviceRegistrationRequest
import com.kirora.androidsdk.net.models.PushApp
import com.kirora.androidsdk.net.models.PushConsentRequest
import com.kirora.androidsdk.net.models.PushTrackingRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList

/** Kirora as the SDK sees it: records every call and answers as the server does unless told otherwise. */
internal open class FakeKiroraApiService : KiroraApiService {
    val batchRequests = CopyOnWriteArrayList<BatchEventsRequest>()
    val batchAuthorizations = CopyOnWriteArrayList<String?>()
    val deviceRegistrations = CopyOnWriteArrayList<Pair<String, DeviceRegistrationRequest>>()
    val registrationAuthorizations = CopyOnWriteArrayList<String?>()
    val deviceDeletions = CopyOnWriteArrayList<Pair<String, String?>>()
    val deletionLastSeenAt = CopyOnWriteArrayList<String?>()
    val pushConsentRequests = CopyOnWriteArrayList<PushConsentRequest>()
    val pushAppQueries = CopyOnWriteArrayList<String?>()
    val trackingReports = CopyOnWriteArrayList<Pair<String, PushTrackingRequest>>()
    val appOpens = CopyOnWriteArrayList<Pair<String, AppOpenRequest>>()

    @Volatile var batchResponder: (suspend (BatchEventsRequest, String?) -> Response<JsonElement>)? = null
    @Volatile var deviceResponder: (suspend (DeviceRegistrationRequest) -> Response<DataEnvelope<DeviceAnswer>>)? = null
    @Volatile var deleteResponder: (suspend () -> Response<Unit>)? = null
    @Volatile var appOpenResponder: (() -> Response<Unit>)? = null
    @Volatile var trackingResponder: (suspend () -> Response<Unit>)? = null
    @Volatile var consentResponder: (() -> Response<Unit>)? = null
    @Volatile var pushApp: PushApp? = PushApp(pushAppId = "11111111-1111-1111-1111-111111111111", name = "Android", targetKind = "fcm_token")

    override suspend fun getPushApp(platform: String, pushAppId: String?, authorization: String?): Response<DataEnvelope<PushApp>> {
        pushAppQueries.add(pushAppId)
        val app = pushApp ?: return error(404, "NOT_FOUND")
        return Response.success(DataEnvelope(if (pushAppId != null) app.copy(pushAppId = pushAppId) else app))
    }

    override suspend fun registerDevice(
        externalInstallationId: String,
        request: DeviceRegistrationRequest,
        authorization: String?
    ): Response<DataEnvelope<DeviceAnswer>> {
        deviceRegistrations.add(externalInstallationId to request)
        registrationAuthorizations.add(authorization)
        return deviceResponder?.invoke(request)
            ?: Response.success(DataEnvelope(DeviceAnswer(lastSeenAt = "2026-11-16T10:00:00.000Z")))
    }

    override suspend fun deleteDevice(externalInstallationId: String, lastSeenAt: String?, authorization: String?): Response<Unit> {
        deviceDeletions.add(externalInstallationId to authorization)
        deletionLastSeenAt.add(lastSeenAt)
        return deleteResponder?.invoke() ?: Response.success(204, Unit)
    }

    override suspend fun updatePushConsent(request: PushConsentRequest, authorization: String?): Response<Unit> {
        pushConsentRequests.add(request)
        return consentResponder?.invoke() ?: Response.success(204, Unit)
    }

    override suspend fun sendEventBatch(request: BatchEventsRequest, authorization: String?): Response<JsonElement> {
        batchRequests.add(request)
        batchAuthorizations.add(authorization)
        return batchResponder?.invoke(request, authorization) ?: accepted(request)
    }

    override suspend fun postAppOpen(externalInstallationId: String, request: AppOpenRequest, authorization: String?): Response<Unit> {
        appOpens.add(externalInstallationId to request)
        return appOpenResponder?.invoke() ?: Response.success(204, Unit)
    }

    override suspend fun reportPush(url: String, body: PushTrackingRequest): Response<Unit> {
        trackingReports.add(url to body)
        return trackingResponder?.invoke() ?: Response.success(204, Unit)
    }

    val sentEventNames: List<String>
        get() = batchRequests.flatMap { r -> r.events.map { it.name } }

    /** The last event named [name] that was sent. */
    fun sentEvent(name: String) = batchRequests.flatMap { it.events }.last { it.name == name }

    companion object {
        /** Every event recorded, as Kirora answers a good batch; [refuse] names events answered with an error code. */
        fun accepted(
            request: BatchEventsRequest,
            refuse: Map<String, String> = emptyMap(),
            retryAfterSeconds: Long? = null
        ): Response<JsonElement> {
            val results = JsonArray()
            request.events.forEach { event ->
                results.add(JsonObject().apply {
                    addProperty("idempotencyKey", event.idempotencyKey)
                    val code = refuse[event.name]
                    if (code == null) {
                        addProperty("replayed", false)
                        add("event", JsonObject().apply { addProperty("id", "evt") })
                    } else {
                        add("error", JsonObject().apply {
                            addProperty("code", code)
                            addProperty("message", "refused")
                        })
                    }
                })
            }
            val body: JsonElement = JsonObject().apply { add("data", JsonObject().apply { add("results", results) }) }
            return Response.success(body, raw(200, retryAfterSeconds))
        }

        private fun raw(status: Int, retryAfterSeconds: Long?): okhttp3.Response = okhttp3.Response.Builder()
            .code(status)
            .message("x")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("https://api.kirora.io/").build())
            .headers(if (retryAfterSeconds != null) headersOf("Retry-After", retryAfterSeconds.toString()) else headersOf())
            .build()

        fun <T> error(status: Int, code: String = "ERROR", retryAfterSeconds: Long? = null): Response<T> =
            errorWithBody(status, """{"error":{"code":"$code","message":"x"}}""", retryAfterSeconds)

        /** An answer of [status] with [json] as its error body. */
        fun <T> errorWithBody(status: Int, json: String, retryAfterSeconds: Long? = null): Response<T> {
            val body = json.toResponseBody("application/json".toMediaType())
            return Response.error(body, raw(status, retryAfterSeconds))
        }
    }
}
