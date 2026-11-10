package prism

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * The version catalog (`gradle/libs.versions.toml`) for the convention plugins, which cannot use the generated
 * `libs` accessors of a build script. Aliases are the catalog's own (`junit-bom`), and an unknown one fails
 * with its name rather than with an absent provider.
 */
internal class Catalog(
	private val catalog: VersionCatalog,
) {
	fun version(alias: String): String =
		catalog
			.findVersion(alias)
			.orElseThrow { missing("version", alias) }
			.requiredVersion

	fun library(alias: String): Provider<MinimalExternalModuleDependency> =
		catalog.findLibrary(alias).orElseThrow { missing("library", alias) }

	private fun missing(kind: String, alias: String) =
		IllegalArgumentException("No $kind '$alias' in the version catalog ${catalog.name}")
}

internal val Project.libs: Catalog
	get() = Catalog(extensions.getByType<VersionCatalogsExtension>().named("libs"))
