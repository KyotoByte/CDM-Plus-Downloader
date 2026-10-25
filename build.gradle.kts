buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
    }
    dependencies { classpath("com.android.tools:r8:9.4.14") }
}
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
