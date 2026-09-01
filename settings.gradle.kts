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
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor publishes nowhere else. Scoped to that one project so
        // a typo'd coordinate cannot quietly pull something unrelated through an
        // unofficial mirror.
        //
        // Case-insensitive on purpose: the extractor is published under
        // com.github.teamnewpipe but pulls its JSON parser as
        // com.github.TeamNewPipe, and an exact match sends that one to Maven
        // Central, where it does not exist.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("(?i)com\\.github\\.teamnewpipe") }
        }
    }
}

rootProject.name = "SYmuS"
include(":app")
