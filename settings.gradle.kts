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
        // mars-xlog is not on Maven Central; Aliyun public mirror still hosts 1.2.5
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "a-log"
include(":alog")
include(":alog-decode")
include(":alog-upload")
include(":sample")
include(":sample-viewer")
include(":sample-xlog")
