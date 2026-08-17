package org.key_project.ide.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.Icon

/**
 * The contexts the project declares.
 *
 * @param project the project to read
 * @return the context ids, in configuration order
 */
private fun contextsOf(project: Project): List<String> =
    KeyProject.of(project).config().contexts.map { it.id }

/** The KeY tool window, holding the project's proof obligations. */
class KeyToolWindowFactory : ToolWindowFactory, DumbAware {

    /**
     * KeY's own logo, read from the KeY the user configured rather than shipped with the
     * plugin.
     *
     * The platform asks the factory for the icon, and asks before the window is built, so
     * setting it while building the content is too late: the window is already drawn with
     * the default. Null leaves the default in place, which is the case where no KeY is
     * configured yet.
     */
    override val icon: Icon? get() = KeyLogo.toolWindowIcon()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Asked for again here, because the icon can only be read once a KeY is
        // configured, which may have happened since the window was registered.
        KeyLogo.toolWindowIcon()?.let { toolWindow.setIcon(it) }

        // From here on the views follow the project's proofs, including changes made
        // outside the plugin.
        WatchProofs.start(project)

        val contents = ContentFactory.getInstance()
        // The verification table comes first, because how much is proved is what a user
        // opens the window to see. It is a tab of our own rather than one in the Problems
        // view: a tab there has to implement an interface the platform marks internal, and
        // a plugin built on that breaks whenever it changes.
        val verification = VerificationPane(project)
        val verificationTab = contents.createContent(verification.component, "Verification", false)
        // Closing the tab releases the pane, and with it what it listens to.
        Disposer.register(verificationTab, verification)
        toolWindow.contentManager.addContent(verificationTab)

        val obligations = ObligationTree(project)
        val obligationsTab =
            contents.createContent(obligations.component, "Proof Obligations", false)
        // Closing the tab releases the tree, and with it what it listens to.
        Disposer.register(obligationsTab, obligations)
        toolWindow.contentManager.addContent(obligationsTab)

        // What a proof rests on, filled by the Show Dependencies action.
        val dependencies = DependencyPane(project)
        val dependenciesTab = contents.createContent(dependencies.component,
            KeyToolWindow.DEPENDENCIES_TAB, false)
        Disposer.register(dependenciesTab, dependencies)
        Disposer.register(dependenciesTab) { KeyToolWindow.forget(project) }
        toolWindow.contentManager.addContent(dependenciesTab)
        KeyToolWindow.register(project, dependencies)

        // Both tabs, since a refresh is the user saying that what they see is out of date,
        // and proofs can change on disk without the bridge being the one to change them.
        toolWindow.setTitleActions(
            listOf(
                VerifyAllAction(project),
                ReplayAllAction(project),
                RefreshAction(obligations, verification),
                VerifyOnSaveToggle(),
                ProverSwitch(project),
                RestartBridgeAction(project),
            ),
        )
        // Declaring a context is what turns the plugin on for a project, so the window
        // follows that without being asked again.
        KeyProject.of(project).onChanged(obligationsTab) { obligations.refresh() }
        obligations.refresh()
    }

    /**
     * Stops the KeY the plugin drives and starts it again.
     *
     * A bridge can be stuck, or be running a jar that has since been replaced. Restarting it
     * is otherwise a matter of closing the project. The settings bridge goes with it, since
     * a user asking for a restart means the plugin's processes rather than one of them.
     */
    private class RestartBridgeAction(private val project: Project) : AnAction(
        "Restart KeY Bridge",
        "Stop the KeY the plugin drives and start it again",
        AllIcons.Actions.Restart,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) {
            KeyTasks.of(project).launch("Restarting the KeY bridge") {
                KeyConfigBridge.of(project).restart()
                KeyBridge.of(project).restart()
            }
        }
    }

    /**
     * Turns keeping the proofs up with the sources on and off.
     *
     * Saving a source then replays that context's proofs and proves what they leave
     * unproved. It is on until the user turns it off, and that choice is kept.
     */
    private class VerifyOnSaveToggle : ToggleAction(
        "Verify on Save",
        "Replay a context's proofs when its sources are saved, and prove what is left unproved",
        AllIcons.General.InspectionsEye,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(event: AnActionEvent): Boolean =
            KeySettings.instance().verifyOnSave

        override fun setSelected(event: AnActionEvent, selected: Boolean) {
            KeySettings.instance().verifyOnSave = selected
        }
    }

    /** Proves everything the project can be asked to prove, a run for each context. */
    private class VerifyAllAction(private val project: Project) : AnAction(
        "Verify All",
        "Prove every proof obligation of every context",
        AllIcons.Actions.Execute,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) {
            KeyTasks.of(project).launch("Reading KeY contexts") {
                // A run for each context, all at once, and each asking for everything the
                // context holds rather than for a list of contracts.
                contextsOf(project).forEach { contextId ->
                    Verification.prove(project, contextId, emptyList(), contextId)
                }
            }
        }
    }

    /** Reads every saved proof of the project back and reports what they turn out to be. */
    private class ReplayAllAction(private val project: Project) : AnAction(
        "Replay All",
        "Replay every saved proof of every context",
        AllIcons.Actions.Rerun,
    ) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) {
            KeyTasks.of(project).launch("Replaying the project's proofs") {
                val work = contextsOf(project).associateWith { emptyList<String>() }
                val results = Verification.replay(project, work)
                KeyNotifications.info(project, Verification.summaryOf(results))
            }
        }
    }

    private class RefreshAction(
        private val obligations: ObligationTree,
        private val verification: VerificationPane,
    ) : AnAction("Refresh", "Read the project's contexts again", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) {
            obligations.refresh()
            verification.refresh()
        }
    }
}
