# Kirora Android SDK

Connect signed-in users' app activity to Kirora and register their devices for push.
Use the core library for events, screen views and sessions; add the FCM library when
your app needs push notifications.

**Preview, version 0.1.0.** Android 7.0 (API 24) and later. Your app must compile against
API 35 or later and use Kotlin 2.1 or later. The SDK is not yet published to a public Maven
repository.

### Words this guide uses

| Term | Meaning |
| --- | --- |
| **User** | The person signed in to your app. The SDK knows them by the `sub` of the token your server signs. |
| **Contact** | That person in Kirora. `sub` is the contact's `externalId`; a user who isn't a contact yet is created on their first call. |
| **Event** | Something the user did, with a name (`Order Completed`) and `properties`. The SDK adds a `context` (device, app, visit). |
| **Visit** | Activity with no gap of 30 minutes or more; the SDK calls it a session (`sessionId`, `Session Started`). |
| **Client key** | Your workspace's key for apps (`kirora_pk_…`). It's safe to ship in an app, and works only together with a user token your server signed. |
| **Tracking consent** | Whether this device may collect behavioural events. Set locally, with `setTrackingConsent`. |
| **Push consent** | The contact's agreement to **marketing** push, saved by Kirora, with `setPushConsent`. Different from Android's notification permission. |

## Install

| Library | Gradle coordinate | Includes |
| --- | --- | --- |
| Core | `com.kirora:android-sdk:0.1.0` | Identity, events, screen views, sessions, consent and local event storage. No Firebase dependency. |
| Core + FCM | `com.kirora:android-sdk-push:0.1.0` | Core plus Firebase Messaging, notification rendering and the FCM service. |

Until the libraries are published, depend on the modules from this checkout, as the sample
app does:

```kotlin
// app/build.gradle.kts: choose one. The push library includes core.
implementation(project(":kirora-sdk"))
implementation(project(":kirora-sdk-push"))
```

The Kotlin package is `com.kirora.androidsdk`.

## Create the client

Create one client in your `Application`, and name that class in your manifest.
Construction doesn't request notification permission or wait for the network. Create it
early, so push taps can reach your app's router.

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

`clientKey` is required; pass every other option by name, since their order isn't fixed.
Use your workspace's **client key** (`kirora_pk_…`). The token secret and integration keys
belong on your server; never ship them in the app. A secret key (`kirora_sk_…`) passed as
`clientKey` is refused: the client logs an error and does nothing.

The SDK learns when your app is on screen from AndroidX's `ProcessLifecycleOwner`, which
`androidx.startup`'s `InitializationProvider` sets up. Keep that provider in your manifest:
without it, the SDK logs an error and follows only activities started after the client was
created.

`Kirora.active` points to the most recently created client. Creating another destroys the
previous one. A replacement for the same client key and API URL keeps the signed-in user;
one for another workspace or API starts signed out. `awaitReady()` waits for start-up when
you need it; other calls can be made earlier. `destroy()` stops the client's work without
sending or deleting queued events, and is not a substitute for `logout()`.

## Identify the signed-in user

Your server issues an HS256 JWT, signed with the token secret from your Kirora workspace:

| Claim | Value |
| --- | --- |
| `sub` | Your stable user ID, matching the contact's `externalId`. |
| `iat` | Issued-at time in seconds. |
| `exp` | Expiry time in seconds, no more than 24 hours after issue. |

Once your app has signed its user in, call `identify(token)` from a coroutine. Call it
again at every launch while the user stays signed in: the SDK keeps the token in memory
only.

```kotlin
// Inside a coroutine; signedUserToken came from your authenticated server.
try {
    kirora.identify(signedUserToken)
} catch (error: com.kirora.androidsdk.model.KiroraError) {
    // Show or record a safe failure state. Do not log the token.
}
```

Version 1 tracks **signed-in users only**; nothing is sent anonymously. Events tracked
before `identify()` wait on the device and go to the next user who signs in. Switching
directly from user A to user B leaves A's events with A.

### Token refresh

Provide `fetchUserToken`, a `suspend () -> String?` that gets a token for the user who is
currently signed in from your server:

```kotlin
val options = KiroraOptions(
    clientKey = "kirora_pk_REPLACE_ME",
    requireTrackingConsent = true,
    fetchUserToken = tokenFromYourAuthenticatedServer
)
// With the callback set, identify() can be called without a token: it fetches one.
```

The callback has 30 seconds to answer. The SDK calls it 60 seconds before the token
expires, and when the app returns to the foreground with the token expired or about to
expire. A failed refresh is retried while the current token is still valid.

`onAuthenticationFailed = { reason -> }` tells you, on the main thread, when the SDK can't
keep the user authenticated in the background:

- `AuthFailureReason.FETCH_FAILED`: your callback threw, timed out, or returned no usable
  token, and the current token has expired.
- `REFUSED`: Kirora refused a fresh token with a 401, or there was none to try.

It's called once per failure. It's not called for a failure `identify()` already threw, or
while the user signs out. Events stay queued; call `identify()` again. Without
`fetchUserToken`, sending pauses after a refused token until `identify()` gives a new one.

