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
        // Explicit Google Maven mirror for some environments / proxies
        maven {
            url = uri("https://dl.google.com/dl/android/maven2")
        }
    }
}

rootProject.name = "AR_Google_Maps_app"
include(":app")
