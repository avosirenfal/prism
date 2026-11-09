// The daemon: the index kept current. A live index (one that can be told to read places again, and that reads the
// places a search asks after that the rules leave out, in memory alone), and the glue that starts the watcher before
// the index is read, so that no change is missed, and makes what it sees in the index.
plugins {
	id("barion.kotlin-jvm")
}

dependencies {
	api(project(":index"))
	api(project(":watcher"))
}
