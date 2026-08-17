package org.key_project.ide.intellij

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.project.Project

/**
 * Draws KeY's icons again when the IDE changes theme.
 *
 * Two of KeY's states are drawn as a dark glyph, which this plugin inverts under a dark
 * theme. The icons a view is holding were made for the theme that was current when they
 * were fetched, so a switch has to reach the views.
 */
class RedrawOnThemeChange(private val project: Project) : LafManagerListener {

    override fun lookAndFeelChanged(manager: LafManager) {
        KeyProject.of(project).forget()
    }
}
