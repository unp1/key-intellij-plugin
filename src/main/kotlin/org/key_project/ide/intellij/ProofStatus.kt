package org.key_project.ide.intellij

/**
 * The proof states the bridge reports, and what a gutter mark makes of them.
 *
 * The names are the bridge's. Comparing against them belongs here rather than in every view
 * that shows a state, so that a state the bridge adds is dealt with in one place.
 */
object ProofStatus {

    /** No proof is saved. */
    const val NONE = "NONE"

    /** A proof is saved but has not been replayed, so KeY has not judged it. */
    const val SAVED = "SAVED"

    /** The proof has goals left. */
    const val OPEN = "OPEN"

    /** The proof is closed, but rests on contracts whose own proofs are not. */
    const val CLOSED_BUT_LEMMAS_LEFT = "CLOSED_BUT_LEMMAS_LEFT"

    /** The proof is closed, using steps taken from the proof cache. */
    const val CLOSED_BY_CACHE = "CLOSED_BY_CACHE"

    /** The proof is closed. */
    const val CLOSED = "CLOSED"

    /** KeY reports a state the bridge does not classify. */
    const val UNKNOWN = "UNKNOWN"

    /**
     * Whether KeY has closed the proof.
     *
     * @param status the state as the bridge reports it
     */
    fun isClosed(status: String): Boolean = status == CLOSED || status == CLOSED_BY_CACHE

}

/**
 * How far the proofs of one declaration have got, as a gutter mark shows it.
 *
 * Which of these a line carries is the bridge's answer, so that a mark means the same in
 * every editor. The names are the ones it reports.
 */
enum class ProofMark {

    /** Every obligation is closed. */
    CLOSED,

    /** Every obligation is closed, some of them resting on contracts that are not. */
    LEMMAS_LEFT,

    /** An obligation has goals left. */
    OPEN,

    /** KeY has not judged every obligation, so how far the declaration has got is unknown. */
    UNJUDGED,
}
