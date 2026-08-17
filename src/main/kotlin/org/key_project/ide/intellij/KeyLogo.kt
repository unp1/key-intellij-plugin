package org.key_project.ide.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ScalableIcon
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Graphics
import java.awt.Image
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * KeY's logo, read from the KeY the user configured.
 *
 * The logo belongs to KeY and carries its licence, so the plugin ships no copy of it. It
 * is read from the jar the user configured, which is the same arrangement as for the status
 * icons: everything drawn comes from the user's own KeY at run time.
 */
object KeyLogo {

    private const val RESOURCE = "de/uka/ilkd/key/gui/images/key-color-icon-square.png"

    /** The tool window this logo belongs on, as plugin.xml registers it. */
    private const val TOOL_WINDOW = "KeY"

    /** The size a tool window's icon is drawn at. */
    private const val TOOL_WINDOW_SIZE = 13

    /** The size a gutter mark is drawn at. */
    private const val GUTTER_SIZE = 12

    /**
     * The logo at the size a tool window wants.
     *
     * @return the icon, or null when no KeY is configured yet, in which case the platform
     *         keeps its own
     */
    fun toolWindowIcon(): Icon? {
        val image = read() ?: return null
        return LogoIcon(image, TOOL_WINDOW_SIZE)
    }

    /**
     * The logo at the size a gutter mark wants.
     *
     * @return the icon, or null when no KeY is configured yet
     */
    fun gutterIcon(): Icon? {
        val image = read() ?: return null
        return LogoIcon(image, GUTTER_SIZE)
    }

    /**
     * Puts the logo on the project's KeY tool window.
     *
     * The platform draws the stripe before it instantiates the factory, so a factory that
     * offers the icon offers it too late: the stripe is already there, with the placeholder
     * every tool window starts with. Setting it on the registered window is what shows the
     * logo before anyone has opened the window.
     *
     * Called when a project opens and again when a KeY is configured, since the logo can
     * only be read once there is a KeY to read it from.
     *
     * @param project the project whose tool window to mark
     */
    fun showOnToolWindow(project: Project) {
        if (project.isDisposed) {
            return
        }
        val icon = toolWindowIcon() ?: return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.setIcon(icon)
    }

    private fun read(): Image? {
        val configured = KeySettings.instance().keyJarPath
        if (configured.isBlank() || !Files.isRegularFile(Path.of(configured))) {
            return null
        }
        return runCatching {
            ZipFile(configured).use { jar ->
                val entry = jar.getEntry(RESOURCE) ?: return null
                ImageIcon(jar.getInputStream(entry).use { it.readBytes() }).image
            }
        }.getOrNull()
    }

    /**
     * An icon the platform can resize.
     *
     * A tool window icon has to be scalable, so that it can be drawn at whatever display
     * and user scaling is in force. The platform refuses a plain image icon.
     */
    private class LogoIcon(image: Image, size: Int) : ImageIconAt(image, size, TOOL_WINDOW_SIZE)
}

/**
 * An image drawn at a size of the platform's choosing.
 *
 * The platform scales the icons it is handed, in the gutter by the editor's scale and
 * everywhere by the screen's. A plain image icon cannot be scaled and is drawn at its own
 * size, which is how a gutter mark ends up cut off. This one draws its image at whatever
 * size it is asked for, so the image should be read at twice the size it is usually shown
 * at, to stay sharp on a dense screen.
 *
 * @param image the image to draw
 * @param size the size to draw it at
 * @param baseSize the size the platform's scale factors are relative to
 */
internal open class ImageIconAt(
    private val image: Image,
    private val size: Int,
    private val baseSize: Int,
) : Icon, ScalableIcon {

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        graphics.drawImage(image, x, y, size, size, component)
    }

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun getScale(): Float = size.toFloat() / baseSize

    override fun scale(scaleFactor: Float): Icon =
        ImageIconAt(image, (baseSize * scaleFactor).toInt().coerceAtLeast(1), baseSize)
}
