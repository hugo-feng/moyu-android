pluginManagement {
    repositories {
        // 国内网络下 google() 可能较慢；maven 中央镜像与阿里云镜像作为补充。
        // 顺序上把 google()/mavenCentral() 放在前面以保证依赖来源的权威性。
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

rootProject.name = "墨阅"
include(":app")
