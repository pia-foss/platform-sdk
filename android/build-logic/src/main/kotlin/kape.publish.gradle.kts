plugins {
    `maven-publish`
}

// AGP only creates the "release" component after full project configuration.
// Modules must call android { publishing { singleVariant("release") } } themselves,
// because accessing LibraryExtension here would require AGP on the build-logic classpath.
afterEvaluate {
    val sdkVersion: String =
        System.getenv("KAPE_SDK_VERSION")
            ?: rootProject.rootDir.parentFile.resolve("VERSION")
                .takeIf { it.exists() }?.readText()?.trim()
            ?: "0.0.0-local"

    val cloudsmithRepoUrl: String =
        System.getenv("CLOUDSMITH_REPO_URL")
            ?: "https://api.cloudsmith.io/maven/expressvpn/kp_platform_sdks_dev/"

    val cloudsmithUsername: String =
        System.getenv("CLOUDSMITH_USERNAME") ?: "token"

    val cloudsmithApiKey: String =
        System.getenv("CLOUDSMITH_API_KEY")
            ?: rootProject.rootDir.parentFile.resolve(".cloudsmith")
                .takeIf { it.exists() }?.readText()?.trim()
            ?: ""

    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.kape.platformsdk"
                artifactId = project.findProperty("publishArtifactId") as? String
                    ?: project.name.lowercase()
                version = sdkVersion
            }
        }
        repositories {
            maven {
                name = "Cloudsmith"
                url = uri(cloudsmithRepoUrl)
                credentials {
                    username = cloudsmithUsername
                    password = cloudsmithApiKey
                }
            }
        }
    }
}
