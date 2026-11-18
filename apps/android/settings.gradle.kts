pluginManagement {
    repositories {
        if (providers.gradleProperty("useChinaMirror").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google(); mavenCentral(); gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            "com.android.application" -> useModule("com.android.tools.build:gradle:${requested.version}")
            "org.jetbrains.kotlin.android" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            "org.jetbrains.kotlin.plugin.compose" -> useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("useChinaMirror").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google(); mavenCentral()
    }
}
rootProject.name = "CompositorAndroid"
include(":app")
