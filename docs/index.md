# KeY for IntelliJ IDEA

Prove that your Java methods do what their JML contracts say, without leaving the IDE.
Select a method, a class or a directory, ask for it to be verified, and
[KeY](https://key-project.org) proves in the background. The proofs are written into the
project, and the KeY tool window shows what the project can be asked to prove and how far
each obligation has got.

The plugin does not contain KeY. It starts the KeY you point it at, drives it without a
window, and reads what it says.

The source, the issue tracker and the releases are on GitHub:
**[github.com/unp1/key-intellij-plugin](https://github.com/unp1/key-intellij-plugin)**.

## What you need

| | |
|---|---|
| IntelliJ IDEA | 2026.1 or later, Community or Ultimate |
| A KeY distribution | a `key-*-exe.jar`, built or downloaded |
| The shared KeY component | a `key-ide-common-*-all.jar`, built from [key-ide-common](https://github.com/unp1/key-ide-common) |
| A Java project with JML | contracts written as `/*@ ... @*/` comments above the methods |

The plugin starts both jars with the IDE's own Java runtime, so no `JAVA_HOME` is needed.

## Where to go next

- [Installing](install.md) — the two jars, the plugin from disk, and where to point it
- [A first project](first-project.md) — from an empty project to a closed proof
- [The interface](interface.md) — what every view, mark and icon means
- [Settings](settings.md) — every setting and what it decides

The [VS Code extension](https://github.com/unp1/key-vscode-plugin) does the same work with
the same words, so what you learn here carries over.
