// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.devtools.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

// Each library's public API is recorded in its api/ file, and `check` fails on a change that would
// break apps built against it: `./gradlew apiDump` records an intended change. The validator's own
// set-up doesn't recognise AGP's built-in Kotlin, so its tasks are wired here, to the release classes.
subprojects {
    if (!name.startsWith("kirora-sdk")) return@subprojects
    pluginManager.withPlugin("com.android.library") {
        val validatorClasspath = configurations.create("apiValidatorClasspath") {
            isCanBeConsumed = false
        }
        dependencies {
            add(validatorClasspath.name, "org.ow2.asm:asm:9.6")
            add(validatorClasspath.name, "org.ow2.asm:asm-tree:9.6")
            add(validatorClasspath.name, "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
        }
        val apiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
            val compile = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileReleaseKotlin")
            inputClassesDirs.from(compile.flatMap { it.destinationDirectory })
            runtimeClasspath.from(validatorClasspath)
            outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
        }
        tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
            group = "verification"
            projectApiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
        }
        tasks.register<Copy>("apiDump") {
            from(apiBuild.flatMap { it.outputApiFile })
            into(layout.projectDirectory.dir("api"))
        }
        tasks.named("check") { dependsOn("apiCheck") }
    }
}
