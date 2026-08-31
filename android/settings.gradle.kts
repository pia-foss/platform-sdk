pluginManagement {
    includeBuild("build-logic")
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://dl.cloudsmith.io/public/expressvpn/kp_platform_sdks_public/maven/")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "KapeVpnPiaPublicMirror"
include(":KapePlatformSDK:KapeVPN-VpnService")
include(":KapePlatformSDK:KapeVPN-Wireguard")
include(":KapePlatformSDK:KapeVPN-OpenVPN")
include(":KapePlatformSDK:KapeVPN-PIA")
