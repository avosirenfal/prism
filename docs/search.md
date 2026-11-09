# Searching the index

The index is there to answer a few typed words with every file on the machine that fits them, the way Everything and
Listary do. This document says what a query means, how it is written in Kotlin today (the text language's parser
comes later and will make the same queries), and how a search runs over the index's columns.

## A query, by example

A query is terms separated by spaces, and a result must pass every one of them. Take `report ext:pdf -dir:old`:

- `report` passes any entry whose name, before its extension, contains "report": `q4-report.pdf`, `report.docx`;
- `ext:pdf` passes entries whose extension is `pdf`;
- `-dir:old` passes entries that are not under a folder named `old`.

So the query finds PDFs with "report" in their names, outside any `old` folder. Terms are never combined with "or",
even when two say the same kind of thing: `ext:jpg ext:png` finds nothing, since no name ends in both. Alternatives
are written with braces inside one term instead: `ext:{jpg,png}`.

Every match ignores case, unless a term says otherwise or the search as a whole is made case sensitive (a toggle in
the picker, not part of the language).

## Names: the stem, and the whole name

A name has a stem and an extension. The extension starts at the last `.`, so `v1.2 notes.txt` has the stem
`v1.2 notes` and the extension `txt`. Two exceptions:

- `.tar.` followed by anything is one extension: `archive.tar.gz` has the stem `archive` and the extension `tar.gz`;
- a leading `.` belongs to the stem, so `.gitignore` is all stem, and has no extension.

A term matches the stem, unless it has a `.` somewhere after its first character, in which case it matches the whole
name. So `report` looks in stems, `report.pdf` looks in whole names, and `.git` (whose only dot is its first
character) looks in stems, finding `.gitignore` and `.github`.

A bare word with no glob characters is found anywhere in what it matches: `report` finds `q4-report.pdf`, and `notes`
finds `v1.2 notes.txt`. Two anchors hold it in place: `^` before a term holds it to the start (`^report`), and `$`
after it holds it to the end (`report$`, `report.pdf$`). `=` before a term is both: `=thing` is a stem that is exactly
`thing` (so `thing.zip` and `thing.tar.gz`), and `=thing.zip` is a whole name that is exactly that.

Because `=` follows the stem rule like everything else, `=.bashrc` finds `.bashrc`, and also `.bashrc.bak`, whose stem
is `.bashrc` too.

## Globs

A word with a glob character is a glob over the whole stem (or the whole name, by the same rule):

| glob | means |
|---|---|
| `*` | any run of characters, none included |
| `?` | one character |
| `[abc]`, `[a-z]`, `[0-5]` | one character of a class; `[!a-z]` one not of it |
| `{a,b}` | one of several; they may hold globs of their own |
| `\` | the next character as itself |

So `*.jpg` is every JPEG, `*.{jpg,png}` every JPEG and PNG, `img_????` a stem of `img_` and four characters, and
`\[draft\]*` a stem that starts with the literal `[draft]`. A glob already matches the whole of what it is matched
against, so `^` and `$` change nothing about it.

## Quoted and raw terms

`"..."` is a bare term that may hold spaces: `"tax return"`, or the glob `"tax *.pdf"`. `\"` is a quote inside one. A
name that starts with `-` must be quoted (`"-notes"`), since a bare `-` negates.

Flags before the quotes change how it is read, in any order, so `rc"..."` and `cr"..."` are the same:

| flag | means |
|---|---|
| `r` | raw: nothing inside is special, and it is found as a substring: `r"img_*"` is the five characters `img_*` |
| `c` | case sensitive: `c"README"` |
| `i` | case insensitive, whatever the toggle says: `i"readme"` |

`c` and `i` cannot be used together. A raw literal with a quote in it is written `r#"say "hi""#`. Anchors work on
these too: `=r"!weird*name"`, `r"!x"$`.

## Paths

A term with a `/` in it is a path. Each `/` is one level:

- a relative path matches the end of an entry's path: `funny/*.jpg` is a JPEG directly inside any folder named
  `funny`;
- an absolute path matches the whole path: `/home/*/Documents/*.pdf` is a PDF directly in any user's `Documents`;
- `**` is any number of levels, as a whole level (`funny/**/b`) or starting the last one (`funny/**.jpg`, which is
  `funny/**` and then `*.jpg`);