## Sign out

Call `logout()` from a coroutine when, and only when, the user signs out of your app. It
signs them out at once, so anything tracked afterwards waits for the next user. Within a
shared five-second limit, and with the outgoing user's token, it then:

- sends that user's waiting events, and drops whatever couldn't be sent;
- detaches the device from push for that user.

It also drops the events still waiting from before sign-in. Called with nobody signed in
(after a relaunch, before `identify()`), it still detaches the device's last push
registration, and drops only the waiting events that continued a signed-in user's visit.

A detachment that fails is retried in the background for up to 24 hours, including after
a restart. Offline, `logout()` can't guarantee the server stops push at once.

## Track activity

```kotlin
kirora.track(
    "Order Completed",
    mapOf("order_id" to "ORD-1042", "total" to 125.00, "currency" to "NGN")
)
kirora.trackScreenView("Checkout")
```

Name events as Kirora's event guide does:

- **Event names** are Title Case, object then past-tense action (`Plan Upgraded`,
  `Payment Failed`), up to 128 characters. They're case-sensitive: a journey waiting for
  `Order Completed` ignores `order completed`.
- **Property names** are lowercase snake_case (`order_id`), up to 64 characters, with the
  whole `properties` object at most 16 KB as JSON. Send the same type every time, and
  amounts in major units (`125.00`, not `12500`).
- **Key events** use the standard names Kirora's goals and journey templates expect, such
  as `Order Completed` (value read from `total`), `Signed Up`, `Checkout Started`,
  `Payment Failed`, `Payment Succeeded` and `Subscription Started`.

Property values can be strings, finite numbers, booleans, null, lists and maps with string
keys. Dates become ISO 8601 strings and enums their names. A cycle, a non-finite number or
an unsupported object drops the event. The server checks names and limits.

The SDK tracks these itself, following tracking consent:

| Event | When | Properties |
| --- | --- | --- |
| `Session Started` | A visit begins: the first activity, or the first after 30 minutes without any. On by default (`trackSessions = true`). | None |
| `Screen Viewed` | Only when you call `trackScreenView(name)` | `name` |
| `Application Installed` | The first launch after install, with `trackAppLifecycle = true` (off by default) | `version`, `build` |
| `Application Updated` | The first launch of a new version | `version`, `build`, `previous_version`, `previous_build` |
| `Application Opened` | Each launch and each return to the foreground | `from_background`, `version`, `build` |
| `Application Backgrounded` | The app leaves the foreground | None |

A visit continues across a restart within 30 minutes, and ends at sign-out or when another
user signs in.

### Delivery

Events are sent once 20 are queued, otherwise within 30 seconds, and when the app goes to
the background. A batch holds up to 100 events. `flush()` sends now, skipping the SDK's own
retry delay but not tracking consent or the server's `Retry-After`, which is kept across
restarts. Neither `flush()` nor
`track()` returning means Kirora accepted the events.

The queue keeps up to 1,000 events and 1 MB, dropping the oldest first, and survives
restarts. If the database can't be written to, events wait in memory instead. Failed
sends are retried with the same idempotency key, so Kirora doesn't count an event twice.

### Redact before storage

```kotlin
val options = KiroraOptions(
    clientKey = "kirora_pk_REPLACE_ME",
    beforeSend = { event ->
        event.copy(properties = event.properties - "email" - "phone")
    }
)
```

`beforeSend` receives a copy of each event (`name`, `properties`, `context`) on a
background thread, before it's stored. Return it with properties or context redacted, or
null to drop it. Name, owner, time and idempotency key can't be changed. If the hook
throws, the event is dropped. Keep it quick, and don't track from inside it. It applies to
behavioural events only, not to push registration or push reports.

## Consent

These controls are independent:

| Control | Meaning |
| --- | --- |
| `setTrackingConsent(Boolean)` | This device may collect behavioural events, sessions and app opens. |
| `setPushConsent(Boolean)` | The signed-in user's marketing push choice, saved by Kirora. |
| `requestPushPermission(context)` | Android's notification permission. Doesn't grant marketing consent. |
| `setPushEnabled(Boolean)` | This installation's push on/off, kept across sign-outs. Doesn't prompt. |

Tracking is allowed unless refused. Set `requireTrackingConsent = true` to wait for an
explicit grant, then apply the user's choice:

```kotlin
kirora.setTrackingConsent(userGrantedBehaviouralTracking)
```

Withdrawing tracking consent stops collection and sending, and deletes queued events, app
opens and the visit.

Push consent is saved on the server, so await it and handle failure before showing the
choice as saved:

```kotlin
// Inside a coroutine, after identify() has completed.
try {
    kirora.setPushConsent(userAcceptedMarketingPush)
} catch (error: com.kirora.androidsdk.model.KiroraError) {
    // Keep the previous saved choice and let the user retry.
}
```

Marketing push needs both push consent and Android's permission; transactional push
needs only the permission. Push registration, push reports and detachment continue while
tracking is refused.

## Add Firebase push

1. Add the push library, and set up Firebase for your app (your own `google-services.json`
   and the Google Services plugin).
