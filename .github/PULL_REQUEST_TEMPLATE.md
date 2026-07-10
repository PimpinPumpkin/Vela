**What this changes and why**


**Checklist** (see docs/CONTRIBUTING.md)
- [ ] Docs updated in this same PR where behaviour changed (README / FEATURES / SPEC / AGENTS), or the description says why none were needed
- [ ] New user-facing strings added to all 11 locales with matching placeholder types
- [ ] No GMS, no static Google keys, no backend calls introduced
- [ ] `:core` stays free of Android UI / MapLibre types
- [ ] If this touches UI/map/nav: verified **VISUALLY** with on-device screenshots (not just a passing script) at BOTH native and a small feature-phone display; say which device
- [ ] If this touches UI: `tests/dpad/audit_static.sh` clean and the on-device D-pad + small-screen auditors pass (every screen opens focused and stays D-pad-navigable)
- [ ] `./gradlew :core:test` passes
- [ ] Commit subjects read as changelog lines (they become the release notes)
