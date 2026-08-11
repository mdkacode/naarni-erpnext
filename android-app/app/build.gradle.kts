import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

// Release signing credentials are read from keystore.properties (git-ignored).
// Absent on machines that only build debug — the release signingConfig is then
// left null and `assembleRelease` will fail loudly rather than silently ship an
// unsigned/debug-signed artifact.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.naarni.service"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naarni.service"
        minSdk = 24            // Android 7.0 — covers ~99% of field devices (plan §0)
        targetSdk = 35         // Android 15 — Play requires API 35 for new apps
        versionCode = 4
        versionName = "0.4.0"

        // Backend base URL — overridable per build type.
        buildConfigField("String", "BASE_URL", "\"https://service.naarni.com/\"")
        // Chat realtime. SITE_HOST is the Frappe site name, which is also the
        // Socket.IO namespace; ORIGIN_URL is the Origin header the realtime auth
        // middleware compares against Host (see FrappeSocket).
        buildConfigField("String", "SITE_HOST", "\"service.naarni.com\"")
        buildConfigField(
            "String",
            "SOCKET_URL",
            "\"wss://service.naarni.com/socket.io/?EIO=4&transport=websocket\"",
        )
        buildConfigField("String", "ORIGIN_URL", "\"https://service.naarni.com\"")
        vectorDrawables { useSupportLibrary = true }
    }

    // Point a debug build at the local bench instead of production:
    //     ./gradlew installDebug -PdevBackend=true
    //     adb reverse tcp:8000 tcp:8000 && adb reverse tcp:9000 tcp:9000
    // Default is false, so the existing debug workflow is unchanged.
    val devBackend = (project.findProperty("devBackend") as String?).toBoolean()

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (devBackend) {
                // Reached over `adb reverse`, so the phone talks to this laptop's
                // bench on localhost. The Host header is rewritten to the real
                // site name by DevHostInterceptor — Frappe resolves the site from
                // Host, and localhost is not a site.
                buildConfigField("String", "BASE_URL", "\"http://localhost:8000/\"")
                buildConfigField("String", "SITE_HOST", "\"dev.localhost\"")
                buildConfigField(
                    "String",
                    "SOCKET_URL",
                    "\"ws://localhost:9000/socket.io/?EIO=4&transport=websocket\"",
                )
                buildConfigField("String", "ORIGIN_URL", "\"http://dev.localhost:8000\"")
            }
        }
        release {
            isMinifyEnabled = true          // R8 full mode (plan §3)
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Real upload key (Play App Signing). Falls back to unsigned when
            // keystore.properties is absent so we never ship a debug-signed AAB.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Images
    implementation(libs.coil.compose)

    // Camera + location (photo stamping headline feature)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.play.location)

    // Secure session storage
    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)

    // Push notifications (FCM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // ---- Chat module ----
    // Room is the chat client's single source of truth: the UI renders only from
    // here, and the socket/REST/FCM are three writers into it.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Resumable 500 MB uploads that survive process death and backgrounding.
    implementation(libs.work.runtime.ktx)

    // PagingConfig.maxSize is the real OOM guard on a 200-image thread.
    implementation(libs.paging.runtime.ktx)
    implementation(libs.paging.compose)

    // Re-attaching GPS + orientation after Bitmap.compress() strips all EXIF.
    implementation(libs.exifinterface)

    // collectAsStateWithLifecycle — stops chat Flows collecting while backgrounded.
    implementation(libs.androidx.lifecycle.runtime.compose)
}

// Room schema export: lets a future migration be written against a real
// baseline instead of guesswork, and enables migration tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
