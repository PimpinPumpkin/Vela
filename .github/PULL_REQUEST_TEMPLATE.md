**What this changes and why**


**Checklist** (see CONTRIBUTING.md)
- [ ] Docs updated in this same PR where behaviour changed (README / FEATURES / SPEC / CLAUDE), or the description says why none were needed
- [ ] New user-facing strings added to all 11 locales with matching placeholder types
- [ ] No GMS, no static Google keys, no backend calls introduced
- [ ] `:core` stays free of Android UI / MapLibre types
- [ ] Tested on a release build if this touches UI, map or navigation (say which device)
- [ ] `./gradlew :core:test` passes
- [ ] Commit subjects read as changelog lines (they become the release notes)
