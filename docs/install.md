# Installing

Nothing here goes through a marketplace: the plugin is built and installed from disk, and
KeY is supplied by you.

## 1. Get a KeY

Either a release from [key-project.org](https://key-project.org), or a build of your own:

```
git clone https://github.com/KeYProject/key.git
cd key
./gradlew :key.ui:shadowJar
```

The file you want is `key.ui/build/libs/key-*-exe.jar`.

## 2. Build the shared KeY component

This is the process the plugin drives. It needs the KeY checkout beside it, because it is
compiled against KeY's API:

```
git clone git@github.com:unp1/key-ide-common.git
cd key-ide-common
./gradlew shadowJar
```

The file you want is `build/libs/key-ide-common-*-all.jar`.

!!! note
    Both projects need a JDK 21. The plugin later starts these jars with the IDE's own Java
    runtime, which is a JDK 21 as well.

## 3. Build the plugin

```
git clone git@github.com:unp1/key-intellij-plugin.git
cd key-intellij-plugin
./gradlew buildPlugin
```

The file you want is `build/distributions/key-intellij-*.zip`.

## 4. Install it from disk

1. **Settings** → **Plugins**
2. the gear icon → **Install Plugin from Disk…**
3. choose the zip built above
4. restart the IDE

To update an installed version, uninstall it first, restart, then install the new zip and
restart again. Installing over a running version does not take.

## 5. Point it at KeY

Once per machine, in **Settings** → **Tools** → **KeY**:

- **KeY jar** — the `key-*-exe.jar` from step 1
- **Bridge jar** — the `key-ide-common-*-all.jar` from step 2

The rest of that page has working defaults; [Settings](settings.md) explains them.

You are ready for [a first project](first-project.md).

## Rebuilding later

The plugin holds the jars by path, so replacing a jar in place is enough — with one catch:
a running KeY keeps the classes it loaded. After replacing the shared component, press
**Restart KeY Bridge** in the KeY tool window, or reopen the project.
