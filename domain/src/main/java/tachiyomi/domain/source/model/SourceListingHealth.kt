package tachiyomi.domain.source.model

// AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED) -->
/**
 * How a listing's ordering has behaved when coverage was checked against it.
 *
 * See source_listing_health.sq for why this exists at all. The policy lives
 * here rather than in the paging source so that the rule and the numbers behind
 * it sit together.
 */
data class SourceListingHealth(
    val sourceId: Long,
    val listingKey: String,
    val failures: Int,
    val updatedAt: Long,
) {

    /**
     * Whether coverage should be neither read nor written for this listing now.
     *
     * Reading and writing are suppressed together on purpose: a listing that
     * cannot be skipped gains nothing from being recorded, and the records are
     * the cost. Suppression lifts once [RETRY_AFTER_MS] has passed since the
     * last failure, because a source that reorders under load may not reorder
     * at all when it is quiet, and a listing written off forever can never come
     * back.
     */
    fun isSuppressed(now: Long): Boolean =
        failures >= MAX_FAILURES && now - updatedAt < RETRY_AFTER_MS

    companion object {
        /**
         * Shifts tolerated before a listing stops being recorded.
         *
         * Three rather than one: a single shift is ordinary - something was
         * published between two walks - and treating that as proof of
         * instability would write off every recency-ordered listing on its
         * first genuinely new entry, which is most of them.
         *
         * Three DETECTIONS, which takes about six walks rather than three. A
         * walk that detects a shift stops recording, so the walk after it has
         * nothing to verify against and can neither fail nor pass; a listing
         * that reorders constantly therefore alternates learn, detect, learn,
         * detect. That is slower than it looks on paper and deliberately so -
         * the cost of being wrong here is suppressing a listing that would have
         * been skippable, while the cost of waiting is a few walks of recording
         * nobody reads.
         */
        const val MAX_FAILURES = 3

        /** A week, after which a suppressed listing is given another walk. */
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
// <-- AM (A_AN_UNSTABLE_LISTING_STOPS_BEING_TRACKED)
