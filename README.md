# Kirora Android SDK

Connect signed-in users' app activity to Kirora and register their devices for push.
Use the core library for events, screen views and sessions; add the FCM library when
your app needs push notifications.

**Preview — source version 0.1.0, Android 7.0 / API 24 and later.** Your app must compile
against API 35 or later (`compileSdk = 35`), which the SDK's AndroidX dependencies require. Still open before
release: public Maven publication and real-device FCM checks. This guide documents the implemented
API; it does not certify public Maven availability.

## Choose the library

| Library | Gradle coordinate | Includes |
| --- | --- | --- |
| Core | `com.kirora:android-sdk:0.1.0` | Identity, events, screen views, sessions, consent, local event storage and native push support hooks. No Firebase dependency. |
| Core + FCM | `com.kirora:android-sdk-push:0.1.0` | Core plus Firebase Messaging, notification rendering and FCM service integration. |

These coordinates come from the publication configuration. Public repository installation
has not been verified. Within this checkout, the sample uses project dependencies:

```kotlin
// app/build.gradle.kts — choose one
implementation(project(":kirora-sdk"))
// Or, for push; this also includes core:
implementation(project(":kirora-sdk-push"))
```

Once a release is available from your configured Maven repository, the corresponding
external dependency is:

```kotlin
dependencies {
    implementation("com.kirora:android-sdk-push:0.1.0")
}
```

The Kotlin package is `com.kirora.androidsdk`, despite the Maven group being
`com.kirora` and the Android resource namespace being `io.kirora.sdk`.
Both libraries currently compile against API 35 and target Java 11 bytecode.
Core also depends on AndroidX, Room, Kotlin coroutines, Retrofit/Gson and OkHttp.

## Create the client

Create one retained client in your `Application`, and name that class in your app's
manifest. Construction does not request notification permission or wait for network
responses. Initialize early so push taps can reach your app's router.

```kotlin
import android.app.Application
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.model.KiroraOptions

class App : Application() {
    lateinit var kirora: Kirora
        private set

    override fun onCreate() {
        super.onCreate()
        kirora = Kirora(
            this,
            KiroraOptions(
                clientKey = "kirora_pk_REPLACE_ME",
                requireTrackingConsent = true
            )
        )
    }
}
```

```xml
<application android:name=".App" ... />
```

Use your workspace's **public client key**. Token secrets and server API keys belong
on your server. Never bundle them in resources, BuildConfig, Firebase configuration or
the application package.

`Kirora.active` points to the most recently created client. Creating another destroys
the previous client's timers and work. A replacement for the same client key and API URL
keeps the signed-in user; one for another workspace or API starts signed out, and its
user must be identified again: a user's token never goes to another configuration.

`awaitReady()` is available when you specifically need startup completion. Ordinary
calls can be made earlier. `destroy()` stops client work without flushing or deleting
queued events; it is not a substitute for logout.

## Identify the signed-in user

Your authenticated server issues an HS256 JWT with:

| Claim | Value |
| --- | --- |
| `sub` | Your stable user ID, matching the contact's `externalId`. |
| `iat` | Issued-at time in seconds. |
| `exp` | Expiry time in seconds, no more than 24 hours after issue. |

The token secret is created in your Kirora workspace and used only on your server.
The backend verifies the token; client-side claim parsing is not authentication.

After your application authenticates its user, call `identify(token)` from your app's
coroutine scope. Call it again on authenticated application startup because the SDK
keeps the user token in memory rather than restoring it from storage.

```kotlin
// Inside a coroutine; signedUserToken came from your authenticated server.
try {
    kirora.identify(signedUserToken)
} catch (error: com.kirora.androidsdk.model.KiroraError) {
    // Show or record a safe failure state. Do not log the token.
}
```

For refresh, provide `fetchUserToken` when constructing the client:

```kotlin
// tokenFromYourAuthenticatedServer is your suspend () -> String? implementation.
val options = KiroraOptions(
    clientKey = "kirora_pk_REPLACE_ME",
    requireTrackingConsent = true,
    fetchUserToken = tokenFromYourAuthenticatedServer
)
// With that callback configured, identify() fetches the initial token too.
```

The callback must obtain a token for the currently authenticated app user. It has a
30-second timeout. With this callback, the SDK also fetches a new token 60 seconds before
the current one expires, and when the app returns to the foreground.

