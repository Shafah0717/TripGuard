// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // AGP 9.0+ ships with built-in Kotlin (KGP 2.2.10). Pin a newer KGP so that
        // the Compose compiler plugin and the Compose 1.12 runtime are compatible.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
