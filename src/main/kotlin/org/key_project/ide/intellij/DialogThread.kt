package org.key_project.ide.intellij

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs on the event thread, including while a modal dialog is open.
 *
 * The event thread runs what is queued for it at the modality that is current, and work
 * started in the background is queued at none. A settings page is inside a modal dialog, so
 * its own code to fill itself in would wait until the dialog was closed: the page sat on
 * "Reading…" for as long as the user looked at it, and finished the moment they gave up.
 *
 * What runs here only reads what the bridge answered and puts it on screen. That is what
 * makes this modality safe to ask for: it touches Swing and nothing the dialog might be in
 * the middle of changing.
 *
 * @param block what to do on the event thread
 * @return what the block returned
 */
suspend fun <T> onDialogThread(block: CoroutineScope.() -> T): T =
    withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }
