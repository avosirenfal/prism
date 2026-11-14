pluginManagement {
	// The build's conventions and the plugins they bring (see build-logic/build.gradle.kts).
	includeBuild("build-logic")
	repositories {
		// Google's mirror of Maven Central first: the same artifacts, served from its CDN. Maven Central itself is the
		// fallback for anything the mirror has not caught up with. Keep in step with dependencyResolutionManagement
		// below and build-logic/settings.gradle.kts: a settings script cannot share a repository list with its
		// pluginManagement block.
		maven("https://maven-central.storage-download.googleapis.com/maven2/") {
			name = "GoogleMavenCentralMirror"
		}
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

plugins {
	// Provisions the JDK the modules compile for (libs.versions.toml: jdk) when none is installed. A settings
	// plugin cannot take its version from the catalog, so it is the one version that lives here.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
	// Every module resolves from these and only these: a repositories block in a module script fails the build.
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		maven("https://maven-central.storage-download.googleapis.com/maven2/") {
			name = "GoogleMavenCentralMirror"
		}
		mavenCentral()
		google()
	}
}

rootProject.name = "prism"

include(
	":cli",
	":client",
	":linux",
	":ebpf",
	":ebpf-loader",
	":dbus",
	":portal",
	":probe",
	":index",
	":watcher",
	":daemon",
	":preferences",
	":window",
	":desktop",
	":browser",
	":launcher",
	":explorer",
	":app",
	":ktlint-rules",
)
