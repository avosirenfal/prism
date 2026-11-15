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
| `-s`, `--select` | exactly one match, printed; more than one is an error, or with `-i` a menu (below) |
| `--files`, `--dirs` | files only, folders only: `is:file`, `is:dir`, said in fewer letters |
| `-0`, `--null` | a NUL after each path in place of a new line, for `xargs -0` and names with new lines in them |

Its exit status says what happened, as `grep`'s does:

| status | means |
|---|---|
| 0 | something was found, and printed |
| 1 | nothing matches; or there is no index to search, which it says |
| 2 | the query cannot be searched for: what is wrong with it is said on standard error |
| 3 | `--select` found more than one match, and there was no menu to choose from |
| 130 | the menu was cancelled |

### Words that start with a dash

In the search's language a dash before a term means "not": `-draft` leaves out names with "draft" in them. On a
command line a dash starts an option. `prism search` reads its words in one of two ways, by whether there is a `--`
among them.

**With no `--`**, the query and the options are mixed, and told apart word by word:

- a word that is **exactly** one of its options (`-b`, `--best`, `-n`, `-0`, ...) is that option;
- any other word that starts with a dash is the query's: `-draft`, `-ext:pdf`, `-is:hidden`.

So `prism search report -draft -b` is the best match for `report -draft`. Short options are not run together here
(`-bi` is a term, "not bi"): if they were, a word like `-big` or `-sin` would be three options and not a term.

**With a `--`**, it says where the query begins, so nothing has to be guessed:

- what is before it is options, as any command reads them: short ones can be run together (`-si` is `-s` and `-i`),
  and one that takes a value is written last, its value after it (`-sin 5`);
- a word before it that starts with a dash and is not an option, or options run together, is a mistake, said with
  status 2, rather than quietly taken for part of the query;
- what is after it is the query, every word of it, whatever it looks like.

```console
$ prism search -si -- report -draft
$ prism search -bn 5 -- -s
```

The second searches for "not s". That is how a term that looks like an option is written: after the two dashes. It
can also be written with the quotes the language gives it, inside quotes the shell takes:

```console
$ prism search report -- -s
$ prism search report '-"s"'
```

`-"s"` typed bare does not do it: the shell takes the quotes away before prism sees the word, and what arrives is
`-s`.

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
7 of 30 · Enter selects, Esc cancels
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

## Exactly one: `--select`

A script, or a function of your shell's, usually wants one path, not a list. `-s` is how a search is told so:

| what the query finds | `prism search -s` | `prism search -s -i` |
|---|---|---|
| one match | prints it, status 0 | prints it, status 0, with no menu |
| several | prints nothing; says how many on standard error, with the first five, status 3 | the menu; prints the one chosen, or nothing and status 130 where it is cancelled |
| none | prints nothing; "no matches" on standard error, status 1 | the same |

So `prism search -si -- QUERY` is always one path on standard output, or none and a status that is not 0. It counts
every match there is, not only those that would be printed: `-s -n 1` over fifty matches is still several. `-n` and
`-a` say how many the menu holds, as they do for `-i` alone. `-b` is one match already, so with it `-s` and `-i` have
nothing left to do, and the best is printed.

## Going to what it finds

A program cannot change the folder of the shell that ran it; only the shell can. So prism prints where to go, and a
function of the shell's goes there. These go to a folder found, or to the folder a file found is in: at once where
one thing matches, and after choosing from the menu where several do.

For bash and zsh, in `~/.bashrc` or `~/.zshrc`:

```bash
# Go to what prism finds: a folder itself, or the folder a file is in.
pcd() {
	local found
	found="$(prism search -si -- "$@")" || return
	if [ -d "$found" ]; then
		cd -- "$found"
	else
		cd -- "$(dirname -- "$found")"
	fi
}
```

For fish, in `~/.config/fish/functions/pcd.fish`:

```fish
# Go to what prism finds: a folder itself, or the folder a file is in.
function pcd --description 'go to what prism finds'
	set -l found (prism search -si -- $argv); or return
	if test -d "$found"
		cd -- "$found"
	else
		cd -- (dirname -- "$found")
	end
end
```

```console
~ $ pcd prism docs
~/Projects/prism/docs $ pcd report .pdf
(a menu; Enter on ~/Documents/2026/report.pdf)
~/Documents/2026 $
```

The menu is drawn on the terminal and not on standard output, which is why `$(...)` can read the path while you are
choosing it. Cancelled, or with nothing found, the function returns the search's status and you stay where you were.

Without a function, the same by hand, for folders only:

```console
$ cd "$(prism search -s --dirs -- photos 2024)"
```

`prism search ... | cd` does not work in any shell: `cd` takes where to go as a word, not from its input, and in a
pipeline it would run in a shell of its own, whose folder is not yours.

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
time (GraalVM's native-image) from the same Kotlin: the `client` module, which holds what one prism asks another,
how, `prism search`, and the asking half of `prism dmenu`.

What it does with its words:

| words | done by |
|---|---|
| `search ...` | the client itself, which asks the prism running, as above |
| `dmenu ...` | the client itself: it reads the lines on its standard input and asks the prism running, which shows them and answers the one chosen (docs/launcher.md, "dmenu"); with nobody to ask, the Java prism, given the lines it read |
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
  is still the option, and everything after `--` is an argument; short options run together, the last alone taking
  a value; and, with a `--`, what is before it options alone, a dashed word that is none of them refused.
- `SearchTest` (client): which words are options and which the query, a word with a space quoted again; what each
  option prints and exits with; every match asked for a page at a time; options run together before a `--`, and a
  stray dashed word there refused with status 2; `--select`'s one match, its several and its none, and with a menu
  the one given without it and the one chosen; the menu's choice printed, cancelled, and refused without a terminal;
  and what was found said by one prism to another and read back.
- `MenuTest` (client): which lines are in sight for every place of the highlight, what each key is, and how the
  lines are drawn.
- `ClientTest` (client): words asked of a prism listening as they were typed, what is left to the Java prism, and
  where the Java prism is looked for.
- `PathSearchTest` (app): matches from an index best first and by page, a wrong query refused with why, the index's
  file read and reported missing, a running prism answering from its index, and what asks for a prism of its own
  refused.
- `tools/daemon/check.sh`: `prism search` answered by a running daemon, its statuses, `--select` giving one match
  and refusing several, options run together before a `--`, the bash function above changing folder, and a search
  reading the index's file once the daemon is gone; run with the app image,
  with the client built here, and with the Nix package.

The menu itself was tried on a terminal of its own (a pty, keys sent as a terminal sends them), by the client and by
the Java prism: `-si` with several matches (one chosen, and cancelled), with one (given with no menu), with none, and
run together with a count. The fish function above was read by fish 3.7 and gone with, to a file's folder, into a
folder, and to one with a space in its name. Neither is among the tests Gradle runs; the bash function is among the
check's, and was not tried in zsh.
