rootProject.name = "EverCare"

// Maven Central (repo.maven.apache.org / repo1.maven.org) and dl.google.com are not
// reliably reachable from this machine; the Aliyun mirrors are. They are listed first
// and the canonical repositories are kept as fallbacks so the build still works on a
// machine with unrestricted access.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

include(":composeApp")
