#!/usr/bin/env bash
# tests/dead_code/audit_deadcode.sh - EXHAUSTIVE cross-module dead-code auditor (host-side).
#
# This is the WHOLE-TREE half of Vela's dead-code gate. detekt (config/detekt/detekt.yml, run by
# `./gradlew detekt`) finds per-module dead code that needs a parser - unused imports, unused
# private members, unreachable code - the things grep gets WRONG (e.g. Compose `by remember`
# delegate imports look unused to grep but are not). This script finds what detekt CANNOT: a
# public/internal top-level declaration that the ENTIRE source tree (every module, .kt + .xml +
# .kts) never references, and whole modules that nothing depends on. Together they are the
# comprehensive finder.
#
# ACCURACY IS THE CONTRACT: it must never flag something that is actually needed. So it:
#   - counts references across ALL modules, not one, and treats a name used ANYWHERE (even only
#     inside its own file) as live;
#   - skips every reflection/DI/framework ENTRY POINT (a class Google/Hilt/Compose/kotlinx.
#     serialization/the manifest reaches by name, which no Kotlin call site mentions): anything
#     annotated @Composable/@Preview/@Inject/@Provides/@Binds/@HiltViewModel/@AndroidEntryPoint/
#     @Module/@Serializable/@Test/@Keep/@JvmStatic, the R8 `-keep`-by-name model package, and the
#     four AndroidManifest entry classes;
#   - errs toward SILENCE (a false negative is acceptable; a false positive is a bug).
#
#   ./audit_deadcode.sh        # scan; print VIOLATIONS (fail) + CHECK notes (triage); exit 1 on any violation
#   ./audit_deadcode.sh -v     # also print the live-reference count for every declaration
# Mirrors tests/dpad/audit_static.sh in shape; wire it into CI next to that one.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERBOSE="${1:-}"

python3 - "$ROOT" "$VERBOSE" <<'PY'
import os, re, sys

root, verbose = sys.argv[1], (len(sys.argv) > 2 and sys.argv[2] == "-v")
viol, check = [], []

# Modules whose MAIN source declares the app's shipped symbols. tools/ghprobe are off-device.
MAIN_SRC = ["app/src/main", "core/src/main"]

def is_build(p):
    return "/build/" in p or p.endswith("~")

def collect(exts, roots=None):
    out = []
    walk_roots = [os.path.join(root, r) for r in (roots or ["app", "core", "tools"])]
    for wr in walk_roots:
        for dp, _, fs in os.walk(wr):
            if is_build(dp):
                continue
            for f in sorted(fs):
                if f.endswith(exts):
                    out.append(os.path.join(dp, f))
    return out

# Every file that could REFERENCE a symbol: all Kotlin + all XML + all Gradle scripts, all modules.
ref_paths = collect((".kt", ".kts", ".xml"))
ref_files = {}
for p in ref_paths:
    try:
        ref_files[p] = open(p, encoding="utf-8", errors="ignore").read().splitlines()
    except OSError:
        pass

# Manifest entry classes (fully-qualified or .Relative) are framework entry points.
manifest_names = set()
for p in ref_paths:
    if p.endswith("AndroidManifest.xml"):
        for m in re.findall(r'android:name="\.?([A-Za-z0-9_.]+)"', "\n".join(ref_files[p])):
            manifest_names.add(m.split(".")[-1])

ENTRY_ANNO = re.compile(
    r'@(Composable|Preview|Inject|Provides|Binds|HiltViewModel|AndroidEntryPoint|Module|'
    r'Serializable|SerialName|Test|Before|After|Keep|JvmStatic|HiltAndroidApp)\b')

# A top-level declaration: class / object / interface / enum class / annotation class / fun /
# typealias at column 0. Capture visibility so we can skip `private` (detekt owns file-local dead
# code) and the name.
DECL = re.compile(
    r'^(?P<mods>(?:public |internal |open |abstract |sealed |data |final |value |annotation |inline |'
    r'suspend |external |tailrec |operator |infix )*)'
    r'(?P<kind>class|object|interface|enum class|fun|typealias)\s+'
    r'(?P<name>[A-Za-z_][A-Za-z0-9_]*)')