`onAuthenticationFailed = { reason -> }` tells you, on the main thread, when the SDK can't
keep the user authenticated in the background: `AuthFailureReason.FETCH_FAILED` (your
callback threw or timed out) or `REFUSED` (Kirora answered 401 to a fresh token, or there
was none to try). It's called once per failure, never for one `identify()` already throws,
and not while the user signs out. Events stay queued; call `identify()` again.

Events captured before identify remain unbound on the device and are adopted by the
next user who signs in. Nothing is sent anonymously. A direct switch from A to B keeps
A's previously owned events associated with A, rather than relabeling them as B.

On application sign-out, call `logout()` from a coroutine. It signs the user out locally
at once: anything tracked from then on waits, unbound, for the next user to sign in. It
then attempts the outgoing user's event delivery and device detachment within a shared
five-second deadline, with that user's own token, and drops what was left unsent.

The detachment waits for a push registration already under way (an FCM token refresh,
say), so it detaches the registration that leaves, not an older one. Failed device
detachment may retry in memory for up to 24 hours, stopping on success, a terminal
refusal or client destruction. Process death ends that cleanup; offline logout cannot
guarantee immediate server-side push revocation.

## Track activity

```kotlin
kirora.track(
    "Order Completed",
    mapOf("order_id" to "ORD-1042", "amount" to 12500, "currency" to "NGN")
)
kirora.trackScreenView("Checkout")
```

Use clear past-tense event names and consistent property names. The server validates
names, reserved names and property limits. Properties are copied when `track` is called.
Supported values are JSON-compatible maps with string keys, lists/arrays, strings,
finite numbers, booleans and null; dates are converted to ISO 8601 strings and enums to
their names. Cycles, non-finite numbers and unsupported objects drop that event.

Sessions are enabled by default (`trackSessions = true`). Lifecycle events are optional
(`trackAppLifecycle = false` by default). Both follow tracking consent.

The SDK is designed to send after 20 queued events, within 30 seconds otherwise, and
when the app backgrounds. Batches have limits of 100 events and 200,000 bytes. `flush()`
is a suspend function that attempts delivery now; it is not proof every event was
accepted and does not intentionally bypass consent or backoff.

