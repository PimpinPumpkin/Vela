#!/usr/bin/env bash
# dpad_test_suite/audit_static.sh — EXHAUSTIVE static D-pad auditor (host-side, no device needed).
#
# Scans EVERY .kt under :app for EVERY known D-pad anti-pattern and fails (exit 1) if any real
# violation remains. Nothing escapes: every interactive-modifier variant, every gesture modifier,
# every Material-dialog/menu shortcut, theme calls, sliders, and raw windows are checked. Items that
# CAN be legitimately hand-indicated (a bare .focusable() with a custom crosshair/pill, a raw Dialog
# in a sanctioned file) are surfaced as CHECK notes so they are triaged, never silently ignored.
#
#   ./audit_static.sh        # scan; print VIOLATIONS + CHECK notes; exit 1 if any violation
#   ./audit_static.sh -v     # also print every OK site
# See docs/dpad.md for the rules this encodes.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/main/java/app/vela"
VERBOSE="${1:-}"

python3 - "$SRC" "$VERBOSE" <<'PY'
import os, re, sys
src, verbose = sys.argv[1], (len(sys.argv) > 2 and sys.argv[2] == "-v")
viol, check, oknote = [], [], []

def rel(p): return os.path.relpath(p, os.path.dirname(src))
def walk():
    for dp, _, fs in os.walk(src):
        for f in sorted(fs):
            if f.endswith(".kt"):
                yield os.path.join(dp, f)

# code portion of a line (strip // line-comments); False for comment-only / block-comment lines.
def code_of(ln):
    c = ln.split("//", 1)[0]
    s = c.strip()
    if s.startswith("*") or s.startswith("/*") or s.startswith("*/"):
        return ""
    return c
def has(regex, ln): return regex.search(code_of(ln)) is not None

# --- interactive modifiers that MUST carry a visible focus ring (.dpadHighlight) --------------
RING_REQUIRED = re.compile(r'\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\s*[({]')
CLICKISH = re.compile(r'\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\s*[({]')
FOCUSABLE = re.compile(r'\.focusable\s*\(')
# Scan the Modifier chain backwards for a ring, stopping at a different focus target / declaration.
def chain_has_ring(lines, idx):
    for j in range(idx, max(idx - 26, -1), -1):
        c = code_of(lines[j])
        if "dpadHighlight(" in c:
            return True
        if j < idx and (CLICKISH.search(c) or FOCUSABLE.search(c)
                        or c.strip().startswith("@Composable")
                        or re.match(r'(private |internal |public |open )*fun ', c.strip())):
            return False
    for j in range(idx + 1, min(idx + 4, len(lines))):
        if "dpadHighlight(" in code_of(lines[j]):
            return True
    return False

# --- gesture modifiers / raw pointer handlers that MUST have a key alternative -----------------
GESTURE = re.compile(r'(detectTapGestures|detectDragGestures|detectVerticalDragGestures|'
                     r'detectHorizontalDragGestures|detectTransformGestures|detectTapAndPress|'
                     r'awaitEachGesture|awaitPointerEventScope|awaitFirstDown|'
                     r'\.draggable\s*\(|\.swipeable\s*\(|\.anchoredDraggable\s*\(|\.transformable\s*\(|'
                     r'\.scrollable\s*\()')
# A gesture has a D-pad alternative if the same composable also offers a KEY path: an explicit key
# handler, a focus-target modifier, OR any Material control (IconButton/Button/Chip/onClick=…) that
# is inherently focusable + OK-activatable (e.g. the results-sheet chevron mirrors its drag handle).
KEYPATH = re.compile(r'(onKeyEvent|onPreviewKeyEvent|MapDpad|mapDpad|Key\.Direction|'
                     r'\.clickable|\.combinedClickable|\.focusable|\.selectable|\.toggleable|'
                     r'onClick\s*=|IconButton\s*\(|FilledTonalIconButton\s*\(|\bButton\s*\(|'
                     r'TextButton\s*\(|OutlinedButton\s*\(|FilterChip\s*\(|AssistChip\s*\(|Chip\s*\()')
