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
    }
}
rootProject.name = "FrameX"
include(
    ":app",
    ":core:common",
    ":core:designsystem",
    ":core:datastore",
    ":core:monitoring",
    ":feature:home",
    ":feature:settings",
)
