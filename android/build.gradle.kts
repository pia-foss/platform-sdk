plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.fusedlibrary) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("publishVpnPiaLocal") {
    group = "publishing"
    description = "Builds KapeVPN-PIA and installs it into the local Maven repo (~/.m2), for local validation of the mirror before it's pushed anywhere."
    dependsOn(":KapePlatformSDK:KapeVPN-PIA:publishReleasePublicationToMavenLocal")
}
