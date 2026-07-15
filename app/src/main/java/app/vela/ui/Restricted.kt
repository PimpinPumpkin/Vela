package app.vela.ui

/**
 * True in the `restricted` build FLAVOR (see app/build.gradle.kts `productFlavors`), false in
 * `standard`. When true, the five self-restriction toggles are HARD-LOCKED at their restrictive
 * values and their rows disappear from Settings - the user cannot flip them:
 *
 *  - [ShowReviews]        locked OFF  (no reviews shown or fetched)
 *  - [LiveReviews]        locked OFF  (no "Read all reviews" Google page)
 *  - [LoadPhotos]         locked OFF  (no place photos shown or fetched)
 *  - [HideAdult]          locked ON   (adult/nightlife categories filtered at the :core seam)
 *  - [HideExternalLinks]  locked ON   (no Website / Street View / Book-Reserve-Order links)
 *
 * Voice search (the mic + Vela Voice) stays FULLY AVAILABLE in the restricted flavor - it is not a
 * content restriction, it is how a keypad user types.
 *
 * The lock lives in each holder's `init`/`set` (init forces the value and never reads the pref;
 * set() is a no-op) so ANY caller is bound, not just the Settings UI. A compile-time constant, so
 * R8 strips the dead branches from each flavor.
 */
const val RESTRICTED_BUILD: Boolean = app.vela.BuildConfig.RESTRICTED
