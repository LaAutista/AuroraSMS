// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.VerificationException
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.license.report)
    alias(libs.plugins.cyclonedx)
}

group = "org.aurorasms"
version = "0.5.0-phase5"

allprojects {
    group = "org.aurorasms"
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }

    configurations.configureEach {
        exclude(group = "androidx.core", module = "core-ktx")

        // AGP creates this SDK-file configuration lazily while executing Android
        // resource tasks. It has no external module dependencies and Gradle does
        // not persist an empty lock entry for it, even under --write-locks.
        if (name == "androidApis") {
            resolutionStrategy.deactivateDependencyLocking()
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Test>().configureEach {
        useJUnit()
        systemProperty("file.encoding", "UTF-8")
        systemProperty("user.country", "US")
        systemProperty("user.language", "en")
        systemProperty("user.timezone", "UTC")
    }
}

licenseReport {
    allowedLicensesFile = layout.projectDirectory.file("config/licenses/allowed-licenses.json")
    buildScriptProjects = allprojects.toTypedArray()
    configurations = arrayOf(
        "debugAndroidTestRuntimeClasspath",
        "debugRuntimeClasspath",
        "debugUnitTestRuntimeClasspath",
        "benchmarkRuntimeClasspath",
        "kspDebugAndroidTestKotlinProcessorClasspath",
        "kspDebugKotlinProcessorClasspath",
        "kspDebugUnitTestKotlinProcessorClasspath",
        "kspReleaseKotlinProcessorClasspath",
        "releaseRuntimeClasspath",
        "runtimeClasspath",
        "testRuntimeClasspath",
    )
    excludeBoms = false
    filters = arrayOf<DependencyFilter>(
        LicenseBundleNormalizer(
            layout.projectDirectory.file("config/licenses/license-normalization.json").asFile.path,
            true,
        ),
    )
    importers = emptyArray()
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.path
    projects = allprojects.toTypedArray()
    renderers = arrayOf<ReportRenderer>(
        JsonReportRenderer("licenses.json"),
        InventoryHtmlReportRenderer("index.html", "AuroraSMS dependency inventory"),
    )
}

tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    jsonOutput.set(layout.buildDirectory.file("reports/bom.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/bom.xml"))
    includeBomSerialNumber.set(false)
    includeBuildSystem.set(true)
    includeLicenseText.set(false)
}

val verifyCleanRoom by tasks.registering(Exec::class) {
    group = "verification"
    description = "Scans tracked implementation inputs for prohibited clean-room material."
    commandLine("bash", layout.projectDirectory.file("scripts/verify-clean-room.sh").asFile)
}

val verifyPrivateAssets by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects tracked files that match private visual-reference hashes."
    commandLine(
        "bash",
        layout.projectDirectory.file("scripts/verify-clean-room.sh").asFile,
        "--hash-only",
    )
}

val verifyPermissions by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks app manifests and built APKs against the permission ledger."
    dependsOn(
        ":app:processBenchmarkMainManifest",
        ":app:processDebugMainManifest",
        ":app:processReleaseMainManifest",
        ":macrobenchmark:processBenchmarkManifest",
    )
    commandLine("bash", layout.projectDirectory.file("scripts/verify-permissions.sh").asFile)
}

val verifyApkContents by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks APK entries for prohibited material and release-only boundaries."
    commandLine("bash", layout.projectDirectory.file("scripts/verify-apk-contents.sh").asFile)
}

