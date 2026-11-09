// Convention plugins for the modules of the main build (src/main/kotlin/barion.*.gradle.kts), and the typed tasks
// and helpers they and the module scripts use (src/main/kotlin/barion/). The main build includes this one from
// its settings (pluginManagement { includeBuild("build-logic") }), so a module applies a convention with
// `id("barion.kotlin-jvm")`.
plugins {
	`kotlin-dsl`
}

// Every Gradle plugin the build uses is a dependency of this one classpath, so a module script applies any of
// them by id and without a version, and the Kotlin plugin and the compiler plugins that extend it (Compose)
// load in one class loader.
dependencies {
	implementation(plugin(libs.plugins.kotlin.jvm))
	implementation(plugin(libs.plugins.compose))
	implementation(plugin(libs.plugins.kotlin.compose))
	implementation(plugin(libs.plugins.kotlin.serialization))
}

/** The Maven coordinates of a plugin from the version catalog, by the plugin marker convention. */
fun plugin(dependency: Provider<PluginDependency>): Provider<String> =
	dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
