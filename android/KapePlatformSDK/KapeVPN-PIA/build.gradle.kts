plugins {
    alias(libs.plugins.android.fusedlibrary)
    id("kape.publish.fused")
}

extra["publishArtifactId"] = "kape-platform-sdk-vpn-pia"

androidFusedLibrary {
    namespace = "com.kape.platformsdk.vpn.pia"
    minSdk {
        version = release(24)
    }
}

dependencies {
    include(project(":KapePlatformSDK:KapeVPN-VpnService"))
    include(project(":KapePlatformSDK:KapeVPN-Wireguard"))
    include(project(":KapePlatformSDK:KapeVPN-OpenVPN"))
    include(libs.kape.amnezia.wireguard)
    // Deliberately NOT included: libs.kape.openvpn (com.github.pia-foss.mobile-android-vpn-manager:openvpn).
    // It stays out of this fused binary and surfaces as a normal transitive Maven dependency
    // since it's already publicly available on public repositories.
}

// External AARs (wireguard-tunnel) don't publish sources JARs.
// fusedSources extends include so exclude rules are the only reliable way to drop them.
// isTransitive = false prevents sources from transitive deps (coroutines, androidx, openvpn, etc.)
// from being pulled into the fused sources JAR.
configurations.named("fusedSources") {
    isTransitive = false
    exclude(group = "com.kape.amneziawg") // wireguard-tunnel
}

// Scoped to this module only (not the shared kape.publish.fused plugin): declare the MIT
// license on the published POM. Runs after kape.publish.fused's own afterEvaluate, which is
// what creates the "release" publication.
afterEvaluate {
    publishing.publications.named<MavenPublication>("release") {
        pom {
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
        }
    }
}
