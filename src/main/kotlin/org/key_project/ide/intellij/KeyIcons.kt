package org.key_project.ide.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.util.Base64
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * KeY's own icons for the states it has a verdict on.
 *
 * They come from KeY as image data, so the plugin ships none of KeY's assets, and they can
 * only be fetched while KeY is running. They are fetched once per size and theme and kept
 * here, being the same for every view.
 *
 * A state KeY has no verdict on gets no icon: an obligation nobody has proved is drawn as
 * KeY draws it, with nothing, and a state the bridge does not recognise is drawn with the
 * IDE's own question mark rather than borrowing KeY's authority.
 *
 * Which of them a dark theme needs drawn differently is the bridge's answer, which serves a
 * set for each, so a state looks the same here as it does in any other editor.
 */
@Service(Service.Level.PROJECT)
class KeyIcons(private val project: Project) {

    /** The icons by the size and the theme they were made for. */
    @Volatile
    private var made: Map<Pair<Int, Boolean>, Map<String, Icon>> = emptyMap()

    /**
     * Fetches the icons at one size, unless this theme already has them.
     *
     * Fetching talks to KeY, so a view calls this while it loads rather than while it draws,
     * and reads what was fetched with [forStatus].
     *
     * @param size the edge length to draw them at
     */
    fun fetch(size: Int) {
        val dark = isDarkTheme()
        if (made.containsKey(size to dark)) {
            return
        }
        val verification = KeyBridge.of(project).connected().getVerificationService()
        val served = verification.icons(IconsParams(size)).await(Deadline.CONTEXT)
        val fetched = (if (dark) served.darkIcons else served.icons)
            .mapNotNull { (status, dataUri) -> decode(dataUri)?.let { status to it } }
            .toMap()
        made = made + ((size to dark) to fetched)
    }


    /**
     * The icon for a state, as a view draws it.
     *
     * Reads what [fetch] fetched for the theme that is current, so a view that draws again
     * after a theme change gets the icons that theme needs.
     *
     * @param status the state as the bridge reports it
     * @param size the edge length asked of [fetch]
     * @return the icon, or null where KeY draws nothing or nothing is fetched yet
     */
    fun forStatus(status: String, size: Int): Icon? {
        if (status == UNPROVED) {
            return null
        }
        val icons = made[size to isDarkTheme()] ?: return null
        return icons[status] ?: AllIcons.General.QuestionDialog
    }

    /**
     * KeY's continue icon, at the size a gutter draws it.
     *
     * KeY draws it from a font rather than storing it as a file, so the bridge renders it.
     * It is read at twice the size it is shown at, so that it stays sharp on a dense screen
     * and when the editor is scaled up.
     *
     * Reading talks to KeY, as [fetch] does, so this belongs on a background thread.
     *
     * @param size the edge length to draw it at
     * @return the icon, or null where KeY did not send one
     */
    fun verify(size: Int): Icon? {
        fetch(2 * size)
        val read = made[2 * size to isDarkTheme()]?.get(VERIFY) as? ImageIcon ?: return null
        return ImageIconAt(read.image, size, size)
    }

    private fun decode(dataUri: String): Icon? {
        val comma = dataUri.indexOf(',')
        if (comma < 0) {
            return null
        }
        return runCatching {
            ImageIcon(Base64.getDecoder().decode(dataUri.substring(comma + 1)))
        }.getOrNull()
    }

    companion object {
        /** KeY draws nothing for an obligation nobody has proved. */
        private val UNPROVED = ProofStatus.NONE


        /** What the bridge calls KeY's continue icon, which stands for no state. */
        private const val VERIFY = "VERIFY"

        /** Whether the IDE is in a dark theme, which is what the editor's background says. */
        private fun isDarkTheme(): Boolean = ColorUtil.isDark(JBColor.background())



        fun of(project: Project): KeyIcons = project.service()
    }
}