- a trailing `/` (or a last level of `**`) is everything under the folder, at any depth: `work/2024/`, `/mnt/photos/`;
- `~` is the home folder, and `~name` is a folder the user has named so, an alias for one folder or several:
  `~/Downloads/`, `~reports/`.

The levels before the last are matched exactly (a whole level's name, ignoring case), unless they are globs. The last
level is matched as a name term is: a substring of the stem, with the stem rule and anchors. So `funny/rep` is an
entry directly in a `funny` folder whose stem contains "rep", and `~/Downloads/inv` is one directly in your
`Downloads` whose stem contains "inv".

## Regexes

`r/.../flags` is a Java regular expression, found anywhere in the whole name (the stem rule does not apply to it):
`r/^img_\d{4}\.jpe?g$/`. Its flags:

| flag | means |
|---|---|
| `s` | match the stem only |
| `p` | match the whole path |
| `c` | case sensitive |
| `i` | case insensitive, whatever the toggle says |

`s` and `p` cannot be used together, nor `c` and `i`.

## Modifiers

A modifier is `name:value`; its value may be quoted (`dir:"old photos"`), and a name that is not one of these is an
error.

**`dir:PATTERN[:DEPTH]`** is an entry with an ancestor folder whose name is `PATTERN` (exactly, ignoring case; a glob
or braces are allowed), or, where `PATTERN` has a `/` or starts with `~`, an ancestor at that path. `DEPTH` is how far
up the ancestor may be, the parent being 1: `n` exactly, `a-b` from `a` to `b`, `a-` at least `a`. Without it, any
distance. So `dir:funny:1 type:image` is an image directly in a `funny` folder, and `dir:work dir:2024` is under both
a `work` folder and a `2024` folder, nested either way, where `dir:work/2024` wants the `2024` inside the `work`.

**`ext:EXT`** is an extension, matched at any extension boundary: `archive.tar.gz` has `tar.gz` and `gz`, so both
`ext:gz` and `ext:tar.gz` find it. Globs and braces are allowed (`ext:{jpg,png,webp}`), and so is a regex between
slashes, which must match the whole of an extension (`ext:/jpe?g/i`).

**`type:GROUP`** is any extension of a group: `archive`, `audio`, `code`, `document`, `font`, `image`, `text`,
`video` (the lists are in `barion.index.query.Group`).

**`is:FLAG`** is one of:

| flag | means |
|---|---|
| `hidden` | a name that starts with `.` |
| `file` | a file, or a link to one |
| `dir` | a folder, or a link to one |
| `symlink` | any link, a broken one included |

So `is:file -is:symlink` is regular files only, and `is:dir is:symlink` is links to folders. Braces give
alternatives: `is:{file,symlink}`. A file picker implies `is:file`, and a folder picker `is:dir`.

**`recent:WHEN`** is an entry given by the picker since `WHEN`: `today` and `yesterday` (from the start of the day),
a weekday such as `wednesday` (from the start of the latest one, today if today is that day), or a duration: `5m`
(minutes), `24h`, `1w`.

**`mount:FSTYPE`** is an entry on a file system of that type, as `/proc/self/mountinfo` names it: `mount:zfs`,
`-mount:ntfs`.

A `-` before any term negates it, and each negated term excludes on its own: `-ext:jpg -ext:png` excludes both.

## Every example, as a test

`index/src/test/kotlin/barion/index/searchtest.kt` holds every example above and in the original brief, written in
the DSL, run against a tree made for them and indexed by the real indexer, each checked entry for entry against what it
should find. Each one's text form is the comment above it.

## Queries in Kotlin

A query is an immutable tree (`barion.index.query`): a `Query` is a list of `Term`s, and a term is a `Test`, negated
or not. Each kind of term is a case of `Test` (`Name`, `InPath`, `Regex`, `Dir`, `Ext`, `Type`, `Is`, `Recent`,
`Mount`), and what the language rules out cannot be built: a term's case is `Default`, `Sensitive` or `Insensitive`,
never two of them; a regex is over the `Name`, the `Stem` or the `Path`, never two of them; a glob is held as it was
parsed, so an unclosed `[` never gets as far as a search.

The DSL writes these trees term by term, as the text writes them. `+` adds a term and `-` adds it negated:

```kotlin
query {
    +word("report")                 // report
    +word("report", start = true)   // ^report
    +exactly("thing")               // =thing
    +word("*.{jpg,png}")            // *.{jpg,png}
    +raw("Img_*", Case.Sensitive)   // rc"Img_*"
    +path("~/Downloads/")           // ~/Downloads/
    +regex("^img_\\d{4}$", RegexOn.Stem) // r/^img_\d{4}$/s
    +dir("funny", Depth.between(3, 5))   // dir:funny:3-5
    -ext("jpg")                     // -ext:jpg
    +isA(Flag.File, Flag.Symlink)   // is:{file,symlink}
    +recent(Since.Within(1.days))   // recent:1d
}
```

What a word means (a substring or a glob, the stem or the whole name) is decided by the same functions the parser will
call (`TextPattern.of`, `NamePart.of`, `PathPatterns.parse`), so the DSL and the text cannot disagree.

A query is run with `index.search(query, context)`. The `SearchContext` says what the query itself does not: the
case-sensitivity toggle, the home folder, the aliases, and the time and zone that `recent:` counts from. A query that
cannot be run in its context (an alias that is not defined) is `Searched.Refused`, with why; otherwise the search is
`Searched.Found`, whose `Results` are a list of entries made only as each is asked for.

## How a search runs

The index holds its entries as columns (docs/plan.md, "In memory, and on disk"): one array of parents, one of names
back to back, one of kinds, one of devices, in preorder, so everything under a folder follows it directly. Two more
columns are worked out as the index is loaded: every name folded to lower case at the same offsets, and every name's
stem length.

Each term is compiled once per search into a check of one entry:

- a word becomes bytes, folded or not, found in the `names` column or the `folded` one with a byte compare, so
  matching a name allocates nothing;
- a glob becomes tokens over UTF-8 bytes, matched by the usual backtracking of `*`;
- a path, or a `dir:` with a path, becomes a small automaton over the names of an entry's ancestors, its states the
  bits of a `Long`;
- `type:` becomes a sorted array of the group's extensions, each packed into a `Long`, searched by halving;
- `mount:` becomes, for each block, a flag per device; `recent:` a bit set of the entries picked since then.

A search then walks each block in order, keeping the folders it is under on a stack. A check that asks about
ancestors (`dir:`, a path) keeps a state at each level of that stack, worked out once as the walk enters a folder, so
it costs one step per folder and one look per entry, never a climb up the tree per entry. Checks are run cheapest
first; every one must pass, so the order changes nothing but the time. A block of more than 65,536 entries is cut into
slices walked on every core, each slice starting its stack from the ancestors of its first entry.

Measured on this project's development machine (4 cores), over a made-up tree of 3,000,000 entries
(`./gradlew :index:test --tests barion.index.SearchBenchmark -Pindex.benchmark=3000000`), the best of seven runs of
each:

| query | found | time |
|---|---|---|
| `report` | 33,414 | 12 ms |
| `^img_2024` | 138,368 | 8 ms |
| `*.jpg` | 1,106,888 | 47 ms |
| `img_[0-9]*` | 1,106,888 | 17 ms |
| `ext:pdf` | 264,251 | 16 ms |
| `type:image` | 1,106,888 | 21 ms |
| `is:dir` | 131,842 | 5 ms |
| `dir:photos *.jpg` | 1,106,888 | 30 ms |
| `dir:project*:1 =readme` | 13,878 | 12 ms |
| `/home/*/Documents/` | 352,114 | 10 ms |
| `work/2024/` | 34,467 | 9 ms |
| `r/^img_\d{4}/` | 1,106,888 | 48 ms |
| `r/2024/p` | 180,409 | 204 ms |

The first run of each takes 60 to 320 ms more while the JVM compiles it, which a daemon pays once. A regex over paths
is the slow one, since Java's regexes run over strings and every path is made into one; every other term reads bytes
where they lie.

## Not yet

- The text language's parser, a parser combinator that makes these same queries from text.
- Sorting results (by name, path, or how recently they were picked); they come in the index's order, by path within
  each block.
- A trigram index, to find a substring without reading every name; at a few million entries reading them all takes
  milliseconds, so it waits until a measurement says otherwise.
- One table of extensions for `type:` and the picker's icons and kind filters, which have their own today.
