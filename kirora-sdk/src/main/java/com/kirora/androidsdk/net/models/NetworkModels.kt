package com.kirora.androidsdk.net.models

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** One event of `POST /client/v1/events/batch`. */
internal data class KiroraEventApiDto(
    @SerializedName("idempotencyKey") val idempotencyKey: String,
    @SerializedName("name") val name: String,
    @SerializedName("properties") val properties: JsonElement,
    @SerializedName("context") val context: JsonElement,
    @SerializedName("occurredAt") val occurredAt: String
)

internal data class BatchEventsRequest(
    @SerializedName("sentAt") val sentAt: String,
    @SerializedName("events") val events: List<KiroraEventApiDto>
)

/**
 * `PUT /client/v1/devices/{externalInstallationId}`: the id is in the path. Either a token
 * (`targetKind` and `target`, permission `granted`) or the denied form (`lastSeenAt` of the
 * registration it follows, no token). Null fields are left out, never sent as "".
 */
internal data class DeviceRegistrationRequest(
    @SerializedName("pushAppId") val pushAppId: String,
    @SerializedName("permission") val permission: String,
    @SerializedName("targetKind") val targetKind: String? = null,
    @SerializedName("target") val target: String? = null,
    @SerializedName("lastSeenAt") val lastSeenAt: String? = null,
    @SerializedName("appVersion") val appVersion: String? = null,
    @SerializedName("osVersion") val osVersion: String? = null,
    @SerializedName("locale") val locale: String? = null,
    @SerializedName("timezone") val timezone: String? = null,
    /** The installation's own secret, made once on the device: detaches it with no user token. */
    @SerializedName("deviceSecret") val deviceSecret: String? = null
)

/** `POST /client/v1/devices/{externalInstallationId}/app-opens`; answered with 204. */
internal data class AppOpenRequest(
    @SerializedName("occurredAt") val occurredAt: String,
    @SerializedName("pushAppId") val pushAppId: String
)

/** Body of a call to a push's `trackingUrl`: `delivered`, `opened` or `clicked`. Names no device. */
internal data class PushTrackingRequest(
    @SerializedName("event") val event: String
)

internal data class PushConsentRequest(
    @SerializedName("granted") val granted: Boolean
)

/** `GET /client/v1/push-app`'s `data`. */
internal data class PushApp(
    @SerializedName("pushAppId") val pushAppId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("targetKind") val targetKind: String? = null,
    @SerializedName("vapidPublicKey") val vapidPublicKey: String? = null
)

/** Kirora's answer envelope: `{ data: … }`. */
internal data class DataEnvelope<T>(
    @SerializedName("data") val data: T? = null
)

/** `PUT /devices/{id}`'s `data`; only what the SDK keeps. */
internal data class DeviceAnswer(
    @SerializedName("lastSeenAt") val lastSeenAt: String? = null
)
