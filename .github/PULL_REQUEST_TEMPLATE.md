**What this changes and why**


**Checklist** (see CONTRIBUTING.md)
- [ ] Every real place in the diff (coordinates, addresses, business names, screenshots, commit messages) is there on purpose, not because it is near me; fixtures use the Davis / Sacramento box (CLAUDE.md, "Location hygiene")
- [ ] Docs updated in this same PR where behavior changed (SPEC, the book chapter, FEATURES), or the description says why none were needed
- [ ] New strings a user sees are in the English `values/strings.xml` with the right placeholder types
- [ ] No Google libraries, no static Google keys, no backend calls
- [ ] `:core` stays free of Android UI and MapLibre types
- [ ] Tested on a release build if this touches the interface, the map or navigation (say which device)
- [ ] `./gradlew :core:test` passes
- [ ] Commit subjects read as changelog lines (they become the release notes)
