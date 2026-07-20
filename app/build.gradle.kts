// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val baselineProfileCapture = providers.gradleProperty("auroraBaselineProfileCapture")
    .map(String::toBooleanStrict)
    .orElse(false)

android {
    namespace = "org.aurorasms.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.aurorasms.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 19
        versionName = "0.6.8-phase6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            // Manual HRF capture must use unobfuscated signatures. Normal
            // benchmark/performance builds remain release-equivalent and R8-enabled.
            isMinifyEnabled = !baselineProfileCapture.get()
            isShrinkResources = !baselineProfileCapture.get()
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        // Phase 0 deliberately approved target 36 and this exact audited,
        // locked Gradle/Kotlin graph while compiling against SDK 37.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "ObsoleteSdkInt",
            "OldTargetApi",
        )
        htmlReport = true
        sarifReport = true
        textReport = true
        warningsAsErrors = true
        xmlReport = true
    }

    testOptions {
        animationsDisabled = true
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:notifications"))
    implementation(project(":core:index"))
    implementation(project(":core:state"))
    implementation(project(":core:telephony"))
    implementation(project(":feature:conversations"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose) {
        // Core 1.19.0 contains the former KTX APIs. Keep Activity Compose from
        // adding the obsolete 1.18.0 compatibility artifact to runtime.
        exclude(group = "androidx.core", module = "core-ktx")
    }
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.lifecycle.viewmodel.compose)
    "benchmarkImplementation"(project(":core:testing"))
    "benchmarkImplementation"(libs.androidx.profileinstaller)
    "benchmarkImplementation"(libs.androidx.room.runtime)
    "releaseImplementation"(libs.androidx.profileinstaller)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(project(":core:testing"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
