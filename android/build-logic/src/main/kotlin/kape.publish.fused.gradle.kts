plugins {
    `maven-publish`
}

afterEvaluate {
    val sdkVersion: String =
        System.getenv("KAPE_SDK_VERSION")
            ?: rootProject.rootDir.parentFile
                .resolve("VERSION")
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
            ?: "0.0.0-local"

    val cloudsmithRepoUrl: String =
        System.getenv("CLOUDSMITH_REPO_URL")
            ?: "https://api.cloudsmith.io/maven/expressvpn/kp_platform_sdks_dev/"

    val cloudsmithUsername: String =
        System.getenv("CLOUDSMITH_USERNAME") ?: "token"

    val cloudsmithApiKey: String =
        System.getenv("CLOUDSMITH_API_KEY")
            ?: rootProject.rootDir.parentFile
                .resolve(".cloudsmith")
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
            ?: ""

    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["fusedLibraryComponent"])
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

    // The fused library plugin auto-creates a "maven" publication (projectName-unspecified)
    // inside its own afterEvaluate.
    // Those tasks cause the 'publish' task to produce 2 duplicate AARs so we disable them
    afterEvaluate {
        tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
            if (publication.name == "maven") enabled = false
        }
        tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenLocal>().configureEach {
            if (publication.name == "maven") enabled = false
        }
    }
}
