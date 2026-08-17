package org.key_project.ide.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Where the user points the plugin at their KeY installation, and how the plugin keeps
 * house on this machine.
 *
 * KeY is not bundled, so the two jar paths are the only thing the plugin cannot determine
 * for itself. The rest are choices about the machine rather than about any project: where
 * the bridge's KeY keeps its files, and how long replaced proofs are kept.
 */
class KeySettingsConfigurable : Configurable {

    private val keyJar = TextFieldWithBrowseButton()
    private val bridgeJar = TextFieldWithBrowseButton()

    private val homeProject = JBRadioButton("Project (.key/tool), starts from KeY's defaults")
    private val homeStandard = JBRadioButton("User (~/.key), shared with KeY itself")

    private val verifyOnSave = JBCheckBox(
        "Verify on save: replay a context's proofs when its sources change, and prove " +
            "what is left unproved",
    )

    private val trashNever = JBRadioButton("Keep everything")
    private val trashOnQuit = JBRadioButton("Empty on quit")
    private val trashBelowSize = JBRadioButton("Max. size")
    private val trashOlderThan = JBRadioButton("Keep for")
    private val trashMegabytes = JSpinner(SpinnerNumberModel(200, 1, 1_000_000, 50))
    private val trashDays = JSpinner(SpinnerNumberModel(30, 1, 10_000, 1))

    override fun getDisplayName(): String = "KeY"

    /**
     * A chooser for one jar, titled for what it is being chosen for.
     *
     * @param title the dialog's title
     * @param description what to pick, shown under the title
     * @return the descriptor
     */
    private fun jarChooser(title: String, description: String) =
        FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter("jar")
            .withTitle(title)
            .withDescription(description)

    override fun createComponent(): JComponent {
        keyJar.addBrowseFolderListener(
            null,
            jarChooser("KeY Distribution", "Select key-*-exe.jar"),
        )
        bridgeJar.addBrowseFolderListener(
            null,
            jarChooser("KeY IDE Common", "Select key-ide-common-*-all.jar"),
        )
        ButtonGroup().apply {
            add(homeProject)
            add(homeStandard)
        }
        ButtonGroup().apply {
            add(trashNever)
            add(trashOnQuit)
            add(trashBelowSize)
            add(trashOlderThan)
        }
        // The numbers only mean anything under the policy they belong to.
        listOf(trashNever, trashOnQuit, trashBelowSize, trashOlderThan).forEach {
            it.addActionListener { enableNumbers() }
        }

        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("KeY jar:"), keyJar, 1, false)
            .addLabeledComponent(JBLabel("Bridge jar:"), bridgeJar, 1, false)
            .addSeparator(12)
            .addComponent(JBLabel("KeY home (settings, logs, caches):"))
            .addComponent(homeProject)
            .addComponent(homeStandard)
            .addSeparator(12)
            .addComponent(verifyOnSave)
            .addSeparator(12)
            .addComponent(JBLabel("Trash (proofs/.trash), holding proofs a rerun replaced:"))
            .addComponent(trashNever)
            .addComponent(trashOnQuit)
            .addComponent(row(trashBelowSize, trashMegabytes, "MB, oldest go first"))
            .addComponent(row(trashOlderThan, trashDays, "days"))
            .addComponent(
                JButton("Empty Now").apply {
                    addActionListener {
                        ProjectManager.getInstance().openProjects
                            .forEach { ProofTrash.of(it).emptyNow() }
                    }
                },
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return built
    }

    /** A policy with the number that belongs to it, on one line. */
    private fun row(button: JBRadioButton, number: JSpinner, unit: String): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(button)
            add(number.apply { border = JBUI.Borders.empty(0, 4) })
            add(JBLabel(unit))
        }

    private fun enableNumbers() {
        trashMegabytes.isEnabled = trashBelowSize.isSelected
        trashDays.isEnabled = trashOlderThan.isSelected
    }

    private fun chosenHome(): KeySettings.KeyHome =
        if (homeStandard.isSelected) KeySettings.KeyHome.STANDARD else KeySettings.KeyHome.PROJECT

    private fun chosenPolicy(): KeySettings.TrashPolicy = when {
        trashOnQuit.isSelected -> KeySettings.TrashPolicy.ON_QUIT
        trashBelowSize.isSelected -> KeySettings.TrashPolicy.BELOW_SIZE
        trashOlderThan.isSelected -> KeySettings.TrashPolicy.OLDER_THAN
        else -> KeySettings.TrashPolicy.NEVER
    }

    override fun isModified(): Boolean {
        val settings = KeySettings.instance()
        return keyJar.text != settings.keyJarPath ||
            bridgeJar.text != settings.bridgeJarPath ||
            verifyOnSave.isSelected != settings.verifyOnSave ||
            chosenHome() != settings.keyHome ||
            chosenPolicy() != settings.trashPolicy ||
            trashMegabytes.value != settings.trashMegabytes ||
            trashDays.value != settings.trashDays
    }

    override fun apply() {
        val settings = KeySettings.instance()
        settings.keyJarPath = keyJar.text.trim()
        settings.bridgeJarPath = bridgeJar.text.trim()
        settings.keyHome = chosenHome()
        settings.verifyOnSave = verifyOnSave.isSelected
        settings.trashPolicy = chosenPolicy()
        settings.trashMegabytes = trashMegabytes.value as Int
        settings.trashDays = trashDays.value as Int

        // The logo is read from the KeY that was just named, so a project whose tool window
        // still carries the placeholder gets it now rather than at the next restart.
        ProjectManager.getInstance().openProjects.forEach { KeyLogo.showOnToolWindow(it) }
    }

    override fun reset() {
        val settings = KeySettings.instance()
        keyJar.text = settings.keyJarPath
        bridgeJar.text = settings.bridgeJarPath
        verifyOnSave.isSelected = settings.verifyOnSave
        homeProject.isSelected = settings.keyHome == KeySettings.KeyHome.PROJECT
        homeStandard.isSelected = settings.keyHome == KeySettings.KeyHome.STANDARD
        when (settings.trashPolicy) {
            KeySettings.TrashPolicy.NEVER -> trashNever.isSelected = true
            KeySettings.TrashPolicy.ON_QUIT -> trashOnQuit.isSelected = true
            KeySettings.TrashPolicy.BELOW_SIZE -> trashBelowSize.isSelected = true
            KeySettings.TrashPolicy.OLDER_THAN -> trashOlderThan.isSelected = true
        }
        trashMegabytes.value = settings.trashMegabytes
        trashDays.value = settings.trashDays
        enableNumbers()
    }
}
