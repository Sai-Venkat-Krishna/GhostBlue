pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GhostBlue"

// betterbluekit: the vehicle API (Kotlin port of BetterBlueKit), plain JVM.
// data: the app's storage (Room) and the logic built on it, shared by the app
// and, later, widgets and Wear OS.
// bbcli: command-line tool for trying the API against a real account.
// app: the Android app.
include(":betterbluekit", ":data", ":bbcli", ":app")
