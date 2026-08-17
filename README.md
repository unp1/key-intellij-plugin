# KeY for IntelliJ IDEA

Verify Java methods with the [KeY](https://key-project.org) theorem prover without leaving
the IDE. Select a method, a class or a directory in the editor, the project view, the
gutter or the KeY tool window, and ask for it to be verified. KeY proves in the background,
the proofs are written to the project, and the tool window lists what the project can be
asked to prove and how far each obligation has got.

The plugin talks JSON to the [KeY IDE common](https://github.com/unp1/key-ide-common), which
is the process that drives KeY. Nothing of KeY is linked or bundled here: even the status
icons are fetched from the KeY the user configured.

## Documentation

A getting-started guide, a walk from an empty project to a closed proof, and what every view,
mark and icon means:

```
pip install mkdocs mkdocs-material
mkdocs serve
```

The pages are in `docs/`.

## Building

Requires a JDK 21.

```
./gradlew buildPlugin
```

The first build downloads the IntelliJ Platform, so it takes a while. The result is

```
build/distributions/key-intellij-0.1.0-dev.zip
```

Other tasks:

```
./gradlew test        # the unit tests
./gradlew runIde      # a sandbox IDE with the plugin installed, for trying it out
```

## Installing it locally

The plugin is not on the marketplace. Install the zip built above:

1. **Settings** → **Plugins**
2. the gear icon → **Install Plugin from Disk…**
3. choose `build/distributions/key-intellij-0.1.0-dev.zip`
4. restart the IDE

To update an installed version, uninstall it first, restart, then install the new zip and
restart again. Installing over a running version does not take.

Then point it at KeY, once per machine, in **Settings** → **Tools** → **KeY**:

- **KeY jar**: a `key-*-exe.jar` from a KeY build or release
- **Bridge jar**: the `key-ide-common-*-all.jar` built from
  [key-ide-common](https://github.com/unp1/key-ide-common)

Both processes are started by the plugin with the IDE's own Java runtime, so no `JAVA_HOME`
is needed.

Per project, declare what KeY should read in **Settings** → **Tools** → **KeY** →
**Contexts**: a Java source directory, and optionally a classpath, a bootclasspath and
`.key` includes. That is written to `.key/settings.json` in the project and is meant to be
committed with it. Proofs go to `proofs/` by default.

Requires IntelliJ IDEA 2026.1 or later, which is what `sinceBuild` declares. The plugin
depends on the platform alone, so it also works in the other IDEs built on it.

## Platforms

Linux, macOS and Windows. The plugin and the bridge are both Java, and reach each other
over a Unix domain socket where the platform has one, which includes Windows 10 and later,
and over a loopback port with a token otherwise.

## Licence

MIT, in [LICENSE](LICENSE). The plugin exchanges JSON with the bridge and links nothing of
KeY, which is what lets it be licensed this liberally; the bridge, which does link KeY, is
GPL-2.0-only.

Third-party components bundled in the zip: Eclipse LSP4J (dual EPL-2.0 or EDL-1.0, used
here under EDL-1.0) and the Kotlin standard library (Apache-2.0).
