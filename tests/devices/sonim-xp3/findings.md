# Sonim XP3 (XP3800) - findings

- **Screen:** 2.6" primary internal screen, 240x320 PORTRAIT, ~154 dpi. Rugged flip phone.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: FULLY COVERED (20/20) at SIMULATED 240x320 @ 160dpi - own full run, not real hardware

`bash tests/devices/full_coverage.sh sonim-xp3` (mock GPS fix over Philadelphia) reports
**20 COVERED, 0 MISSED / RESULT: FULLY COVERED**. This profile now runs as its OWN row in the gate
with its own frames in [`screenshots/full/`](screenshots/full/) (01 welcome .. 20 parked-car sheet),
each checked by eye - it no longer piggybacks on the Kyocera e4810 record, even though the emulated
geometry is identical. Layout analysis and fix history for this geometry live in
[kyocera-e4810/findings.md](../kyocera-e4810/findings.md). If a real unit ever behaves differently
(panel quirk), capture it here.
