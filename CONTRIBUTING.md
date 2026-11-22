# Contributing

## Build from source

Use the checked-in Gradle wrapper, an installed Android SDK and a Java launcher.
The wrapper's daemon JVM configuration requests JDK 25; allow toolchain provisioning or
install that JDK locally. This is separate from the library's Java 11 bytecode target.
Set `sdk.dir` in your own `local.properties` when needed.

```sh
./gradlew :kirora-sdk:assembleRelease :kirora-sdk-push:assembleRelease
./gradlew :kirora-sdk:lintRelease :kirora-sdk-push:lintRelease
./gradlew :app:assembleDebug
```

The sample app builds without Firebase configuration; FCM delivery needs your own project
configuration and a suitable device. Its UI is an integration aid, not evidence that a
server accepted an operation. The token you type into it is stored by the sample's own
settings code: use disposable test credentials, and don't copy it for production
authentication.

## Tests

Unit tests (Robolectric):

```sh
./gradlew :kirora-sdk:testDebugUnitTest :kirora-sdk-push:testDebugUnitTest
```

Device checks (notifications as Android draws them, real redirects, taps) on a connected
emulator or device:

```sh
./gradlew :kirora-sdk:connectedDebugAndroidTest
```

Physical-device FCM checks (foreground, background, terminated launch, permission changes)
are still to do.

## Local publications

```sh
./gradlew :kirora-sdk:publishReleasePublicationToStagingRepository
./gradlew :kirora-sdk-push:publishReleasePublicationToStagingRepository
```

Each module writes under its own `build/staging-repo`. Signing happens only when signing
properties are configured. Local staging is not a public release.
