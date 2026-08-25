# Errors

Command failures and integration errors.

---

## [ERR-20260825-005] windows-rg-glob-expansion

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary
PowerShell did not expand a Unix-style wildcard embedded in an `rg` path argument.

### Error
```text
IO error: filename, directory name, or volume label syntax is incorrect
```

### Context
- The path argument used `app/src/main/res/values*/dimens.xml`.

### Suggested Fix
Use `rg --files` or PowerShell file enumeration first, then pass explicit paths to `rg`.

### Metadata
- Reproducible: yes
- Related Files: none

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Replaced the wildcard path with explicit file enumeration.

---

## [ERR-20260825-004] android-layout-listener-view-type

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
Android layout-change callbacks expose the resized widget as `View`, not its concrete receiver type.

### Error
```text
Type mismatch: inferred type is View but RecyclerView was expected
```

### Context
- An adaptive grid helper expected a `RecyclerView`.
- The listener was registered on a RecyclerView, but its callback contract still uses `View`.

### Suggested Fix
Capture the strongly typed RecyclerView outside the callback or cast only after a safe type check.

### Metadata
- Reproducible: yes
- Related Files: app/src/main/java/org/cloud/sonic/android/ui/images/ImagesFragment.kt

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Reused the captured RecyclerView reference in both media fragments.

---

## [ERR-20260825-001] powershell-rg-quoting

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary
PowerShell parsed a regex pipe from a double-quoted `rg` pattern as a shell pipeline.

### Error
```text
The term 'cornerRadius=' is not recognized as a name of a cmdlet.
```

### Context
- Attempted a multi-alternative `rg` pattern inside a PowerShell double-quoted argument.
- The inspection was read-only and no project source was affected.

### Suggested Fix
Use a single-quoted regex argument or pass arguments as an array when the pattern contains `|`.

### Metadata
- Reproducible: yes
- Related Files: none

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Re-ran the search with a single-quoted regex argument.

---

## [ERR-20260825-002] powershell-variable-boundary

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: config

### Summary
PowerShell treated a colon immediately after a variable name as part of the variable reference.

### Error
```text
Variable reference is not valid. ':' was not followed by a valid variable name character.
```

### Context
- A line-number formatting expression used `$i:` inside a double-quoted string.

### Suggested Fix
Use `${i}:` whenever punctuation immediately follows a PowerShell variable.

### Metadata
- Reproducible: yes
- Related Files: none

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Re-ran the command with an explicit variable boundary.

---

## [ERR-20260825-003] android-material-attribute-compatibility

**Logged**: 2026-08-25T00:00:00+08:00
**Priority**: low
**Status**: resolved
**Area**: frontend

### Summary
The installed Material Components version did not expose direct bottom-navigation active-indicator attributes.

### Error
```text
attribute itemActiveIndicatorEnabled not found
attribute itemActiveIndicatorColor not found
```

### Context
- Android resource linking failed during `assembleDebug`.
- Selected icon and text colors were already supplied by a state-list resource.

### Suggested Fix
Use attributes available in the pinned Material version or rely on the Material 3 theme indicator.

### Metadata
- Reproducible: yes
- Related Files: app/src/main/res/layout/activity_main.xml

### Resolution
- **Resolved**: 2026-08-25T00:00:00+08:00
- **Notes**: Removed the unsupported attributes and kept the checked-state color selector.

---
