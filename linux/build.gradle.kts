// libc through the FFM API: the system calls prism makes itself (directories read with getdents64, statx, the
// memory maps and epoll the BPF ring buffer is read through), each with its errno; and how a file prism keeps is
// written whole (rename(2) over the old one), since every module that keeps one can see this one.
plugins {
	id("prism.kotlin-jvm")
}