2. Set up the matching FCM push app in your Kirora workspace.
3. Add `push = KiroraPushOptions()` to the client options.
4. Ask for notification permission from a user action, and record push consent separately.

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

`pushAppId` picks the push app when a workspace has several. The push library declares
`POST_NOTIFICATIONS`; Android 13 and later still need the runtime permission:

```kotlin
kirora.requestPushPermission(activity) { permission ->
    // GRANTED, DENIED, UNANSWERED or UNSUPPORTED.
}
```

A suspend form returns the same `PushPermission`. `getPushStatus()` returns `ENABLED`,
`DISABLED`, `DENIED`, `UNANSWERED` or `UNSUPPORTED` without prompting. `DENIED` also covers
Kirora's notification channel turned off in system settings; send the user there.
`setPushEnabled(false)` detaches this installation; `true` registers it again when
permission allows. Granting the permission also turns push back on.

### Use the built-in service or forward from your own

The push library includes a Firebase messaging service. If it handles your app's messages,
register `KiroraPush.setFallbackMessageHandler` early to receive the ones that aren't
Kirora's. If your app has its own service, forward its callbacks instead:

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

    // Only called when Firebase registers by installation ID (below).
    override fun onRegistered(installationId: String) {
        KiroraPush.onRegistered(this, installationId)
    }
}
```

If your app (or another SDK in it) turns on Firebase's installation-ID registration
(`firebase_messaging_installation_id_enabled` in the manifest), the SDK registers the device by
its installation ID instead of an FCM token, with nothing for you to change.

Declare it with the Firebase messaging intent filter and `android:exported="false"`, and
check the merged manifest to confirm which service receives messages. `handleMessage`
calls a configured fallback handler before returning false, so use either the fallback
or your own false branch, not both.

### Notifications and links

The SDK draws Kirora's notifications itself, from data-only FCM messages. Delivery still
depends on FCM, system settings and Android's background limits.

`KiroraPushOptions` sets `smallIconResId`, `accentColor`, `defaultChannelId` and
`defaultChannelName`. The default channel is `kirora_default`; create any other channels
in your app. Notifications can carry an HTTPS image, up to two HTTPS buttons, and a `tag`
that replaces an earlier one. A push for a channel (or, from Android 9, a channel group)
the user turned off isn't shown, and isn't reported as delivered.

Notifications are shown while the app is in the foreground too; return false from
`onForegroundNotification` to hide one. `addForegroundNotificationListener` receives
arriving Kirora messages. `addNotificationClickListener` receives taps, including the tap
that launched the app if added after start-up; remove it with the same function instance
via `removeNotificationClickListener`. Remove listeners when their owner goes away. A tap
reaches your app once its report is answered, or after 1.5 seconds at most.

Set `KiroraOptions.handleDeepLink` to route links yourself. It runs on the main thread;
return true if you handled the link, or the SDK opens it. Dangerous schemes are blocked.
`allowedLinkSchemes` limits only what the SDK opens, **not the URLs your callback
receives**: validate every URL your router accepts, and treat push data as untrusted.

## State and errors

```kotlin
kirora.getStateAsync { state ->
    // On the main thread: isInitialized, isIdentified, deviceId, sessionId, pushToken,
    // trackingConsent, pushConsent, queuedEventCount
}
```

`pushToken` is the FCM token, or the installation ID in that mode. `getState()` is the suspend form. It makes no network request and doesn't prompt.
`queuedEventCount` counts waiting events for every user; it isn't a delivery receipt.

| Operation | Coroutine form | Callback form |
| --- | --- | --- |
| Identify | `identify(token?)` | `identifyAsync(token?, onError)`; called for failures only. |
| Sign out | `logout()` | `logoutAsync(onComplete)` |
| Send now | `flush()` | `flushAsync(onComplete)` |
| Save push consent | `setPushConsent(granted)` | `setPushConsentAsync(granted) { error -> }`; null means saved. |
| Read push status | `getPushStatus()` | `getPushStatusAsync(callback)` |
| Turn device push on or off | `setPushEnabled(enabled)` | `setPushEnabledAsync(enabled, onComplete)` |

`KiroraError` is the base exception. `UserTokenError` is a token that couldn't be
obtained; `KiroraApiError` has `status`, `code`, `retryAfterMs` and `retryable`. Don't
swallow coroutine cancellation. Only callbacks documented as such run on the main thread.

## Data and storage

The user token is kept in memory only. Queued events and their owners are stored in a
Room database in the app's private storage; preferences hold installation, consent, visit
and push registration state. Storage is kept per client key and API, excluded from
backup, and isn't encrypted by the SDK.

Each event's context holds: the visit (`sessionId`), `locale`, `timezone`, a random
installation ID and the device manufacturer (`device`), the app's name, version, build and
package (`app`), the OS version (`os`), the screen size and density (`screen`), and the
SDK's name and version. No device model, network, carrier, hardware or advertising
identifiers are collected. Push registration also sends the app and OS versions, locale and
time zone. Review your own properties before shipping.

## Contributing

Building from source, tests and local publications: see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE).