The queue keeps at most 1,000 events and 1,000,000 bytes in all, the oldest dropped
first. Room-backed records survive restarts. When Room can't take a write, events wait
in memory within the same budget (they don't survive a restart) and move into Room once
it works again. Each event is settled by its own result: a malformed or missing result is
retried with the same idempotency key, and no send comes sooner than the server's
`Retry-After`, however long. Do not assume a successful `track()` call means the event
reached Kirora.

### Redact before storage

```kotlin
val options = KiroraOptions(
    clientKey = "kirora_pk_REPLACE_ME",
    beforeSend = { event ->
        event.copy(properties = event.properties - "email" - "phone")
    }
)
```

`beforeSend` receives a detached `BeforeSendEvent` containing `name`, `properties` and
`context`. It runs on a background thread before storage. Return the event with redacted
properties/context, or null to drop it. The SDK preserves its event name, owner, time and
idempotency key. A throwing hook or invalid JSON output drops the event. Keep the hook
quick and do not call tracking from inside it. This hook applies to behavioural events,
not device registration or operational push receipts.

## Consent and notification settings

These controls are independent:

| Control | Meaning |
| --- | --- |
| `setTrackingConsent(Boolean)` | Local permission to collect behavioural events, sessions and influenced app opens. |
| `setPushConsent(Boolean)` | Signed-in user's marketing push choice, recorded by the server. |
| `requestPushPermission(context)` | Android notification authorization. Does not grant marketing consent. |
| `setPushEnabled(Boolean)` | This installation's push on/off preference, retained across user sign-outs. Does not prompt. |

Tracking is allowed by default unless a refusal is stored. Set
`requireTrackingConsent = true` to require an explicit grant. Apply your user's choice:

```kotlin
kirora.setTrackingConsent(userGrantedBehaviouralTracking)
```

Withdrawing tracking consent stops collection and sends, deletes queued behavioural
events, app opens and the visit, and forgets the last app-open time. A remembered refusal
takes precedence, including through a separate refusal marker. If the database refuses
a deletion, the SDK remembers it (across restarts too), keeps those records unused, and
deletes them before reading the database again; the same applies to a signed-out user's
events. With `trackSessions = false`, the 30-minute app-open spacing is kept in memory only.

Marketing push consent is a server operation. Await it and handle failure before
showing the choice as saved:

```kotlin
// Inside a coroutine, after identify has completed.
try {
    kirora.setPushConsent(userAcceptedMarketingPush)
} catch (error: com.kirora.androidsdk.model.KiroraError) {
    // Keep the previous saved choice and let the user retry.
}
```

Marketing push requires both marketing consent and Android permission. Transactional
push does not require marketing consent. Device registration, delivered/opened/clicked
push reports and detachment have separate operational rules and may continue while
behavioural tracking is refused.

## Add Firebase push

1. Add the push library and configure Firebase for your Android app, including your own
   `google-services.json` and Google Services plugin setup.
2. Configure the matching FCM push app in your Kirora workspace.
3. Include `push = KiroraPushOptions()` in the client options.
4. Request notification permission from an appropriate user interaction, then record
   marketing consent separately when applicable.

```kotlin
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions

val options = KiroraOptions(
    clientKey = "kirora_pk_REPLACE_ME",
    requireTrackingConsent = true,
    push = KiroraPushOptions(
        allowedLinkSchemes = setOf("https", "myapp")
    )
)
```

`pushAppId` can select a push app when a workspace has several. The push artifact declares
`POST_NOTIFICATIONS`; Android 13+ still requires runtime permission. Call from your UI:

```kotlin
kirora.requestPushPermission(activity) { permission ->
    // GRANTED, DENIED, UNANSWERED or UNSUPPORTED.
}
```

The suspend alternative returns the same `PushPermission`. `getPushStatus()` reads
`ENABLED`, `DISABLED`, `DENIED`, `UNANSWERED` or `UNSUPPORTED` without prompting.
`setPushEnabled(false)` detaches this installation; `true` registers again when permission
allows it. A granted permission request also turns the device preference back on.

Device registration includes app/OS version, locale and timezone. It is refreshed for
identity, token or relevant detail changes and after the daily refresh interval. An
unchanged signed-in start can reuse the stored registration.

### Use the built-in service or forward from your own

The push module includes a Firebase messaging service. If it handles incoming messages
for your app, register `KiroraPush.setFallbackMessageHandler` early to receive non-Kirora
messages. If your app owns the service, forward both callbacks instead:

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kirora.androidsdk.push.fcm.KiroraPush

class AppMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (KiroraPush.handleMessage(this, message)) return
        // Handle your app's other messages here.
    }

    override fun onNewToken(token: String) {
        KiroraPush.onNewToken(this, token)
    }
}
```

Declare your service with the Firebase messaging intent filter and `android:exported="false"`.
Inspect the merged manifest to confirm which service handles messages. Do not also route
the same message through a second forwarding path. `handleMessage` invokes a configured
fallback before returning false, so use either that fallback or your own false-branch
handler for non-Kirora messages to avoid duplicate handling.

### Rendering and links

Kirora's Android payloads are data-only FCM messages marked `kirora = "true"`; the SDK
renders them rather than relying on an FCM notification payload. Android data keys include
`title`, `body`, `image`, `link`, `buttons`, `trackingUrl`, `channelId` and `tag`.
The backend intentionally translates its public push content fields to these keys.
Delivery still depends on FCM, system settings and Android background restrictions.

`KiroraPushOptions` configures `smallIconResId`, `accentColor`, `defaultChannelId` and
`defaultChannelName`. The default channel is `kirora_default`. Create any custom channels
in your app; channel sound/importance are controlled by Android settings. The renderer
supports an optional HTTPS image, up to two HTTPS action buttons and notification
replacement by `tag`. Image loading is best effort.

Foreground messages display by default. `onForegroundNotification` can return false to
suppress display. `addForegroundNotificationListener` receives arriving Kirora messages;
remove listeners when their owner is disposed. `addNotificationClickListener` observes
taps, including a buffered launch tap when attached after startup. Remove it with the
same function instance via `removeNotificationClickListener`. A tap reaches your listener,
`handleDeepLink` or the link after its `opened`/`clicked` report has been answered, waiting
1.5 seconds at most; on a slow network the report carries on in the background.

Provide `KiroraOptions.handleDeepLink` to route links in your app. It runs on the main
thread; return true if you handled the link. Otherwise the SDK uses its native routing
fallback. Dangerous schemes are blocked. `allowedLinkSchemes` limits native fallback
routing, **not the URLs delivered to your callback**: validate every URL your router
accepts. Treat payload data as untrusted input.

Operational push reports use the delivery's trusted `trackingUrl`, and are best effort.
They are not queued behavioural events. A test push without that URL sends no report.
A failed report is retried once, never before `Retry-After`; one asked to wait over a
minute is dropped instead.

## Inspect state and handle errors

```kotlin
kirora.getStateAsync { state ->
    // Runs on the main thread; suitable for a support/debug screen.
    // isInitialized, isIdentified, deviceId, sessionId, pushToken,
    // trackingConsent, pushConsent, queuedEventCount
}
```

`getState()` is the suspend form. It makes no network request and does not prompt.
`pushToken` describes the current user/device registration, not merely Firebase's cached
token. Queue count includes pending events for all owners. It is not a delivery receipt.

| Operation | Coroutine form | Callback form |
| --- | --- | --- |
| Identify | `identify(token?)` | `identifyAsync(token?, onError)`; callback reports failures only. |
| Sign out | `logout()` | `logoutAsync(onComplete)` |
| Flush | `flush()` | `flushAsync(onComplete)` |
| Save marketing choice | `setPushConsent(granted)` | `setPushConsentAsync(granted) { error -> }`; null means success. |
| Read device push state | `getPushStatus()` | `getPushStatusAsync(callback)` |
| Enable/disable device push | `setPushEnabled(enabled)` | `setPushEnabledAsync(enabled, onComplete)` |

`KiroraError` is the SDK exception base type. `UserTokenError` describes token acquisition
failures; `KiroraApiError` exposes `status`, `code`, `retryAfterMs` and `retryable`.
Preserve coroutine cancellation in your app's error handling. Completion of logout or
flush does not guarantee remote cleanup or acceptance of every event.

Do not assume callback APIs all switch to the UI thread. Apart from explicitly documented
main-thread callbacks, dispatch UI updates through your application's lifecycle-aware
main-thread mechanism. Events the SDK gives up on are logged with the reason; log
identifiers/error codes rather than tokens or event contents.

## Data and storage

The SDK keeps its JWT in memory. Room stores queued behavioural events, owner IDs and
context in the app's private storage; preferences store installation, consent, session
and registration state. Storage is namespaced by client key/API configuration, with
no-backup handling and restore detection. This is not an SDK-provided encryption layer.

Each event's context, read when the event is tracked, holds: the visit (`sessionId`),
`locale`, `timezone`, the random installation ID with the device manufacturer (`device`),
the app's name, version, build and package (`app`), the OS version (`os`), the screen size
and density (`screen`), and the SDK name and version. No device model, network, carrier,
hardware or advertising identifiers are collected; the installation ID is random. Device registration for push separately sends the app and OS versions, locale and
time zone. Review your own properties before shipping.

The sample app's manually entered token is stored by the **sample's own settings code**.
Use disposable test credentials there; it is not a model for production authentication.

## Build from source

Use the checked-in Gradle wrapper, an installed Android SDK and a Java launcher.
The wrapper's daemon JVM configuration requests JDK 25; allow toolchain provisioning or
install that JDK locally. This is a source-build requirement, separate from the library's
Java 11 bytecode target. Set `sdk.dir` in your own `local.properties` when needed.

```sh
./gradlew :kirora-sdk:assembleRelease :kirora-sdk-push:assembleRelease
./gradlew :kirora-sdk:lintRelease :kirora-sdk-push:lintRelease
./gradlew :app:assembleDebug
```

The demo can build without Firebase configuration; FCM delivery needs your own project
configuration and a suitable device. Its UI is an integration aid, not evidence that a
server accepted an operation.

Generate local staging publications with:

```sh
./gradlew :kirora-sdk:publishReleasePublicationToStagingRepository
./gradlew :kirora-sdk-push:publishReleasePublicationToStagingRepository
```

Each module writes under its own `build/staging-repo`. Signing occurs only when signing
properties are configured. Local staging is not a public release.

Run the unit tests (Robolectric) with:

```sh
./gradlew :kirora-sdk:testDebugUnitTest
```

Physical-device FCM checks (foreground, background, terminated launch, permission changes) are
still to do.

## License

[MIT](LICENSE).