val verifyDependencies by tasks.registering {
    group = "verification"
    description = "Resolves and inventories every reproducible, locked dependency graph."
    val reportFile = layout.buildDirectory.file("reports/dependencies/resolved-components.txt")
    outputs.file(reportFile)

    doLast {
        val components = sortedSetOf<String>()
        val violations = sortedSetOf<String>()

        allprojects.forEach { candidateProject ->
            candidateProject.configurations
                .filter { it.isCanBeResolved }
                .sortedBy { it.name }
                .forEach configurationLoop@ { configuration ->
                    configuration.allDependencies
                        .withType(ExternalModuleDependency::class.java)
                        .forEach { dependency ->
                            val requestedVersion = dependency.version.orEmpty()
                            if (
                                requestedVersion.contains('+') ||
                                requestedVersion.contains("SNAPSHOT", ignoreCase = true) ||
                                requestedVersion.contains("latest", ignoreCase = true) ||
                                requestedVersion.startsWith('[') ||
                                requestedVersion.startsWith('(')
                            ) {
                                violations +=
                                    "Non-reproducible declaration in " +
                                    "${candidateProject.path}:${configuration.name}: " +
                                    "${dependency.group}:${dependency.name}:$requestedVersion"
                            }
                        }

                    val standardAndroidClasspath = Regex(
                        "^(benchmark|debug|release)(AndroidTest|UnitTest)?" +
                            "(Compile|Runtime)Classpath$",
                    )
                    val standardJvmOrToolClasspath = configuration.name in setOf(
                        "androidLintTool",
                        "compileClasspath",
                        "kotlinBuildToolsApiClasspath",
                        "kotlinCompilerClasspath",
                        "runtimeClasspath",
                        "testCompileClasspath",
                        "testRuntimeClasspath",
                    )
                    val isVerificationClasspath =
                        standardAndroidClasspath.matches(configuration.name) ||
                            standardJvmOrToolClasspath ||
                            configuration.name == "ksp" ||
                            configuration.name.startsWith("kspDebug") ||
                            configuration.name.startsWith("kspBenchmark") ||
                            configuration.name.startsWith("kspRelease") ||
                            configuration.name.startsWith("unified-test-platform")
                    if (!isVerificationClasspath) {
                        return@configurationLoop
                    }

                    configuration.incoming.resolutionResult.allComponents.forEach { component ->
                        val id = component.id
                        if (id is ModuleComponentIdentifier) {
                            val coordinate = "${id.group}:${id.module}:${id.version}"
                            components += coordinate
                            if (
                                id.group == "org.fossify" ||
                                id.group.startsWith("org.fossify.") ||
                                id.group == "org.simplemobiletools" ||
                                id.group.startsWith("org.simplemobiletools.")
                            ) {
                                violations += "Denied dependency: $coordinate"
                            }
                            if (id.group == "androidx.core" && id.module == "core-ktx") {
                                violations +=
                                    "Unapproved AndroidX compatibility artifact: $coordinate"
                            }
                            if (
                                id.version.contains('+') ||
                                id.version.contains("SNAPSHOT", ignoreCase = true) ||
                                id.version.contains("latest", ignoreCase = true)
                            ) {
                                violations += "Non-reproducible dependency: $coordinate"
                            }
                        }
                        if (
                            id is ProjectComponentIdentifier &&
                            candidateProject.path == ":app" &&
                            configuration.name == "releaseRuntimeClasspath" &&
                            id.projectPath in setOf(":core:testing", ":macrobenchmark")
                        ) {
                            violations += "${id.projectPath} leaked into :app release runtime"
                        }
                        if (
                            id is ModuleComponentIdentifier &&
                            configuration.name == "releaseRuntimeClasspath" &&
                            (
                                id.module == "benchmark-macro" ||
                                    id.module == "benchmark-macro-junit4" ||
                                    id.module == "room-compiler" ||
                                    id.module == "room-testing" ||
                                    id.group == "com.google.devtools.ksp"
                            )
                        ) {
                            violations +=
                                "Build/test dependency leaked into " +
                                "${candidateProject.path} release runtime: " +
                                "${id.group}:${id.module}:${id.version}"
                        }
                    }
                }
        }

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(components.joinToString(separator = "\n", postfix = "\n"))

        if (violations.isNotEmpty()) {
            throw VerificationException(violations.joinToString(separator = "\n"))
        }
    }
}

tasks.register("verifyGovernance") {
    group = "verification"
    description = "Runs the source, dependency, permission, and APK governance gates."
    dependsOn(
        verifyCleanRoom,
        verifyDependencies,
        verifyPermissions,
        verifyPrivateAssets,
        verifyApkContents,
    )
}
