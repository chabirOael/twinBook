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
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // GeckoView only. The content filter keeps every other lookup off Mozilla's server.
        maven {
            url = uri("https://maven.mozilla.org/maven2/")
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "twinBook"

include(":app", ":engine", ":data", ":mockserver")
