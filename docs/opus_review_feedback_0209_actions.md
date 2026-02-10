# Opus Review Feedback Actions

**Date:** 2026-02-10  
**Source review:** `docs/opus_review_feedback_0209.md`  
**Scope:** `plugins/lance-vector`, `plugins/security-realm-cloud-iam`

## Verification Summary

This file logs triage results after checking the review items against current code.

- `Confirmed`: issue is present and should be fixed.
- `Confirmed (adjusted)`: issue is real, but severity/scope in the review was overstated.
- `Defer`: valid hardening/refactor item, but not first merge blocker.

## Item-by-Item Triage

| ID | Review topic | Triage | Action |
|---|---|---|---|
| 1 | Arrow version conflict | Confirmed (adjusted) | Align dependency strategy; note plugin classloader reduces some flat-classpath assumptions. |
| 2 | Jackson version conflict | Confirmed | Switch to `${versions.jackson}` in plugin build. |
| 3 | `FakeLanceDataset` reachable in production path | Confirmed | Remove fallback; fail fast on unsupported/non-Lance URIs in production path. |
| 4 | Missing distance column returns meaningless scores | Confirmed | Throw hard failure when distance column is absent. |
| 5 | Pre-filter method returns unfiltered results | Confirmed (adjusted) | Enforce contract (throw/fail) or implement proper pushdown; keep post-filter path explicit. |
| 6 | SQL injection via unvalidated column names | Confirmed (adjusted) | Validate mapped column identifiers before SQL generation. |
| 7 | `String.intern()` lock + spinwait anti-pattern | Confirmed | Replace with per-URI `CompletableFuture` loading coordination. |
| 8 | Double-close in `invalidate()` | Confirmed | Ensure close happens in one place only (usually removal listener). |
| 9 | `System.err.println()` in security realm | Confirmed | Replace with structured logger usage. |
| 10 | `listObjects()` hardcoded fake data | Confirmed (adjusted) | Implement or throw `UnsupportedOperationException`; currently appears not in active production path. |
| 11 | `checkHasIndex()` swallows broad exceptions | Confirmed | Narrow catches and improve visibility (warn/info). |
| 12 | InputStream leak in `loadCredentials()` | Confirmed | Use try-with-resources for stream lifecycle. |
| 13 | No timeout around native search operation | Defer | Add bounded execution/timeout strategy after blocker fixes. |
| 14 | Refresh cycle drops stack trace | Confirmed | Log full exception (`logger.warn(..., e)`). |
| 15 | `setEnvIfChanged()` broad catch hides root cause | Defer | Improve exception handling and messaging. |
| 16 | `VarCharVector` leak on exception path | Defer | Add guarded allocation/close or try/finally around vector creation paths. |
| 17 | `VersionedDataset` non-atomic version+delegate update | Confirmed | Atomically swap dataset+version snapshot. |
| 18 | Metrics skipped on early return paths | Confirmed | Record metrics on all return paths. |
| 19 | `isNumericValue()` allows NaN/Infinity | Confirmed | Reject non-finite numeric values before SQL emission. |
| 20 | `OAuthTokenValidator` catches broad exception | Confirmed | Narrow catches to parse/network cases and preserve failure semantics. |
| 21 | Shard routing docs mention `Math.abs()` | Confirmed | Update docs/comments to match `Math.floorMod()` implementation. |
| 22 | Mutable map exposure (`fieldMapping`, `signedParams`) | Confirmed | Return immutable copies/views. |

## Priority Execution Order

1. **Merge blockers:** `#2 #3 #4 #7 #8`
2. **Security/correctness next:** `#6 #9 #11 #12 #18 #19 #20`
3. **Quality/doc consistency:** `#14 #17 #21 #22`
4. **Deferred hardening pass:** `#1 #5 #10 #13 #15 #16`

## Notes

- Dependency mismatch exists and should be resolved, but review wording around universal flat-classpath runtime failure was stronger than what current plugin classloading implies.
- Tests were **not** executed as part of this triage log; this file captures verification-by-inspection and planned actions.
