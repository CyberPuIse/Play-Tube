import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.play.tube"
    // compileSdk 35 (kept from the Firebase era; harmless without it) —
    // targetSdk intentionally stays at 34.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.play.tube"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "1.5.6"

    }

    // Release signing: credentials come from environment variables so they
    // never live in the repo. CI exports them from GitHub secrets; local
    // builds without the vars simply produce an unsigned release APK.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val keyProps = java.util.Properties().apply {
        val f = file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val playtubeKeyFile = file(keyProps.getProperty("storeFile", "playtube.keystore"))
    val hasReleaseSigning = keystoreFile != null && keystorePassword != null &&
        keyAlias != null && keyPassword != null

    signingConfigs {
        // One fixed key for every build (local and GitHub Actions). Android only
        // lets an APK update an installed app when both are signed with the SAME
        // key; without this, each CI run generated a new debug key and updates
        // failed with "package conflicts with an existing package". The key file
        // is app/playtube.keystore — keep a backup copy of it, never replace it.
        // The key and its passwords are NOT part of the public source: they live
        // only in the private build repo, as app/playtube.keystore plus
        // app/keystore.properties (storePassword, keyAlias, keyPassword).
        // Without those files the normal Android debug key is used.
        if (playtubeKeyFile.exists() && keyProps.getProperty("storePassword") != null) {
            getByName("debug") {
                this.storeFile = playtubeKeyFile
                this.storePassword = keyProps.getProperty("storePassword")
                this.keyAlias = keyProps.getProperty("keyAlias", "playtube")
                this.keyPassword = keyProps.getProperty("keyPassword")
                    ?: keyProps.getProperty("storePassword")
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key from CI secrets if present, otherwise the fixed
            // PlayTube key, so release APKs also stay updatable.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Compact test APK: use D8's non-debug dex packaging, but keep every
        // class/resource and the existing test-build behavior. No R8 shrinking,
        // obfuscation, language filtering, ABI filtering or feature removal.
        create("compactTest") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            // Keep the debug test's update-check/crash-report behavior even
            // though this compact APK does not allow debugger attachment.
            buildConfigField("boolean", "DEBUG", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports modern java.* APIs to old Android runtimes. Without this,
        // NewPipeExtractor's Collectors.toUnmodifiableList() (Java 10 API)
        // crashes the app with NoSuchMethodError on Android 10 and below —
        // those runtimes only ship the Java 8 stream API.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Kotlin 2.2 DSL: the old android.kotlinOptions block is deprecated in KGP
// 2.x — compilerOptions is the supported replacement.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3Version = "1.3.1"

    testImplementation(files("libs/test/junit-4.13.2.jar", "libs/test/hamcrest-core-1.3.jar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // NewPipeExtractor — vendored jar, bytecode-patched for old-Android compatibility.
    // v0.26.5 calls URLDecoder/URLEncoder .decode/.encode(String, Charset) (Java 10 API,
    // available on Android only from API 33), which crashed with NoSuchMethodError on
    // Android 10 and below. Core library desugaring does not cover java.net, and v0.26.5
    // is the latest extractor release, so the two call sites in Utils.class were patched
    // to use the (String, String) overloads that exist since API 1 — via the injected
    // AndroidUrlCompat helper. See tools/extractor-patch/ to regenerate or re-patch
    // a newer extractor version.
    implementation(files("libs/newpipeextractor-0.26.5-android-compat.jar"))
    // NewPipeExtractor's runtime dependencies, previously resolved transitively from
    // the JitPack POM of com.github.TeamNewPipe:NewPipeExtractor:v0.26.5:
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Desugared java.* APIs (streams, toUnmodifiableList, time, …) for
    // Android < 11 — see compileOptions.isCoreLibraryDesugaringEnabled.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Image loading
    implementation("io.coil-kt:coil:2.6.0")

    // Playback
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Crash reporting: local and Firebase-free — crash logs are written to
    // a "PlayTube" folder on the user's device (see util/CrashReporter.kt).
    // Firebase Crashlytics was removed because google-services.json (which
    // contains the Firebase web API key) cannot live in a public repo without
    // GitHub secret scanning flagging it.
}
