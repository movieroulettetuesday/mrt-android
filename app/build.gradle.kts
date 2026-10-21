import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes only from environment variables; nothing secret lives in the repo.
// When any of them is missing, the release build is simply left unsigned.
val uploadKeystore: String? = System.getenv("MRT_UPLOAD_KEYSTORE")
val uploadKeystorePassword: String? = System.getenv("MRT_UPLOAD_KEYSTORE_PASSWORD")
val uploadKeyAlias: String? = System.getenv("MRT_UPLOAD_KEY_ALIAS")
val uploadKeyPassword: String? = System.getenv("MRT_UPLOAD_KEY_PASSWORD")
val hasUploadKey = listOf(uploadKeystore, uploadKeystorePassword, uploadKeyAlias, uploadKeyPassword)
    .all { !it.isNullOrBlank() } && file(uploadKeystore!!).isFile

android {
    namespace = "com.movieroulettetuesday.app"
    compileSdk = 36

    defaultConfig {
        // Matches the iOS bundle id (com.movieroulettetuesday.app).
        applicationId = "com.movieroulettetuesday.app"
        minSdk = 26
        targetSdk = 36
        // versionCode: monotonically increasing integer, +1 for every build uploaded to Play (never reused).
        // versionName: semantic version shown to users (MAJOR.MINOR.PATCH). See docs/NOTES.md.
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadKeystore!!)
                storePassword = uploadKeystorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasUploadKey) {
                signingConfig = signingConfigs.getByName("upload")
            } else {
                logger.lifecycle("MRT: upload key env vars not set; release build will be unsigned.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        // Newer-library-version nags are informational only; versions live in libs.versions.toml.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // targetSdk 36 is a deliberate choice (Play requirement for new apps; Matthew 2026-10-21).
        disable += "OldTargetApi"
    }
}

kotlin {
    jvmToolchain(17)
}

// Robolectric's Android 15+ (API 35/36) runtime jars need Java 21; app code still targets Java 17.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "2g"
    // Robolectric 4.17 + API 36 touches FileDescriptor internals.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// ---------------------------------------------------------------------------------------------
// Shared cross-platform contract (mrt-shared). One source: the pinned copy in ../shared, refreshed
// only by scripts/sync-shared.sh and checked against shared/SHA256SUMS on every build. When the
// mrt-shared folder exists on this machine, a stale pinned copy fails the build.
// Constants in shared/data/*.json are turned into Kotlin (SharedContract.kt) at build time.
// ---------------------------------------------------------------------------------------------
val sharedDir = rootProject.layout.projectDirectory.dir("shared")
val externalSharedDir: String = System.getenv("MRT_SHARED_DIR") ?: "/workspace/mrt-shared"

abstract class VerifySharedContract : DefaultTask() {
    @get:InputDirectory abstract val pinnedDir: DirectoryProperty
    @get:Input abstract val externalDir: Property<String>
    @get:OutputFile abstract val stamp: RegularFileProperty

    private fun sha256Hex(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }

    @TaskAction
    fun run() {
        val root = pinnedDir.get().asFile
        val external = File(externalDir.get())
        val sums = File(root, "SHA256SUMS").readLines().filter { it.isNotBlank() }.associate {
            val (hash, path) = it.split(Regex("\\s+"), limit = 2)
            path.trim() to hash
        }
        val onDisk = root.walkTopDown().filter { it.isFile && it.extension == "json" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        if (onDisk != sums.keys) throw GradleException("shared/: ${onDisk - sums.keys} / ${sums.keys - onDisk} not matching SHA256SUMS")
        for ((path, hash) in sums) {
            if (sha256Hex(File(root, path)) != hash) throw GradleException("shared/$path does not match SHA256SUMS. Run scripts/sync-shared.sh.")
            val ext = File(external, path)
            if (ext.isFile && sha256Hex(ext) != hash) {
                throw GradleException("shared/$path is stale vs ${external.path}/$path. Run scripts/sync-shared.sh.")
            }
        }
        stamp.get().asFile.writeText(sums.values.sorted().joinToString("\n"))
    }
}

val verifySharedContract = tasks.register<VerifySharedContract>("verifySharedContract") {
    description = "Checks shared/ against shared/SHA256SUMS (and against mrt-shared when present)."
    pinnedDir.set(sharedDir)
    externalDir.set(externalSharedDir)
    stamp.set(layout.buildDirectory.file("shared-contract/verified.txt"))
}

abstract class GenerateSharedContract : DefaultTask() {
    @get:InputDirectory abstract val dataDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun lit(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is java.math.BigInteger -> v.toString()
        is Number -> v.toDouble().toString()
        is List<*> -> when {
            v.isNotEmpty() && v.all { it is Number } -> "doubleArrayOf(" + v.joinToString(", ") { (it as Number).toDouble().toString() } + ")"
            else -> "listOf(" + v.joinToString(", ") { lit(it) } + ")"
        }
        is Map<*, *> -> "mapOf(" + v.entries.joinToString(", ") { "${lit(it.key.toString())} to ${lit(it.value)}" } + ")"
        else -> error("unsupported $v")
    }

