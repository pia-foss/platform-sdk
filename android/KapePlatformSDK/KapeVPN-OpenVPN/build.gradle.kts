plugins {
    alias(libs.plugins.android.library)
    id("kape.convention")
    id("kape.publish")
}

extra["publishArtifactId"] = "kape-vpn-openvpn"

android {
    namespace = "com.kape.platformsdk.vpn.openvpn"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":KapePlatformSDK:KapeVPN-VpnService"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kape.openvpn)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
