plugins {
	id("barion.kotlin-jvm")
}

dependencies {
	api(project(":probe"))
	api(project(":ebpf-loader"))
	api(project(":linux"))
}
