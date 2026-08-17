package org.key_project.ide.intellij

import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * Draws the gutter mark for a declaration.
 *
 * KeY's own icons say what one proof is, and every listing of a proof obligation shows them.
 * A gutter mark stands for all obligations of a declaration at once and belongs to the
 * editor, so it uses the shapes an editor uses: a check for closed, a check in brackets for
 * closed but for lemmas, and a cross for open.
 *
 * The marks are drawn rather than read from a file. They follow the IDE's light and dark
 * themes that way, and they can be painted at whatever size the platform asks for.
 */
object StatusMarks {

    private val COLORS = mapOf(
        ProofMark.CLOSED to JBColor(Color(0x59, 0xA8, 0x69), Color(0x49, 0x9C, 0x54)),
        ProofMark.LEMMAS_LEFT to JBColor(Color(0xED, 0xA2, 0x00), Color(0xF0, 0xA7, 0x32)),
        ProofMark.OPEN to JBColor(Color(0xDB, 0x58, 0x60), Color(0xC7, 0x54, 0x50)),
    )

    /**
     * The mark at one size.
     *
     * @param mark how far the declaration has got
     * @param size the edge length to draw it at
     * @return the icon, or null for a declaration KeY has not judged, which is drawn with
     *         KeY's continue button instead
     */
    fun icon(mark: ProofMark, size: Int): Icon? =
        COLORS[mark]?.let { color -> MarkIcon(mark, size, size, color) }

    /**
     * One mark, painted on demand.
     *
     * @param mark how far the declaration has got
     * @param size the edge length to paint at
     * @param baseSize the size the platform's scale factors are relative to
     * @param color the colour of that mark
     */
    private class MarkIcon(
        private val mark: ProofMark,
        private val size: Int,
        private val baseSize: Int,
        private val color: JBColor,
    ) : Icon, ScalableIcon {

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val canvas = graphics.create() as Graphics2D
            try {
                canvas.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                canvas.setRenderingHint(
                    RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE,
                )
                canvas.translate(x, y)
                canvas.color = color
                paint(canvas)
            } finally {
                canvas.dispose()
            }
        }

        private fun paint(canvas: Graphics2D) {
            when (mark) {
                ProofMark.CLOSED -> {
                    canvas.stroke = stroke(0.17)
                    canvas.draw(check(0.18, 0.84, 0.26, 0.76))
                }

                ProofMark.LEMMAS_LEFT -> {
                    // The check is drawn smaller and the brackets thinner than the closed
                    // mark, so that all three fit the same square and stay apart at the
                    // size a gutter draws them at.
                    canvas.stroke = stroke(0.09)
                    canvas.draw(brackets())
                    canvas.stroke = stroke(0.14)
                    canvas.draw(check(0.33, 0.68, 0.36, 0.64))
                }

                ProofMark.OPEN -> {
                    canvas.stroke = stroke(0.17)
                    canvas.draw(cross())
                }

                // Never drawn: a declaration KeY has not judged has no colour and so no
                // icon of this kind.
                ProofMark.UNJUDGED -> Unit
            }
        }

        /** A check inside the given fractions of the square. */
        private fun check(left: Double, right: Double, top: Double, bottom: Double): Path2D {
            val path = Path2D.Double()
            path.moveTo(at(left), at(bottom - (bottom - top) * 0.44))
            path.lineTo(at(left + (right - left) * 0.35), at(bottom))
            path.lineTo(at(right), at(top))
            return path
        }

        private fun cross(): Path2D {
            val path = Path2D.Double()
            path.moveTo(at(0.26), at(0.26))
            path.lineTo(at(0.74), at(0.74))
            path.moveTo(at(0.74), at(0.26))
            path.lineTo(at(0.26), at(0.74))
            return path
        }

        private fun brackets(): Path2D {
            val path = Path2D.Double()
            path.moveTo(at(0.24), at(0.12))
            path.quadTo(at(0.07), at(0.5), at(0.24), at(0.88))
            path.moveTo(at(0.76), at(0.12))
            path.quadTo(at(0.93), at(0.5), at(0.76), at(0.88))
            return path
        }

        /** A pen wide enough to stay visible in a gutter, at whatever size is asked for. */
        private fun stroke(width: Double): BasicStroke =
            BasicStroke(
                (size * width).toFloat().coerceAtLeast(1f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

        private fun at(fraction: Double): Double = size * fraction

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size

        override fun getScale(): Float = size.toFloat() / baseSize

        override fun scale(scaleFactor: Float): Icon =
            MarkIcon(mark, (baseSize * scaleFactor).toInt().coerceAtLeast(1), baseSize, color)
    }
}
