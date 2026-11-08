# Scanning

The index is first built by reading every directory on the machine. This page says how barion reads one, why it
reads it through its own bindings to `getdents64` rather than through the JVM, and what the difference measured.

## The question

A JVM program can read a directory tree with `java.nio.file`: `Files.newDirectoryStream` for the names, then
`Files.readAttributes` on each entry to learn what it is. The kernel already says what each entry is when it lists a
directory: `getdents64` returns each entry's inode and kind (`d_type`) beside its name. The JVM reads that and drops
it, so a JVM indexer must `lstat` every entry to learn which entries are directories.

So the question was whether reading `getdents64` directly, through the FFM API, is worth having bindings of our own
for. It is.

## What was measured

`barion scan PATH` reads a tree with each backend, several times, and prints the best run of each. Both backends are
driven by the same traversal (`barion.index.Scanner`, one fork-join task per directory, as fd-find does), produce the
same immutable tree, and leave out the same ignored directories; the only difference is how each asks the kernel
what a directory holds. The runs after the first read the kernel's dentry and inode caches, which is the case the
daemon is in once it has run before, so the best run is the one compared.

In this development container (4 cores, Linux 6.18, ext4), warm caches:

| tree | threads | entries | getdents64 (FFM) | JVM | getdents64 / JVM |
|---|---:|---:|---:|---:|---:|
| `/usr` | 1 | 92,611 | 48.5 ms | 214.8 ms | **0.23** |
| `/usr` | 4 | 92,611 | 32.4 ms | 73.4 ms | **0.44** |
| `/root` | 4 | 38,272 | 23.1 ms | 57.1 ms | **0.40** |

Both backends found the same tree every time: the same entries, directories, skipped directories, and each
directory's device number (the test `bothBackendsReadTheSameTree` checks this, device numbers included).

On one thread, which shows the cost of one entry plainly, getdents64 takes under a quarter of the JVM's time: the
JVM's time is mostly the `lstat` per entry. More threads hide some of that latency, and the difference narrows to
about 2.5 times. On a machine with millions of entries in a home directory, that is seconds rather than tenths of a
second, every time the index is built from nothing.

These numbers are from a small container with warm caches. A cold scan is bound by the disk, where the JVM's extra
`lstat` per entry costs a seek or a page read of its own, so the gap is expected to be wider there; that wants
measuring on real hardware (`barion scan ~ --runs 5`, with a cold first run).

## The second reason: names that are not UTF-8

A Linux file name is bytes, and need not be valid in any encoding. The JVM names files with `String`s, decoded with
its file name encoding (`sun.jnu.encoding`), so a name that is not valid there cannot be represented, reached or
opened through `java.nio.file` at all: the JVM throws `InvalidPathException` for it.

barion's index holds names as bytes (`barion.index.Name`), and the getdents64 backend reads and opens directories by
their bytes, so such a name is indexed and reachable like any other. The test `aNameThatIsNotUtf8IsReadAsItsBytes`
makes a directory named `n\xFFm` and finds it. The JVM backend reports such a directory as one it cannot name.

## How the getdents64 backend reads a directory

1. `openat(AT_FDCWD, path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)`, with the path's bytes as given.
2. `statx(fd, "", AT_EMPTY_PATH)` for the directory's own device and inode: one per directory, never per entry.
3. `getdents64` into a 64 KiB buffer per thread, read in place (`barion.linux.DirentBuffer`): each entry's inode,
   kind and name are where the kernel wrote them, so reading a directory allocates nothing per entry until the
   entry is kept.
4. Only an entry whose file system does not report its kind (`DT_UNKNOWN`, which some network and FUSE file systems
   give) is `statx`'d, relative to its directory.

Symlinks are never followed. Directories the ignore rules name (`.git`, `node_modules`, `.cache`, ...; see
`IgnoreRules.DEFAULT`) are held in the tree by name, marked skipped, and never read. File systems that are the
kernel's views of itself (`proc`, `sysfs`, `cgroup2`, ...) are found from `/proc/self/mountinfo` and skipped where
they are mounted.

## What is not done yet

- **Per-file-system backends.** Reading every directory is the general case. Some file systems can give their
  whole namespace faster (btrfs's tree search ioctl, an NTFS volume's MFT); docs/plan.md lists these as later
  backends behind the same `Backend` interface.
- **The tree's shape in memory.** A scan now builds a tree of objects, which is right for comparing backends and
  for the tests. The index proper wants the tree as arrays (names in one byte array, parents and kinds in others),
  which is also the shape of the file format; see docs/plan.md.
- **Sizes and times.** The scan reads names, kinds and inodes, which is what the index needs to find things. What a
  listing shows (size, modified time) is read with `statx` when a directory is shown, not when it is indexed.