    private fun emit(sb: StringBuilder, name: String, map: Map<*, *>, indent: String) {
        sb.append("${indent}object $name {\n")
        for ((k, v) in map.entries.sortedBy { it.key.toString() }) {
            val key = k.toString()
            if (key.startsWith("_")) continue
            val isConst = v is String || v is Boolean || v is Int || v is Long || (v is Number && v !is java.math.BigInteger)
            if (v is Map<*, *> && v.values.any { it is Map<*, *> || it is List<*> }.not() && v.values.all { it is String } && key != "knownCategoryColors") {
                emit(sb, key.replaceFirstChar { it.uppercase() }, v, "$indent    ")
            } else if (v is Map<*, *> && v.values.any { it !is String }) {
                emit(sb, key.replaceFirstChar { it.uppercase() }, v, "$indent    ")
            } else {
                sb.append("$indent    ${if (isConst) "const val" else "val"} $key = ${lit(v)}\n")
            }
        }
        sb.append("$indent}\n")
    }

    @TaskAction
    fun run() {
        val sb = StringBuilder()
        sb.append("// GENERATED from shared/data/*.json (mrt-shared) by :app:generateSharedContract. Do not edit.\n")
        sb.append("@file:Suppress(\"unused\", \"ktlint\")\n\npackage com.movieroulettetuesday.app.shared\n\n")
        val names = mapOf("palette.json" to "SharedPalette", "show.json" to "SharedShow", "spin-sound-track.json" to "SharedSpinTrack")
        for (f in dataDir.get().asFile.listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }) {
            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parse(f) as Map<String, Any?>
            emit(sb, names[f.name] ?: error("unknown shared data file ${f.name}"), json, "")
            sb.append("\n")
        }
        val out = outputDir.get().asFile.resolve("com/movieroulettetuesday/app/shared")
        out.mkdirs()
        out.resolve("SharedContract.kt").writeText(sb.toString())
    }
}

val generateSharedContract = tasks.register<GenerateSharedContract>("generateSharedContract") {
    dependsOn(verifySharedContract)
    dataDir.set(sharedDir.dir("data"))
    outputDir.set(layout.buildDirectory.dir("generated/shared-contract/kotlin"))
}

// Spin sound: Matthew's recording from the iOS repo (ASSETS.md), copied at build time and verified by
// sha256; never committed here. Debug builds without an iOS checkout just have no sound.
val spinSoundSha256 = "f9b6db796152c10493ee5c434fc4ef5f68ceaddc5f25a0b40e2491f623a666fd"
val iosCheckout: String = System.getenv("MRT_IOS_CHECKOUT") ?: rootProject.file("../ios-ref").path

abstract class CopySpinSound : DefaultTask() {
    @get:Input abstract val source: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val required: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val src = File(source.get())
        if (!src.isFile) {
            if (required.get()) throw GradleException("wheel-spin.m4a not found at $src (set MRT_IOS_CHECKOUT).")
            logger.warn("MRT: $src missing; building without the spin sound.")
            return
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(src.readBytes()).joinToString("") { b -> "%02x".format(b) }
        if (hash != sha256.get()) throw GradleException("wheel-spin.m4a sha256 $hash != pinned ${sha256.get()} (ASSETS.md). Spin timing is locked to that file.")
        src.copyTo(out.resolve("wheel-spin.m4a"))
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateSharedContract, GenerateSharedContract::outputDir)
        val copy = tasks.register<CopySpinSound>("copySpinSound${variant.name.replaceFirstChar { it.uppercase() }}") {
            source.set(File(iosCheckout, "MovieRouletteTuesday/Resources/wheel-spin.m4a").path)
            sha256.set(spinSoundSha256)
            required.set(variant.buildType == "release")
            outputDir.set(layout.buildDirectory.dir("generated/spin-sound/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopySpinSound::outputDir)
    }
}
