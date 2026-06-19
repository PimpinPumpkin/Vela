package app.vela.core.diag

/**
 * One diagnostic breadcrumb in the opt-in debug log ([DiagLog]).
 *
 * Deliberately flat + string-only so it serializes trivially and a human can read
 * the exported bundle. [kind] is a short tag ("search", "directions", "drift",
 * "nav", "error"); [summary] is a one-line human description; [detail] is optional
 * extra context (a request path, a route summary, an exception message) — kept
 * small and free of anything we wouldn't want in a shared bug report.
 */
data class DiagEvent(
    val epochMs: Long,
    val kind: String,
    val summary: String,
    val detail: String? = null,
)