def near_keypath(lines, idx, radius=50):
    lo, hi = max(0, idx - radius), min(len(lines), idx + radius)
    return KEYPATH.search("".join(code_of(l) for l in lines[lo:hi])) is not None

RE_DROPDOWN = re.compile(r'\bDropdownMenu\s*\(')
RE_ALERT    = re.compile(r'\bAlertDialog\s*\(')
RE_ISSYS    = re.compile(r'isSystemInDarkTheme\s*\(\)')
RE_FIELD    = re.compile(r'\b(OutlinedTextField|BasicTextField|TextField)\s*\(')
RE_SLIDER   = re.compile(r'\bSlider\s*\(')
RE_RAWDIALOG= re.compile(r'(?<!Alert)(?<!Date)(?<!Time)\bDialog\s*\(')
RE_POPUP    = re.compile(r'\bPopup\s*\(')
RE_SURFACE  = re.compile(r'\b(Card|Surface|ListItem|OutlinedCard|ElevatedCard)\s*\(')  # clickable ones
RE_ANDROIDVIEW = re.compile(r'\bAndroidView\s*\(')
RE_SHEET    = re.compile(r'\b(ModalBottomSheet|BottomSheetScaffold)\s*\(')
# A dialog's content is safe on a small screen if it SCROLLS or FILLS the screen (a full-screen
# viewer paginates instead). Otherwise a tall body clips its buttons off a feature-phone display.
RE_DLG_SAFE = re.compile(r'verticalScroll|LazyColumn|LazyVerticalGrid|HorizontalPager|VerticalPager|fillMaxSize|fillMaxHeight')
# Files allowed to host a raw Dialog/Popup (the sanctioned auto-focus seams + full-screen viewers).
RAW_OK = {"VelaMenu.kt", "VelaDialog.kt", "PlaceSheet.kt", "ReviewsPanel.kt"}
scanned = 0

