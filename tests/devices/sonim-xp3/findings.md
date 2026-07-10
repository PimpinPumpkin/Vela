# Sonim XP3 (XP3800) - findings

- **Screen:** 2.6" primary internal screen, 240x320 PORTRAIT, ~154 dpi. Rugged flip phone.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: COVERED by the 240x320 verification

This is the EXACT same emulated geometry as the Kyocera e4810 (`wm size 240x320; wm density 160`), so
the Kyocera findings ARE this device's results - same wm config produces the same layout/focus, not an
assumption. See [kyocera-e4810/findings.md](../kyocera-e4810/findings.md): adaptive density fits all
chips, Settings opens focused + DOWN navigates, the full first-run flow fits and auto-focuses. If a real
unit ever behaves differently (panel quirk), capture it here.
