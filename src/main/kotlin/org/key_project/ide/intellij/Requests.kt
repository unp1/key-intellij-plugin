package org.key_project.ide.intellij

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * How long to wait for the bridge, by what is being waited for.
 *
 * Named in one place because the deadline belongs to the kind of request, not to the view
 * that happens to make it. Listing a context took anything from thirty seconds to an hour
 * depending on who asked, which is a difference nobody chose.
 */
object Deadline {

    /** Reading or writing settings, which the configuration bridge answers without KeY. */
    val CONFIG: Duration = Duration.ofSeconds(30)

    /**
     * Anything that may load a context into KeY, which reads the sources, the taclets and
     * the specifications. Minutes on a large project, and once per context.
     */
    val CONTEXT: Duration = Duration.ofMinutes(10)

    /** A proof run, which takes as long as the proofs take. */
    val PROOF: Duration = Duration.ofHours(12)
}

/**
 * Waits for a request to the bridge.
 *
 * @param deadline how long to wait
 * @return what the bridge answered
 * @throws java.util.concurrent.TimeoutException if the deadline passes first
 */
fun <T> CompletableFuture<T>.await(deadline: Duration): T =
    get(deadline.toMillis(), TimeUnit.MILLISECONDS)


