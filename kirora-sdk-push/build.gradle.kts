plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

val sdkVersion = providers.gradleProperty("kiroraSdkVersion").get()

kotlin {
    explicitApi()
    compilerOptions {
        // The Kirora artifacts use each other's @KiroraInternalApi hooks; apps cannot.
        freeCompilerArgs.add("-opt-in=com.kirora.androidsdk.KiroraInternalApi")
    }
}

android {
    namespace = "io.kirora.sdk.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

// Maven Central: uploaded and validated by `publishToMavenCentral` (the release workflow, on a
// version tag); released by hand in the Central Portal. Signed with ORG_GRADLE_PROJECT_signingInMemoryKey
// and ...KeyPassword; Central's user token in ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("io.kirora", "android-sdk-push", sdkVersion)
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    pom {
        name.set("Kirora Android SDK Push")
        description.set("Firebase Cloud Messaging support for the Kirora Android SDK.")
        url.set("https://www.kirora.io")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("kirora")
                name.set("Kirora")
                url.set("https://www.kirora.io")
            }
        }
        scm {
            url.set("https://github.com/kiroratech/kirora-android")
            connection.set("scm:git:https://github.com/kiroratech/kirora-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/kiroratech/kirora-android.git")
        }
    }
}

// Local staging repo: `./gradlew :kirora-sdk-push:publishAllPublicationsToStagingRepository`
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

dependencies {
    api(project(":kirora-sdk"))
    // RemoteMessage appears in KiroraPush's public API, so consumers need it on their compile classpath.
    api(libs.firebase.messaging)
    // Aligns firebase's old transitive androidx versions, as in the core module.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
}
