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
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