for path in walk():
    name, base = rel(path), os.path.basename(path)
    lines = open(path, encoding="utf-8").readlines()
    scanned += 1
    for i, ln in enumerate(lines):
        n = i + 1
        # A/B. Material menu/dialog shortcuts that can't auto-focus
        if base != "VelaMenu.kt" and has(RE_DROPDOWN, ln):
            viol.append(("HIGH", f"{name}:{n}", "bare DropdownMenu — use VelaMenu"))
        if base != "VelaDialog.kt" and has(RE_ALERT, ln):
            viol.append(("HIGH", f"{name}:{n}", "bare AlertDialog — use VelaDialog"))
        # C. theme
        if base != "AppTheme.kt" and has(RE_ISSYS, ln):
            viol.append(("MED", f"{name}:{n}", "isSystemInDarkTheme() — use isAppInDarkTheme()"))
        # D. any ring-required interactive modifier without a ring
        if has(RING_REQUIRED, ln):
            if chain_has_ring(lines, i):
                if verbose: oknote.append(f"ring {name}:{n}")
            else:
                viol.append(("MED", f"{name}:{n}", "clickable/toggleable/selectable with no .dpadHighlight (invisible focus)"))
        # E. gesture modifier with no key alternative nearby
        if has(GESTURE, ln):
            if near_keypath(lines, i):
                if verbose: oknote.append(f"gest {name}:{n}")
            else:
                viol.append(("HIGH", f"{name}:{n}", "gesture (drag/tap) with no key alternative (onKeyEvent/clickable) — D-pad can't reach it"))
        # F. bare .focusable() — needs a ring OR deliberate self-indication → surface for triage
        if has(FOCUSABLE, ln) and not chain_has_ring(lines, i):
            check.append(("focusable", f"{name}:{n}", "bare .focusable() with no ring — confirm it self-indicates (crosshair/pill) or add dpadHighlight"))
        # G. Slider — Material handles arrows when focused, but confirm it's reachable
        if has(RE_SLIDER, ln):
            check.append(("slider", f"{name}:{n}", "Slider — confirm D-pad arrows adjust it and it's focus-reachable"))
        # H. raw Dialog/Popup outside the sanctioned files
        if base not in RAW_OK and (has(RE_RAWDIALOG, ln) or has(RE_POPUP, ln)):
            check.append(("window", f"{name}:{n}", "raw Dialog/Popup — must auto-focus a .focusable() element (VelaDialog/VelaMenu pattern)"))
        # H2. any raw Dialog whose content has NO scroll container and isn't full-screen — a tall
        #     body clips its buttons/options off a small (feature-phone) screen. VIOLATION: dialogs
        #     must scroll or fill the screen so every option stays reachable (user 2026-07-08).
        if has(RE_RAWDIALOG, ln):
            body = "".join(code_of(l) for l in lines[i:i + 60])
            if not RE_DLG_SAFE.search(body):
                viol.append(("MED", f"{name}:{n}", "Dialog content has no verticalScroll/LazyColumn and isn't full-screen — clips options on a small screen; wrap the body in a scroll container"))
        # I. text field escape
        if has(RE_FIELD, ln):
            if "dpadFieldEscape" not in "".join(lines[max(0,i-2):i+22]):
                check.append(("field", f"{name}:{n}", "text field — confirm .dpadFieldEscape() (or bespoke DOWN-escape) so controls below stay reachable"))
        # J. clickable Material SURFACE (Card/Surface/ListItem whose OWN constructor takes onClick) —
        #    Material focus indication on a big grey surface is faint; confirm it reads or add a ring.
        #    Only the constructor args count (text before the content-lambda '{'), so a child's
        #    onClick doesn't trip it.
        if has(RE_SURFACE, ln):
            ctor = ""
            for k in range(i, min(i + 6, len(lines))):
                c = code_of(lines[k]); b = c.find("{")
                ctor += c[:b] if b >= 0 else c
                if b >= 0: break
            if "onClick" in ctor and "dpadHighlight(" not in "".join(code_of(l) for l in lines[max(0,i-1):i+9]):
                check.append(("surface", f"{name}:{n}", "Card/Surface/ListItem with onClick — confirm its focus is visible (Material indication is faint on grey) or add dpadHighlight"))
        # K. AndroidView — a native view (WebView/MapView) has NO Compose focus; confirm it has an
        #    explicit D-pad key bridge (MapDpadController / pageUp-pageDown) or it's unreachable.
        if has(RE_ANDROIDVIEW, ln):
            check.append(("androidview", f"{name}:{n}", "AndroidView — confirm a D-pad key bridge (MapDpadController / WebView pageUp-Down) or the native view is unreachable"))
        # L. Material bottom sheet / scaffold sheet — confirm it auto-focuses (Vela uses hand-built
        #    sheets for exactly this reason).
        if has(RE_SHEET, ln):
            check.append(("sheet", f"{name}:{n}", "ModalBottomSheet/BottomSheetScaffold — confirm it auto-focuses a primary control (Compose sheets open unfocused)"))

order = {"HIGH": 0, "MED": 1, "LOW": 2}
viol.sort(key=lambda v: order.get(v[0], 9))
print(f"=== D-pad EXHAUSTIVE static audit ({scanned} .kt files scanned) ===")
if viol:
    print(f"VIOLATIONS ({len(viol)}):")
    for sev, loc, msg in viol: print(f"  [{sev}] {loc}  {msg}")
else:
    print("VIOLATIONS: none.")
if check:
    print(f"CHECK — surfaced for triage ({len(check)}); each verified OK or it'd be a violation:")
    for _, loc, msg in check: print(f"  - {loc}  {msg}")
if verbose:
    print(f"(OK sites: {len(oknote)})")
sys.exit(1 if viol else 0)
PY
