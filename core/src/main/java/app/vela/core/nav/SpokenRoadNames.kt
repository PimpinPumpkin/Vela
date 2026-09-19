package app.vela.core.nav

/**
 * Whether spoken guidance says the name of the road you are turning onto (issue #596).
 *
 * ON is the default and is what Vela has always done. Off, the voice says "Turn left" where it
 * would have said "Turn left onto Maple Street"; Google and most other navigators offer the same
 * switch, and it is the difference between guidance that fades into the background and guidance
 * that reads a street directory at you.
 *
 * NOTHING ON SCREEN CHANGES. The banner, the step list and the road pill under the puck all keep
 * the name, because the reason to drop it is that hearing it is noisy, not that knowing it is
 * unwanted. That is also why this is not a strip of the spoken string: the nameless form is built
 * by the same per-language template as the full one, with the road left out, so the word order
 * stays right in every language rather than losing a tail that is not always at the end.
 *
 * Same `:core` flag seam as [app.vela.core.data.LowRamMode]: the preference lives in `:app`
 * (`app.vela.ui.SpokenRoadNames`), which pushes the value down here, because `:core` is
 * UI-agnostic and never reads an app holder.
 */
object SpokenRoadNames {

    /** Set from the app at startup and whenever the Settings switch moves. */
    @Volatile var enabled: Boolean = true
}
