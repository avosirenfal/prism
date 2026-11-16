// Which platform prism is running on, and where prism keeps what is its own there: its preferences, its index, what it
// keeps of its own doing, and the socket a session's prism listens at. Every module that asks either asks it here, so
// there is one answer. Depends on nothing: the client compiled ahead of time asks it too.
plugins {
	id("prism.kotlin-jvm")
}
