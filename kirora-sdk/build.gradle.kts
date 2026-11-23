plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.vanniktech.publish)
}

// Single source for the version: the Maven artifact, the Kirora-SDK header and event context.
val sdkVersion = providers.gradleProperty("kiroraSdkVersion").get()
// The backend refuses every call whose Kirora-SDK version doesn't match this (400 VALIDATION_ERROR).
// Same pattern as SdkInfo.VERSION_PATTERN, which checks wrappers' versions at run time.
require(Regex("^[0-9A-Za-z.+-]{1,40}$").matches(sdkVersion)) {
    "kiroraSdkVersion '$sdkVersion' must match [0-9A-Za-z.+-]{1,40}, or Kirora refuses every call"
}

kotlin {
    explicitApi()
    compilerOptions {
        // The Kirora artifacts use each other's @KiroraInternalApi hooks; apps cannot.
        freeCompilerArgs.add("-opt-in=com.kirora.androidsdk.KiroraInternalApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "io.kirora.sdk"
    compileSdk = 35

    // The device tests' APK behaves as a current app would (notification permission, Android 13+).
    testOptions {
        targetSdk = compileSdk
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

// Maven Central: uploaded and validated by `publishToMavenCentral` (the release workflow, on a
// version tag); released by hand in the Central Portal. Signed with ORG_GRADLE_PROJECT_signingInMemoryKey
// and ...KeyPassword; Central's user token in ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password.
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("io.kirora", "android-sdk", sdkVersion)
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    pom {
        name.set("Kirora Android SDK")
        description.set("Track events, identify users and receive push notifications with Kirora.")
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

// Local staging repo: `./gradlew :kirora-sdk:publishAllPublicationsToStagingRepository`
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp)
    // Part of the public API (`Flow`, `suspend`), so consumers need it on their compile classpath.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)
}
