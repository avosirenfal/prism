# prism from a shell: `prism search`, going to what it finds, and the client

The index the daemon keeps is not only the picker's and the launcher's. `prism search` runs a query from a shell and
prints the paths it finds, the best first, so a script can read them, `cd` can be given one, and a person can choose
one from a menu without leaving the terminal. This document says how it is used, how a search changes a shell's
folder, and what the `prism` a shell starts is, which is why all of it answers in a few milliseconds.

## Searching

```console
$ prism search report .pdf
/home/ember/Documents/report.pdf
/home/ember/Documents/q4-report.pdf
/home/ember/old/2023/report.pdf
```

The words after `search` are one query, in the search's own language (docs/search.md): joined with spaces, every
term to be fitted, the results in the order the picker's search gives them. So everything written there works here:
`prism search ~/Documents/ -draft`, `prism search type:image new:1d`, `prism search 'r/^img_\d{4}/'`.

| option | what it does |
|---|---|
| `-n N`, `--count N` | how many matches at most; 20, unless told |
| `-a`, `--all` | every match, however many |
| `-b`, `--best` | only the best one |
| `-i`, `--interactive` | choose one from a menu on the terminal, and print it (below) |
| `-g`, `--goto` | print the folder to go to, in place of the match (below) |
| `--files`, `--dirs` | files only, folders only: `is:file`, `is:dir`, said in fewer letters |
| `-0`, `--null` | a NUL after each path in place of a new line, for `xargs -0` and names with new lines in them |

Its exit status says what happened, as `grep`'s does:

| status | means |
|---|---|
| 0 | something was found, and printed |
| 1 | nothing matches; or there is no index to search, which it says |
| 2 | the query cannot be searched for: what is wrong with it is said on standard error |
| 3 | `--goto` found more than one place to go |
| 130 | the menu was cancelled |

### Words that start with a dash

In the search's language a dash before a term means "not": `-draft` leaves out names with "draft" in them. On a
command line a dash starts an option. `prism search` reads its words so:

- a word that is **exactly** one of its options (`-b`, `--best`, `-n`, `-0`, ...) is that option;
- any other word that starts with a dash is the query's: `-draft`, `-ext:pdf`, `-is:hidden`;
- everything after `--` is the query's, whatever it looks like.

So `prism search report -draft -b` is the best match for `report -draft`. Short options are not run together
(`-bi` is a term, "not bi"), which keeps the options few enough to remember: a negated term is an option only if it
is one of `-a`, `-b`, `-g`, `-i`, `-n` or `-0`. To leave out names with a "g" in them, write the term after two
dashes, or with the quotes the language gives it, inside quotes the shell takes:

```console
$ prism search report -- -g
$ prism search report '-"g"'
```

`-"g"` typed bare does not do it: the shell takes the quotes away before prism sees the word, and what arrives is
`-g`.

A shell takes a word's quotes in general, so a word that arrives with a space in it is taken for a quoted term:
`prism search "tax return"` searches for `"tax return"`, and `prism search dir:"old photos"` for `dir:"old photos"`.

### Choosing from a menu

`prism search -i note` shows the matches in a menu, eight of them in sight, and prints the one chosen:

```
  /home/ember/notes/note-28.md
  /home/ember/notes/note-27.md
  /home/ember/notes/note-26.md
  /home/ember/notes/note-25.md
> /home/ember/notes/note-24.md
  /home/ember/notes/note-23.md
  /home/ember/notes/note-22.md
  /home/ember/notes/note-21.md
7 of 30 · Enter chooses, Esc cancels
```

| key | does |
|---|---|
| Down, `j`, Ctrl+N; Up, `k`, Ctrl+P | the highlight, a line at a time |
| Page Down, Page Up | eight lines at a time |
| Home, `g`; End, `G` | the first match; the last |
| `1` to `8` | choose that line of those in sight |
| Enter | choose the highlighted line |
| Escape, `q`, Ctrl+C | choose nothing: status 130, nothing printed |

The highlight moves down the lines until it is on the middle one. From there the list moves under it, the highlight
staying on that line, so there is always as much to see below the line you are on as above it; when the last match
comes into sight the list stops, and the highlight goes down to it. Going up is the same the other way. The menu
holds as many matches as would have been printed: 20, or `-n`'s number, or all of them with `-a`.

The menu is drawn on the terminal itself (`/dev/tty`), not on standard output, so what is chosen can still be read
by whatever ran the search:

```console
$ $EDITOR "$(prism search -i --files config)"
```

When it ends, however it ends, the terminal is as it was and the menu's lines are gone. With no terminal (a script
run by cron), `-i` says so and exits 1.

## Going to what was found

`prism search -g QUERY` is for changing folder. With one match, it prints the folder to go to: the match itself, if
it is a folder, and otherwise the folder it is in. With several, it lists them on standard error and exits 3, since
it cannot know which you meant: narrow the query, take the best with `-b`, or choose with `-i`.

A program cannot change the folder of the shell that ran it; only the shell can. So one line in the file your shell
reads as it starts gives the shell a function that does:

| shell | the line |
|---|---|
| bash (`~/.bashrc`) | `eval "$(prism shell bash)"` |
| zsh (`~/.zshrc`) | `eval "$(prism shell zsh)"` |
| fish (`~/.config/fish/config.fish`) | `prism shell fish \| source` |

With it:

```console
~ $ prism search -g prism docs
~/Projects/prism/docs $ prism search -g -i report
(a menu; Enter on ~/Documents/2026/report.pdf)
~/Documents/2026 $
```

The function is called `prism` and stands in front of the program. Anything but a search it passes on untouched. For
a search, it names a file in `PRISM_GOTO_FILE` for prism to write the folder into, and changes to that folder
afterwards if anything was written. So the search prints what it would have printed without the function (its
matches, its menu, what is wrong with a query), and only a `--goto` that found where to go changes anything.
`prism shell bash` prints the function, a dozen lines, if you would rather read it than take that on trust.

Without the function, `--goto` prints the folder, and `cd "$(prism search -g -b report)"` does the same by hand.

## Who searches

Nothing in `prism search` reads the index. It asks:

1. **The prism already running**, at its socket (docs/running.md), for the matches of the query: `prism matches`, a
   command nobody types, which answers with a count and the paths. The daemon searches its live index, so a file made
   a moment ago is found. Matches are asked for 20,000 at a time, so `-a` over a million results is fifty questions.
2. **The index's file**, where no prism is running or the one that is keeps no index: read once, as it was last
   written (`$XDG_CACHE_HOME/prism/index.prism`). With no file either, it says how to make one: `prism daemon` keeps
   one, `prism index` writes one once.

## The client: the `prism` a shell starts

Starting Java takes about a fifth of a second before a program has read its first word. Asking the daemon for a
window, the launcher, its status or a search needs none of what that time buys: it is a few words written to a
socket. So the `prism` on your path, in the flake's package, is not Java. It is a small program compiled ahead of
time (GraalVM's native-image) from the same Kotlin: the `client` module, which holds what one prism asks another and
how, `prism search`, and `prism shell`.

What it does with its words:

| words | done by |
|---|---|
| `search ...`, `shell ...` | the client itself; a search asks the prism running, as above |
| anything else, with a prism running | sent to it as typed. It reads them as its own command line, and answers what they printed |
| anything nobody answered | the Java prism, started with the same words, its input and output the client's own |

The third row is everything else there is: no prism running, a command no prism does for another (`prism index`,
`prism daemon`, `prism pick`), `--help`, words that say how a window should be (`--isolated`, `--theme`, `--index`),
a mistake in the words, a search with no daemon to ask. The Java prism is the same prism it always was, and does what
it always did; to whoever typed the command there is one `prism`, which is quicker when the daemon can answer.

Because the client sends the words unread, the prism that receives them decides whether they are its to do. A
command it does for others says which of its invocations ask for a process of their own (`Served.apart`), and those
are refused, as a command it does not do for others is; the client then starts the Java prism, which does them.

Measured in the development container, from the command being run to its returning (the daemon running, on a
virtual display; both are the Nix package's, the Java prism with its cache of classes; each is the middle of seven
runs):

| | the client | the Java prism |
|---|---|---|
| `prism status` | about 5 ms | about 0.15 s |
| `prism launcher` (the daemon has shown it) | about 16 ms | about 0.18 s |
| `prism search report` | about 10 ms | about 0.16 s |
| `prism ~/Documents` (the daemon has opened the window) | about 12 ms | about 0.16 s |
| `prism shell bash` (nobody asked) | about 5 ms | about 0.13 s |

### Where the Java prism is

The client looks for it in `PRISM_JAVA`, if that names a program, and otherwise beside itself: `../libexec/prism/prism`
from the folder the client is in, which is where the package puts it. Without either it says so, with the status a
shell gives a command it cannot find (127).

What is to stay is run as the Java prism directly: the package's systemd units, and the daemon when it starts itself
apart from a terminal, name `libexec/prism/prism`, so no client sits waiting on a daemon.

### Building it

```console
$ ./gradlew :client:nativeImage        # with GRAALVM_HOME set, or -Pprism.graalvm=DIR
$ client/build/native/prism status
```

GraalVM is needed for that task and for nothing else: `./gradlew build` and the tests do not use it, and the app
image (`createDistributable`) is the Java prism alone, which does everything the client does, a fifth of a second
later. The flake's dev shell sets `GRAALVM_HOME`; the package builds the client itself.

## Tested

- `CliTest`: a word with a dash that names no option is an argument where a command says so, an option's own word
  is still the option, and everything after `--` is an argument.
- `SearchTest` (client): which words are options and which the query, a word with a space quoted again; what each
  option prints and exits with; every match asked for a page at a time; `--goto`'s folder, its several, its file
  for the shell's function; the menu's choice printed or gone to, cancelled, and refused without a terminal; and what
  was found said by one prism to another and read back.
- `MenuTest` (client): which lines are in sight for every place of the highlight, what each key is, and how the
  lines are drawn.
- `ClientTest` (client): words asked of a prism listening as they were typed, what is left to the Java prism, and
  where the Java prism is looked for.
- `PathSearchTest` (app): matches from an index best first and by page, a wrong query refused with why, the index's
  file read and reported missing, a running prism answering from its index, and what asks for a prism of its own
  refused.
- `tools/daemon/check.sh`: `prism search` answered by a running daemon, its statuses, `--goto` and the shell's
  function changing folder, and a search reading the index's file once the daemon is gone; run with the app image,
  with the client built here, and with the Nix package.

The menu itself was tried on a terminal of its own (a pty, keys sent as a terminal sends them), by the client and by
the Java prism; that is not among the tests Gradle runs.
