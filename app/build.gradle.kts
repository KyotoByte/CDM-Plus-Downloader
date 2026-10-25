import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from keystore/signing.properties (never committed).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.kyotobyte.cdmplus"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kyotobyte.cdmplus"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (signingProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside the release app, so testing never touches the real one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true } // compress native libs: smaller download
    }

    // One APK per phone architecture plus a universal APK that runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.4.10")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("org.mozilla:rhino:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Torrents: libtorrent4j (MIT) + libtorrent (BSD). Native code for ARM phones + x86_64; 32-bit x86 shows "not supported".
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35") // Chromebooks / x86_64 tablets & emulators
    testImplementation("org.libtorrent4j:libtorrent4j-linux:2.1.0-35")
    // Page parsing (link crawler, reading mode): jsoup, MIT
    implementation("org.jsoup:jsoup:1.22.2")
    // YouTube / SoundCloud / Bandcamp / PeerTube extraction: NewPipeExtractor, GPL-3.0
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // QR scanner: ZXing (Apache-2.0) + zxing-android-embedded (Apache-2.0)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    maxHeapSize = "3072m"
    // One JVM per test class: the native torrent library and Robolectric's native graphics don't share a process well.
    forkEvery = 1
    systemProperty("roborazzi.test.record", "true")
    systemProperty("cdm.shots", rootProject.file("shots").absolutePath)
    systemProperty("cdm.bench.out", rootProject.file("benchmark.txt").absolutePath)
    // Desktop libtorrent native library for the JVM torrent test (extracted from libtorrent4j-linux).
    systemProperty("java.library.path", rootProject.file("build/jni").absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
