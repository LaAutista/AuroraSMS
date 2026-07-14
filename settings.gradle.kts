// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                google()
            }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "AuroraSMS"

include(
    ":app",
    ":benchmark",
    ":core:index",
    ":core:model",
    ":core:notifications",
    ":core:state",
    ":core:telephony",
    ":core:testing",
    ":feature:conversations",
    ":macrobenchmark",
)
