// Standalone build for the owner-run spikes (E1 Watch Next, E2 screensaver on demand).
// NOT included from the root settings.gradle.kts; build it with `./gradlew -p spike installDebug`.
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
    versionCatalogs {
        // Reuse the root catalog so AGP / Kotlin versions can never drift from :app.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "field-spike"
include(":app")
