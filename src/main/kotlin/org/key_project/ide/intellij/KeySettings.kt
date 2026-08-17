package org.key_project.ide.intellij

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where this machine keeps KeY.
 *
 * KeY is supplied by the user rather than bundled, so the plugin has to be told where it
 * is. This is a machine-wide setting rather than a project one, because the same
 * installation serves every project.
 */
@State(name = "KeYInstallation", storages = [Storage("key-ide.xml")])
class KeySettings : PersistentStateComponent<KeySettings> {

    /** The KeY distribution, a `key-*-exe.jar`. */
    var keyJarPath: String = ""

    /** The KeY component both IDE integrations share, a `key-ide-common-*-all.jar`. */
    var bridgeJarPath: String = ""

    /**
     * Where the bridge's KeY keeps its own files.
     *
     * KeY keeps settings, logs and caches in a home directory. By default the bridge is
     * given one inside the project, `.key/tool`, so that nothing of one project reaches
     * another and nothing of the user's own KeY reaches the bridge; the settings files in
     * it are reset at every start, so the defaults stay KeY's. The other choice is the home
     * a KeY started by hand uses, `~/.key`, which then also lends the bridge its saved
     * settings.
     */
    var keyHome: KeyHome = KeyHome.PROJECT

    /**
     * Whether saving a verified source replays its proofs and proves again what an edit
     * left unproved.
     *
     * On by default, so that what the views show follows the sources without being asked
     * for. The user's own choice is kept here and restored at the next start.
     */
    var verifyOnSave: Boolean = true

    /** How the trash of replaced proofs is kept; see [TrashPolicy]. */
    var trashPolicy: TrashPolicy = TrashPolicy.NEVER

    /** The size, in megabytes, the trash is kept below under [TrashPolicy.BELOW_SIZE]. */
    var trashMegabytes: Int = 200

    /** The age, in days, at which a replaced proof is thrown away under [TrashPolicy.OLDER_THAN]. */
    var trashDays: Int = 30

    override fun getState(): KeySettings = this

    override fun loadState(state: KeySettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Why these settings cannot be used yet, or null when they can.
     *
     * This is checked before launching, so that a missing jar is named directly rather
     * than showing up as a process that dies immediately.
     */
    fun problem(): String? = bridgeProblem() ?: when {
        keyJarPath.isBlank() -> "No KeY jar is configured. Set it in Settings, Tools, KeY."
        !Files.isRegularFile(Path.of(keyJarPath)) -> "The KeY jar $keyJarPath does not exist."
        else -> null
    }

    /**
     * Why the bridge cannot be started yet, or null when it can.
     *
     * Reading the settings needs the bridge but no prover, so a page that only reads them
     * checks this and opens before KeY has been configured.
     *
     * @return the problem, as a sentence for the user
     */
    fun bridgeProblem(): String? = when {
        bridgeJarPath.isBlank() -> "No KeY bridge jar is configured. Set it in Settings, Tools, KeY."
        !Files.isRegularFile(Path.of(bridgeJarPath)) ->
            "The bridge jar $bridgeJarPath does not exist."
        else -> null
    }

    /** The choices for where the bridge's KeY keeps its files. */
    enum class KeyHome {
        /** `.key/tool` in the project, settings reset at every start. */
        PROJECT,

        /** `~/.key`, as a KeY started by hand uses. */
        STANDARD,
    }

    /**
     * How the trash of replaced proofs is kept.
     *
     * A proof that is proved again is not overwritten but moved to `proofs/.trash`, so that
     * a good proof is never lost to a worse attempt. Left alone the trash only grows; this
     * says when it is emptied.
     */
    enum class TrashPolicy {
        /** Keep everything. */
        NEVER,

        /** Empty it when the project is closed. */
        ON_QUIT,

        /** Throw the oldest away until it is below the size given. */
        BELOW_SIZE,

        /** Throw away what is older than the number of days given. */
        OLDER_THAN,
    }

    companion object {
        fun instance(): KeySettings = service()
    }
}
