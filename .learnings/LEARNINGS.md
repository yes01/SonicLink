# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260825-001] best_practice

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
Use default resources for compact Android screens and `sw<N>dp` qualifiers for progressively larger layouts.

### Details
`w320dp` means an available width of at least 320dp, so it also matches normal phones and larger devices. It cannot express a maximum compact width. A robust ladder is default compact values, `sw360dp` phone values, and `sw600dp` tablet values, with combined qualifiers such as `sw360dp-land` when orientation-specific overrides are needed.

### Suggested Action
Model Android breakpoints as minimum-size enhancements and avoid treating `w<N>dp` as a max-width media query.

### Metadata
- Source: error
- Related Files: app/src/main/res/values/dimens.xml
- Tags: android, resources, responsive-layout

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Replaced the initial width qualifier with default, `sw360dp`, `sw360dp-land`, `sw600dp`, and `sw840dp` resources.

---