def kept_model_pkg(path):
    # R8 `-keep`/`-keepnames class app.vela.core.model.**` - reached reflectively, never dead.
    return "/core/" in path and "/model/" in path

def entry_annotated(lines, idx):
    # Scan the annotation/modifier block immediately above the declaration.
    j = idx - 1
    while j >= 0:
        s = lines[j].strip()
        if s == "" or s.startswith("*") or s.startswith("/*") or s.startswith("*/") or s.startswith("//"):
            j -= 1
            continue
        if s.startswith("@"):
            if ENTRY_ANNO.search(s):
                return True
            j -= 1
            continue
        break
    return False

# Enumerate candidate declarations from the shipped main source only.
decls = []
for p in ref_paths:
    if not p.endswith(".kt"):
        continue
    if not any(("/" + m.replace("/", "/")) in p or (m in p) for m in MAIN_SRC):
        continue
    if "/test/" in p or "/androidTest/" in p:
        continue
    lines = ref_files[p]
    for i, ln in enumerate(lines):
        m = DECL.match(ln)
        if not m:
            continue
        mods, name = m.group("mods"), m.group("name")
        if "private " in mods:
            continue                      # file-local: detekt's job, not cross-module
        if len(name) < 4:
            continue                      # too short: real symbols collide with common tokens
        if name in manifest_names or kept_model_pkg(p):
            continue
        if entry_annotated(lines, i):
            continue
        decls.append((p, i, name))

word = {}  # cache compiled per-name regexes
def wre(n):
    if n not in word:
        word[n] = re.compile(r'\b' + re.escape(n) + r'\b')
    return word[n]

def live_refs(declpath, declline, name):
    """Count references to `name` anywhere in the tree EXCLUDING the declaration line itself and
    any `import` line. A hit inside the declaring file still counts as live (used, not dead)."""
    rx, n = wre(name), 0
    for p, lines in ref_files.items():
        for i, ln in enumerate(lines):
            if p == declpath and i == declline:
                continue
            st = ln.lstrip()
            if st.startswith("import "):
                continue
            if rx.search(ln):
                n += 1
                if not verbose:
                    return n     # one live ref is enough to clear it
    return n

for p, i, name in sorted(decls):
    n = live_refs(p, i, name)
    rel = os.path.relpath(p, root)
    if n == 0:
        viol.append(f"ORPHAN  {name}  ({rel}:{i+1}) - declared but referenced nowhere in the tree")
    elif verbose:
        print(f"OK  {name}  ({rel}:{i+1}) - {n}+ live refs")

# --- whole-module deadness (advisory) --------------------------------------------------------
# A module referenced only by its own build script + settings + comments is dead weight. Add any
# suspect module id to MODULES below; a live one is skipped, a dead one is surfaced for triage (a
# module deletion is a human decision). Empty now that the throwaway :ghprobe probe was retired.
def module_live(modname):
    rx = re.compile(r'\b' + re.escape(modname) + r'\b')
    for p, lines in ref_files.items():
        if ("/" + modname + "/") in p.replace(root, "") or p.endswith("settings.gradle.kts"):
            continue
        for ln in lines:
            st = ln.lstrip()
            if st.startswith("//") or st.startswith("*"):
                continue
            if rx.search(ln):
                return p
    return None

for mod in ():   # no known throwaway modules; :ghprobe was retired
    if os.path.isdir(os.path.join(root, mod)) and module_live(mod) is None:
        check.append(f"DEAD MODULE  :{mod}  - nothing outside its own build/settings depends on it "
                     f"(consider removing from settings.gradle.kts)")

if viol:
    print("DEAD CODE VIOLATIONS (fail):")
    for v in viol:
        print("  " + v)
if check:
    print("\nCHECK (triage, not a failure):")
    for c in check:
        print("  " + c)
if not viol and not check:
    print("PASS: no orphan declarations, no dead modules.")
elif not viol:
    print("\nPASS: no orphan declarations (see CHECK notes above).")

sys.exit(1 if viol else 0)
PY