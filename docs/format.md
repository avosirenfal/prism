# The index's file

The index lives in one file, `$XDG_CACHE_HOME/barion/index.barion` by default (`~/.cache/barion/index.barion`): a
cache, since it can always be made again by indexing, and in a folder the index itself leaves out. This document says
what is in it, byte by byte, and why it is laid out so. The code is `barion.index.IndexFile`.

## The idea

A daemon keeps the index current as files change, in memory, and writes it out now and then. Writing the whole index
for every change would mean writing megabytes to say that one file was made. So the file is a log:

1. a **snapshot** of the index, compacted, written column by column;
2. then **frames of changes**, each a batch of what changed since the last flush, appended.

Reading the file is reading the snapshot and making each batch of changes after it, in order. Since every change says
what is so now rather than what happened (an entry and everything under it is as this tree says, or is absent), making
one twice is the same as making it once, so a batch read twice does no harm.

Now and then, once the changes are a quarter of the size of the snapshot, the file is **compacted**: the index is
written anew as one snapshot, into `index.barion.new` beside the file, flushed to the disk, and renamed over the old
one. A reader sees the old file or the new one, never half of either.

Every frame is compressed with zstd (through zstd-jni, which carries the native library for each platform in its jar;
a snapshot at level 9, since it is written now and then and read at every start, a frame of changes at level 3, since
one is written at every flush) and carries a CRC-32C of its compressed bytes. A frame cut short
by a crash, or damaged, fails its check, and it and anything after it are dropped: the index is what the frames
before it say. The next frame appended is written where the bad one began, cutting it off.

## Byte by byte

Numbers in headers are little-endian. Inside a payload, counts and lengths are unsigned LEB128 varints (seven bits a
byte, the low bits first, the top bit set on every byte but the last), and signed numbers are zigzagged first
(`0, -1, 1, -2, ...` as `0, 1, 2, 3, ...`), so a small number either way takes one byte.

### The header: 16 bytes

| offset | size | what |
|---|---|---|
| 0 | 8 | `BARIONIX` in ASCII |
| 8 | 2 | the version: 3 (version 2 had each device's number as a stat gives it alone; version 1 was compressed with DEFLATE and had no inodes; a file of another version is not read, and the index is made again) |
| 10 | 6 | zeros |

### A frame: 16 bytes, then the stored payload

| offset | size | what |
|---|---|---|
| 0 | 1 | the kind: 1 a snapshot, 2 changes |
| 1 | 3 | zeros |
| 4 | 4 | the payload's length, decompressed |
| 8 | 4 | the stored length, compressed |
| 12 | 4 | the CRC-32C of the stored bytes |
| 16 | stored length | the payload, compressed: one zstd frame |

### A snapshot's payload

| what | how |
|---|---|
| the roots | a count, then each root's path as bytes |
| what was picked when | a count, then each: a path as bytes, and a time |
| the blocks | a count, then each block, one a root, in the order of their roots' paths |

Bytes are written as their length plus one, then the bytes, so that a length of 0 is "none": `[n + 1][n bytes]`. A
time is the seconds since 1970 (signed) and then the nanoseconds.

### A block

A block is one tree of entries in preorder (each entry before everything under it, which follows it at once), the
children of a folder in the order of their names' bytes. It is written a column at a time, since a column of like
values compresses far better than entries one after another:

| what | how |
|---|---|
| the root's path | bytes |
| the devices | a count, then each: its major and minor numbers as a stat gives them, its major and minor numbers as the kernel has them (as mountinfo lists them, and as the watcher names them; on btrfs, not a subvolume's own), and its file system type as bytes (none where unknown) |
| the number of entries | a count |
| the depths | for each entry after the first, its depth less the depth of the entry before it, signed |
| the names' lengths | for each entry, a count |
| the names | every name's bytes, back to back |
| the kinds | for each entry, one byte: 0 folder, 1 file, 2 link to a folder, 3 link to a file, 4 link to something else, 5 broken link |
| the inodes | for each entry, its inode less the inode of the entry before it (the first, less 0), signed, as 64 bits wrap |
| the devices of the entries | for each entry, a count: which of the block's devices it is on |

The root is the first entry, at depth 0, and is named as the last level of its path (the root `/` has an empty name,
the only one). Depths step by -1, 0 or 1 most of the time, so they take a byte each before compression and almost
nothing after it.

Reading a block makes its columns directly: each entry's parent is the nearest entry before it one level up, which is
on a stack of the folders still open; names are copied as they are. Then three more columns are worked out, which are
never written: where what is under each entry ends, each name folded to lower case, and each name's stem length.

A folder's inode is its own, as reading it gives it: for a mount point, the inode of the root of what is mounted there,
not of the folder it covers, since that is the folder a change in it happens in. The watcher names the folder a change
happened in by device and inode, and the index finds it by those two (`Index.foldersAt`), with a map made from a
block's folders the first time it is asked.

### A frame of changes' payload

A count, then each change: a kind byte, then its body.

| kind | change | body |
|---|---|---|
| 1 | indexed: a root of the index, held as this tree | a block |
| 2 | present: the entry at the block's root is as this tree says | a block |
| 3 | absent: nothing at this path, nor under it | a path as bytes |
| 4 | picked: the picker gave this path at this time | a path as bytes, and a time |

## Sizes

The index of this development container's `/` (184,676 entries, its caches and build trees left out) is 537 KB and
reads back in 39 ms. A made-up tree of 3,000,000 entries writes a file of 8.4 MB in 1.1 s and reads back in 0.29 s,
on 4 cores. (Compressed with DEFLATE, and without inodes, those were 683 KB, and 9.4 MB in 1.3 s, read in 0.33 s.)
