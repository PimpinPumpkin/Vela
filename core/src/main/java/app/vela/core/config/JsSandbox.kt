package app.vela.core.config

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

/**
 * Runs one function out of a JavaScript [source] in a locked-down Rhino sandbox and
 * returns its String result (or null on anything going wrong). Pure (no Android / DI),
 * so it's unit-testable on the JVM.
 *
 *  - `initSafeStandardObjects` exposes **no** Java (`Packages`/reflection/IO) — the
 *    script can only read and return the string it's handed.
 *  - `optimizationLevel = -1` (interpreted) — Rhino's bytecode generator doesn't run on
 *    Android/ART, so we never emit classes.
 *  - `synchronized` — Rhino contexts aren't thread-safe; serialize all use.
 *  - Any exception (parse error, missing function, wrong return type) → null, so the
 *    caller falls back to compiled Kotlin.
 */
object JsSandbox {
    fun run(source: String, fn: String, arg: String): String? = synchronized(this) {
        runCatching {
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, source, "transforms.js", 1, null)
                val f = ScriptableObject.getProperty(scope, fn) as? Function ?: return@runCatching null
                val result = f.call(cx, scope, scope, arrayOf<Any>(arg))
                (Context.jsToJava(result, String::class.java) as? String)?.takeIf { it.isNotBlank() }
            } finally {
                Context.exit()
            }
        }.getOrNull()
    }
}
