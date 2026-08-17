package org.key_project.ide.intellij

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * The prover, switched from the tool window's toolbar.
 *
 * The button reads what the project runs with: SC for the single-core prover, or MT with
 * the number of threads. A click switches between the two; a right click opens the
 * choices, single core or multi core with two, four, eight threads and so on up to the
 * cores of this machine. The choice is the project's, stored where the settings page
 * stores it, so the two agree.
 *
 * @param project the project whose prover is switched
 */
class ProverSwitch(private val project: Project) : AnAction(), CustomComponentAction, DumbAware {

    init {
        templatePresentation.text = label(ProverOptionsDto())
        templatePresentation.description = DESCRIPTION
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val contexts = KeyProject.of(project)
        event.presentation.text = label(contexts.prover())
        event.presentation.isEnabled = contexts.anyContext()
        event.presentation.description = if (contexts.anyContext()) DESCRIPTION else KeyAction.NO_CONTEXT
    }

    /** A click switches between the single-core and the multi-core prover. */
    override fun actionPerformed(event: AnActionEvent) {
        val contexts = KeyProject.of(project)
        val current = contexts.prover()
        // The thread count is kept across the switch, so that going back to multi core
        // brings back what was chosen before. Where none was ever chosen, KeY's own first
        // choice is stored, so that the button says what will run.
        val threads = if (current.threads > 0) current.threads else KEY_DEFAULT_THREADS
        contexts.setProver(ProverOptionsDto(parallel = !current.parallel, threads = threads))
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button = ActionButtonWithText(this, presentation, place, Dimension(0, 0))
        button.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybeChoose(event)

            override fun mouseReleased(event: MouseEvent) = maybeChoose(event)

            /** A right click offers every choice, at the pointer. */
            private fun maybeChoose(event: MouseEvent) {
                if (!event.isPopupTrigger || !button.isEnabled) {
                    return
                }
                event.consume()
                JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Prover", choices(), DataManager.getInstance().getDataContext(button),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
                    )
                    .show(RelativePoint(event))
            }
        })
        button.border = JBUI.Borders.empty(0, 4)
        return button
    }

    /** Every prover the button can be set to, the current one ticked. */
    private fun choices(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(ProverChoice(project, ProverOptionsDto(parallel = false, threads = 0)))
        for (threads in threadChoices()) {
            group.add(ProverChoice(project, ProverOptionsDto(parallel = true, threads = threads)))
        }
        return group
    }

    /** One entry of the menu, ticked when it is what the project runs with. */
    private class ProverChoice(private val project: Project, private val choice: ProverOptionsDto) :
        AnAction(menuLabel(choice)), Toggleable, DumbAware {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            val current = KeyProject.of(project).prover()
            val ticked = if (choice.parallel) {
                current.parallel && current.threads == choice.threads
            } else {
                !current.parallel
            }
            Toggleable.setSelected(event.presentation, ticked)
        }

        override fun actionPerformed(event: AnActionEvent) {
            KeyProject.of(project).setProver(choice)
        }
    }

    companion object {
        /** The worker count KeY itself starts the multi-core prover with. */
        private const val KEY_DEFAULT_THREADS = 4

        private const val DESCRIPTION =
            "Which prover runs the proofs. Click to switch between single and multi core, " +
                "right-click to choose the number of threads."

        /**
         * How a prover reads on the button.
         *
         * @param prover the prover
         * @return SC, or MT with the thread count
         */
        fun label(prover: ProverOptionsDto): String = when {
            !prover.parallel -> "SC"
            prover.threads > 0 -> "MT ${prover.threads}x"
            // Stored by hand without a count: KeY starts with its own.
            else -> "MT ${KEY_DEFAULT_THREADS}x"
        }

        /** How a prover reads in the menu. */
        private fun menuLabel(prover: ProverOptionsDto): String = when {
            !prover.parallel -> "Single core (SC)"
            else -> "Multi core, ${prover.threads} threads (MT ${prover.threads}x)"
        }

        /**
         * The thread counts offered: the powers of two from two up to the cores of this
         * machine, and the core count itself when it is not one of them.
         */
        fun threadChoices(): List<Int> {
            val cores = Runtime.getRuntime().availableProcessors()
            val choices = generateSequence(2) { it * 2 }.takeWhile { it <= cores }.toMutableList()
            if (cores > 1 && cores !in choices) {
                choices.add(cores)
            }
            return choices
        }
    }
}
