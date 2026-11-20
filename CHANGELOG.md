# Changelog

All notable changes to the Kirora Android SDK are recorded here. Each entry says whether apps
need to do anything to upgrade.

## Unreleased

### Fixed
- **Event context** matches Kirora's event schema of 17 November: `sessionId`, `locale`, `timezone`,
  `device` (`id`, `manufacturer`, `type: "android"`), `app`, `os`, `screen` and `library`, read when
  the event is tracked. The device model and network details (Wi-Fi, cellular, carrier) are no longer
  collected: with the rest, they help fingerprint a device.
  Needs the backend version that accepts the mobile fields. **No action required.**
- **`logout()`** signs the user out locally before any network work: events tracked while it runs wait
  for the next sign-in instead of joining the outgoing user or being dropped with their events.
- **Device detachment** waits for a push registration still under way and names its `lastSeenAt`, so
  a token refresh or first registration in flight is detached too.
- **Failed deletions** (consent withdrawal, sign-out) are remembered across restarts; the records they
  cover stay unused until the database deletes them, so they can't come back when storage recovers.
- **Queue budget**: the database and the in-memory fallback share one 1,000-event, 1,000,000-byte
  budget; events held in memory move into the database once it works again.
- **Acknowledgements**: a result is settled only as `{ event: {…} }` or `{ error: { code } }`; a
  malformed one is retried with its key.
- **`Retry-After`** is no longer cut to one hour, and very large values no longer overflow. A push
  report asked to wait more than a minute is dropped rather than retried early.
