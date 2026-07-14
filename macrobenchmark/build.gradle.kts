// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "org.aurorasms.macrobenchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true
    }
}

androidComponents {
    // The fixture/control surface exists only in the app's benchmark variant.
    // Excluding this module's default debug variant keeps the repository-wide
    // connectedDebugAndroidTest task from pairing benchmark tests with the
    // deliberately fixture-free app debug APK.
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

dependencies {
    implementation(project(":core:testing"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit)
}
