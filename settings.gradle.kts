pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.mozilla.nimbus-gradle-plugin") {
                useModule("org.mozilla.appservices:tooling-nimbus-gradle:154.0.1")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "universal-harvester-companion"
include(":app")
