plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "org.key_project.ide"
version = "0.1.0-dev"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // From 2025.3 JetBrains publishes one distribution rather than a Community and an
        // Ultimate one, so this is the platform both are built on. What keeps the plugin
        // free of paid-only features is its dependency on com.intellij.modules.platform
        // alone, declared in plugin.xml.
        intellijIdea("2026.2.1")
        // Checks the built plugin against every IDE it claims to support, which is what
        // makes sinceBuild a statement rather than a hope.
        pluginVerifier()
    }

    // The message layer, matching the bridge and KeY's own key-rpc. This plugin speaks
    // JSON-RPC to a separate process and links nothing of KeY, which is what keeps it
    // independent of KeY's licence.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The decisions worth testing are the ones about what a selection means and how a result
// reads, which are ordinary functions. Anything needing a running IDE is left to trying
// the plugin, since a platform fixture costs more than it tells us here.
tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

// The searchable options step starts a headless IDE to index settings for the search box.
// Opening the KeY window there would start bridges and load contexts, which is work with
// no one to see it, so the step is off.
tasks.named("buildSearchableOptions") {
    enabled = false
}
