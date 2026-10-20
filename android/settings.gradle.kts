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
rootProject.name = "GateEE"
include(":core")
// The Android app needs the Android SDK; set -PcoreOnly=true to build/test just the core logic.
if (providers.gradleProperty("coreOnly").orNull != "true") include(":app")
