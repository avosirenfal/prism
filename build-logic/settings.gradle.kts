// The build's own conventions (see build.gradle.kts here). It shares the version catalog with the main build.
// Both repository lists are the main build's (settings.gradle.kts there): Google's mirror of Maven Central before
// Maven Central itself. Without its own pluginManagement this build would fetch its plugins (kotlin-dsl and
// the Kotlin compiler it brings) from the plugin portal alone.
pluginManagement {
	repositories {
		maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "GoogleMavenCentralMirror" }
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "GoogleMavenCentralMirror" }
		gradlePluginPortal()
		mavenCentral()
		google()
	}
	versionCatalogs {
		create("libs") {
			from(files("../gradle/libs.versions.toml"))
		}
	}
}

rootProject.name = "build-logic"
