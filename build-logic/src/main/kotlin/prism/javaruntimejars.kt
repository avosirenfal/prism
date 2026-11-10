package prism

import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.model.ObjectFactory

/**
 * Makes a resolvable configuration select what a JVM runtime classpath selects: a library's runtime jars.
 * Without the attributes a project dependency offers several variants to choose from, and so does a published
 * library with Gradle module metadata (the ktlint CLI).
 */
fun ResolvableConfiguration.resolvesJavaRuntimeJars(objects: ObjectFactory) {
	attributes {
		attribute(
			Category.CATEGORY_ATTRIBUTE,
			objects.named(Category::class.java, Category.LIBRARY),
		)
		attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
		attribute(
			LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
			objects.named(LibraryElements::class.java, LibraryElements.JAR),
		)
		attribute(
			Bundling.BUNDLING_ATTRIBUTE,
			objects.named(Bundling::class.java, Bundling.EXTERNAL),
		)
	}
}
