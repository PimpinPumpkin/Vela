package app.vela.core.data.google

/**
 * Debug hook for the review feed (`qv9Egd`, 2026-09-25): when set, every raw feed reply is handed
 * to [sink] before it is parsed. The app sets it only when `debug.vela.tune.feedDump` is 1 (an
 * adb-only property), to study why the feed answers nothing on a full Google session while the
 * limited view answers five. Null, and never set, for everyone else.
 */
object ReviewFeedDebug {
    @Volatile var sink: ((String) -> Unit)? = null
}
