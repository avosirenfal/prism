# build-logic

The build's conventions, as Gradle convention plugins: an included build whose plugins every module applies by
id, so a module's `build.gradle.kts` holds only what is particular to it and nothing in the root reaches into a
module (which is what keeps the configuration cache and parallel configuration on). A plugin's id is its file
name under `src/main/kotlin`, which is why those files are spelled `barion.<name>.gradle.kts`.

| plugin | applied by | what it does |
|---|---|---|
| `barion.kotlin-jvm` | every module | Kotlin on the catalog's JDK, JUnit, the tests allowed native access (FFM) |
| `barion.compose` | picker, app | the Compose compiler, Compose Desktop for the host, Material 3, headless tests |
| `barion.ktlint` | the root | `ktlintCheck` and `ktlintFormat` with the house rules (`ktlint-rules`) |

`src/main/kotlin/barion/` holds the typed tasks and helpers the plugins and module scripts use: the version
catalog for scripts that cannot use the generated accessors, the runtime-jar resolution attributes, the ktlint
tasks, and the two tasks that turn the probes declared in the Kotlin eBPF DSL into BPF objects
(`EmitBpfSources`, which runs the `probe` module's emitter, and `CompileBpf`, which runs clang's BPF target).
