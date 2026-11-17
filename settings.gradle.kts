pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
rootProject.name = "FrameX"
include(
    ":app",
    ":core:common",
    ":core:designsystem",
    ":core:datastore",
    ":core:database",
    ":core:optimization",
    ":core:shizuku",
    ":core:a11yboost",
    ":core:adb",
    ":feature:optimize",
    ":feature:games",
    ":core:benchmark",
    ":feature:benchmark",
    ":core:monitoring",
    ":feature:home",
    ":feature:monitor",
    ":feature:settings",
)