- **Replacement clients** keep the signed-in user only for the same client key and API URL.
- **A client created right after another** (a replacement, or a wrapper's new engine) takes a tracking
  choice the previous client is still writing, instead of the older one in storage.
- **`getQueuedEventCountFlow()`** counts events held in memory too, and nothing a deletion is owed for.
- **App-open spacing** is kept in memory when `trackSessions` is off, and forgotten when tracking
  consent is withdrawn.

### Removed (not in the shared SDK interface)
- `KiroraOptions.tokenRefreshLeadSeconds`: a token is always refreshed 60 seconds before it expires.
- `KiroraOptions.onEventsDropped` and `Kirora.droppedEventCount` (with `EventDropReason`): not in the
  shared interface; dropped events are logged. **Action:** remove the option if you set it.

### Changed (shared interface, 17 November)
- **`onAuthenticationFailed(reason)`**, now in every SDK: called on the main thread when the user can't
  stay authenticated in the background. `AuthFailureReason.FETCH_FAILED` when `fetchUserToken` threw or
  timed out; `REFUSED` when Kirora answered 401 to a fresh token, or there was none to try. Once per
  failure, again only after Kirora accepts the user's token or `identify()` is called; never for a
  failure `identify()` throws, nor for a user signing out. **Action:** change `{ … }` to
  `{ reason -> … }` if you set the earlier, reason-less version.
- **Taps reach the app after their report**, waiting 1.5 seconds at most: the link, `handleDeepLink`,
  and tap listeners hear of a tap once its `opened`/`clicked` report has been answered, or after 1.5
  seconds, with the report carrying on in the background.
- `android-sdk-push` now exposes Firebase Messaging as an `api` dependency. `KiroraPush.handleMessage`
  takes a `RemoteMessage`, so apps previously had to add `firebase-messaging` themselves to compile.
  **No action required.**

### Changed (aligned with the client SDK interface)
- **Wire fixes.** Events are sent as `name` (they were `eventName` and refused); devices register at
  `PUT /client/v1/devices/{externalInstallationId}` with the id in the path; app opens carry the
  `pushAppId` the device registered with; a drawn push reports `delivered` (it was `shown`); answers
  are read from Kirora's `{ data }` envelope. Taps and deliveries no longer send the device id.
- **Acknowledgements.** A successful answer no longer clears a whole batch: each event is settled by
  its own result. Retryable per-event codes and missing or repeated results are retried with the same
  idempotency key. Batches are capped at 200,000 bytes; the queue at 1,000 events and 1,000,000 bytes.
  `flush()` no longer skips `Retry-After`.
- **Ownership.** Events tracked before sign-in are adopted by the next sign-in at once; a different
  user signing in never sends or relabels the first user's events; logout drops only the outgoing
  user's. Storage (queue, visit, consent, device id, registration) is per workspace and API URL.
- **Consent.** A remembered `setTrackingConsent(false)` always wins, also without
  `requireTrackingConsent`, and clears queued events, app opens and the visit and cancels sends.
- **`logout()` is now `suspend`** with a 5-second deadline (`logoutAsync()` for callbacks). A failed
  detachment is retried in the background for up to 24 hours. **Action:** call `logout()` from a
  coroutine or switch to `logoutAsync()`.
- **Detach precondition.** `DELETE /client/v1/devices/{id}` sends `?lastSeenAt=` from the device's last
  successful registration, at logout and at `setPushEnabled(false)`. A 409 (registered again since)
  ends the detachment like a 204; 401, 403, 404 and other 4xx except 429 end it without retrying.
  **Needs the backend change that adds this precondition before release.**
- **Push registration** happens at `identify()` and then only when the user, the token, a device
  detail or the day changes, reads `targetKind`, and reports a denied permission in its own form.
- Taps and deliveries are retried once, then dropped (no longer stored for 7 days); app opens are kept
  up to 7 days (was 24 hours) and only for a device registered for push.
- `fetchUserToken` times out after 30 seconds (was 15). Lifecycle events carry `version`, `build`,
  `from_background` and `previous_build`; `Screen Viewed` carries `name` only.
- Database schema is version 5, one file per workspace; the old queue is deleted on upgrade.

### Added
- `getPushStatus()`, `setPushEnabled(enabled)`, `destroy()`, `KiroraApiError`, and the options
  `beforeSend` (redaction before storage) and `handleDeepLink`.
- Push reporting: device registration sends `appVersion`, `osVersion`, `locale` and `timezone` (a
  value that can't be read is left out). App opens go to `POST /client/v1/devices/{id}/app-opens`.

- Wrapper support for React Native and Flutter: `Kirora.setWrapperSdk(name, version)` and
  `Kirora.setWrapperCallbacks(handleDeepLink, onForegroundNotification)` (internal API, answers awaited
  at most 1.5 s), and callback forms `flushAsync`, `getPushStatusAsync`, `setPushEnabledAsync`.
- `getState()` (and `getStateAsync`): a read-only `KiroraState` snapshot for support and debug
  screens: `isInitialized`, `isIdentified`, `deviceId`, `sessionId`, `pushToken`, `trackingConsent`,
  `pushConsent`, `queuedEventCount`. No network call, never prompts, never fails.
  `pushToken` is the current registration's token (null with none, even when Firebase holds one);
  `pushConsent` is the signed-in user's choice as Kirora last accepted it, cleared at logout and when
  another user signs in (a refused `setPushConsent` keeps the previous value).
- Push at launch for wrappers: `Kirora.installPushIntegration(context)` (internal API). A launch
  tap's link waits up to 10 s for the wrapper's first `setWrapperCallbacks`; the app opens at once.
- **Breaking:** `setPushConsent(granted)` is now `suspend` and throws when Kirora hasn't recorded the
  choice (`KiroraApiError`, `UserTokenError`, or `KiroraError` for no signed-in user or a network
  error), instead of recording in the background. Callers outside coroutines use
  `setPushConsentAsync(granted) { error -> }`. A choice made before sign-in is no longer held for the
  next user: it throws.
- `identify()` throws `KiroraError` for a setup error: no token and no `fetchUserToken` (was
  `UserTokenError`), or a client key or `apiUrl` the client was disabled for (was a silent no-op).
- Tracking consent storage: a refusal is also kept in a separate file outside the SDK's settings,
  and either copy counts. An unreadable saved choice follows the consent mode: with
  `requireTrackingConsent` nothing is collected; without it, tracking resumes as on a fresh install
  when the refusal copy was read and holds none (it used to stop in both modes). Neither copy
  readable: nothing is collected. A diagnostic is logged in each case.
  The choice in effect is held in memory (no disk read per event) and saved off the caller's thread,
  in order; a refusal is written to its own copy first and a grant clears that copy first, so a
  crash between the two writes never turns one answer into the other. Only `setTrackingConsent`
  writes either copy: nothing is repaired on start or on a check, since a repair made in the middle
  of a grant (another process starting, say) would undo the grant for good.
- `Kirora.wrapperDetached()` (internal API) for a wrapper's engine teardown. A deep link the app's
  handler takes brings the app forward; a launch tap's link that becomes ready in the background
  opens when the app returns. `setPushConsent` on a destroyed client throws, overlapping calls run one
  at a time, and the callback forms (`setPushConsentAsync`, `getPushStatusAsync`, `flushAsync`)
  answer after `destroy()`.
- **Behaviour change:** reading `Kirora.deviceId` no longer creates a device id; it is null until
  Kirora needs one (the first event or push registration).

### Removed
- `KiroraOptions.maxQueuedEvents` (the queue is fixed at 1,000 events) and
  `KiroraPushOptions.onNotificationLink` (use `KiroraOptions.handleDeepLink`). **Action:** move a
  link handler to `handleDeepLink = { url -> … }`.

### Also added
- Published under the MIT licence (`LICENSE`), with the licence, developer and source-repository
  details Maven Central requires in both artifacts' POMs (source repository: `kiroratech/kirora-android`).
- Early token refresh: with `fetchUserToken` and a token that has `exp`, a new token is fetched 60 seconds
  before expiry and when the app comes to the foreground.
- `KiroraPushOptions.allowedLinkSchemes` restricts which schemes Kirora opens. The manifest declares `<queries>` for
  `http`/`https` so link resolution works on Android 11+.
- `Kirora` refuses secret client keys (`kirora_sk_…`) and stays disabled, logging an error.
- The sample app's release build is minified, so R8 problems with the SDK's consumer rules surface in CI.

### Changed
- **Migration:** removed `KiroraOptions.advertisingId`. The SDK no longer sends `device.advertisingId`
  or `device.adTrackingEnabled`; delete the option if you set it.
- Removed `KiroraApiError` (never thrown by the SDK), and made `KiroraLogger`/`LogEntry` and
  `KiroraPushTrampolineActivity` internal. **Migration:** apps that referenced them must stop;
  none was documented.
- `Kirora.handlePushMessage`, `Kirora.handleNewPushToken` and `KiroraPushBridge` are marked
  `@KiroraInternalApi`: they exist for the `android-sdk-push` artifact, not for apps.
