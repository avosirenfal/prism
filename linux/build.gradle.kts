// libc through the FFM API: the system calls prism makes itself (directories read with getdents64, statx, the
// memory maps and epoll the BPF ring buffer is read through), each with its errno, and nothing else.
plugins {
	id("prism.kotlin-jvm")
}
