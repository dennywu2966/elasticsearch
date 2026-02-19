# Task Plan: [Brief Description]
<!-- 
  WHAT: This is your roadmap for the entire task. Think of it as your "working memory on disk."
  WHY: After 50+ tool calls, your original goals can get forgotten. This file keeps them fresh.
  WHEN: Create this FIRST, before starting any work. Update after each phase completes.
-->

## Goal
<!-- 
  WHAT: One clear sentence describing what you're trying to achieve.
  WHY: This is your north star. Re-reading this keeps you focused on the end state.
  EXAMPLE: "Create a Python CLI todo app with add, list, and delete functionality."
-->
总结仓库内与 Lance 多分片、NRT 刷新、prefilter/filter pushdown、数据流架构、Cloud IAM 相关的可证实材料并输出可用于技术分享的中文要点与 ASCII 架构图草稿。

## Current Phase
<!-- 
  WHAT: Which phase you're currently working on (e.g., "Phase 1", "Phase 3").
  WHY: Quick reference for where you are in the task. Update this as you progress.
-->
Phase 3

## Phases
<!-- 
  WHAT: Break your task into 3-7 logical phases. Each phase should be completable.
  WHY: Breaking work into phases prevents overwhelm and makes progress visible.
  WHEN: Update status after completing each phase: pending → in_progress → complete
-->

### Phase 1: Requirements & Discovery
<!-- 
  WHAT: Understand what needs to be done and gather initial information.
  WHY: Starting without understanding leads to wasted effort. This phase prevents that.
-->
- [x] Understand user intent
- [x] Identify constraints and requirements
- [x] Document findings in findings.md
- **Status:** complete
<!-- 
  STATUS VALUES:
  - pending: Not started yet
  - in_progress: Currently working on this
  - complete: Finished this phase
-->

### Phase 2: Planning & Structure
<!-- 
  WHAT: Decide how you'll approach the problem and what structure you'll use.
  WHY: Good planning prevents rework. Document decisions so you remember why you chose them.
-->
- [x] Define technical approach
- [x] Create project structure if needed
- [x] Document decisions with rationale
- **Status:** complete

### Phase 3: Implementation
<!-- 
  WHAT: Actually build/create/write the solution.
  WHY: This is where the work happens. Break into smaller sub-tasks if needed.
-->
- [ ] Execute the plan step by step
- [ ] Write code to files before executing
- [ ] Test incrementally
- **Status:** in_progress

### Phase 4: Testing & Verification
<!-- 
  WHAT: Verify everything works and meets requirements.
  WHY: Catching issues early saves time. Document test results in progress.md.
-->
- [ ] Verify all requirements met
- [ ] Document test results in progress.md
- [ ] Fix any issues found
- **Status:** pending

### Phase 5: Delivery
<!-- 
  WHAT: Final review and handoff to user.
  WHY: Ensures nothing is forgotten and deliverables are complete.
-->
- [ ] Review all output files
- [ ] Ensure deliverables are complete
- [ ] Deliver to user
- **Status:** pending

## Key Questions
<!-- 
  WHAT: Important questions you need to answer during the task.
  WHY: These guide your research and decision-making. Answer them as you go.
  EXAMPLE: 
    1. Should tasks persist between sessions? (Yes - need file storage)
    2. What format for storing tasks? (JSON file)
-->
1. Lance 多分片支持的关键代码证据和测试覆盖在哪些文件？
2. NRT 刷新、prefilter/filter pushdown、Cloud IAM 的可证实事实与推断边界是什么？

## Decisions Made
<!-- 
  WHAT: Technical and design decisions you've made, with the reasoning behind them.
  WHY: You'll forget why you made choices. This table helps you remember and justify decisions.
  WHEN: Update whenever you make a significant choice (technology, approach, structure).
  EXAMPLE:
    | Use JSON for storage | Simple, human-readable, built-in Python support |
-->
| Decision | Rationale |
|----------|-----------|
|          |           |

## Errors Encountered
<!-- 
  WHAT: Every error you encounter, what attempt number it was, and how you resolved it.
  WHY: Logging errors prevents repeating the same mistakes. This is critical for learning.
  WHEN: Add immediately when an error occurs, even if you fix it quickly.
  EXAMPLE:
    | FileNotFoundError | 1 | Check if file exists, create empty list if not |
    | JSONDecodeError | 2 | Handle empty file case explicitly |
-->
| Error | Attempt | Resolution |
|-------|---------|------------|
| session-catchup.py 路径不存在 | 1 | 改用技能目录 /home/denny/.agents/skills/planning-with-files/ |

## Notes
<!-- 
  REMINDERS:
  - Update phase status as you progress: pending → in_progress → complete
  - Re-read this plan before major decisions (attention manipulation)
  - Log ALL errors - they help avoid repetition
  - Never repeat a failed action - mutate your approach instead
-->
- Update phase status as you progress: pending → in_progress → complete
- Re-read this plan before major decisions (attention manipulation)
- Log ALL errors - they help avoid repetition

---

## Session 2026-02-10: Opus Review Action Implementation

### Goal
Implement all items from `docs/opus_review_feedback_0209_actions.md` in severity order with TDD, and verify no regressions in both affected plugins.

### Current Phase
Phase E (Verification) - complete

### Phases
- Phase A (Blockers): `#2 #3 #4 #7 #8` ✅
- Phase B (Security/Correctness): `#6 #9 #11 #12 #18 #19 #20` ✅
- Phase C (Quality/Consistency): `#14 #17 #21 #22` ✅
- Phase D (Deferred Hardening): `#1 #5 #10 #13 #15 #16` ✅
- Phase E (Verification): targeted + plugin-wide test runs ✅
- Phase F (reg_validation_guide verification): full real-suite rerun + javaRestTest unblock/fix ✅

### Execution Rules
- Add/adjust failing tests first for every behavior change (RED), then minimal implementation (GREEN), then refactor.
- No "fake tests" (no tests written only to satisfy assertions without behavior value).
- Do not claim completion without fresh verification output.

### Errors Encountered (Session 2026-02-10)
| Error | Attempt | Resolution |
|-------|---------|------------|
| Gradle sandbox wildcard IP startup issue | 1 | Re-ran verification commands with escalation. |
| `manage_threads` entitlement violation in initial timeout design | 1 | Replaced thread-based timeout with lock-timeout strategy in registry. |
| Full build checkstyle task reports existing style violations in broader plugin code | 1 | Kept focus on requested review-action implementation; verified via full plugin tests. |
| `:plugins:security-realm-cloud-iam:javaRestTest` blocked by dependency verification for `elasticsearch-distribution-snapshot` | 1 | Added trusted artifact entry in `gradle/verification-metadata.xml`. |
| `CloudIamRealmIT` auth bootstrap failed in javaRestTest (`_nodes/plugins` 401) | 1 | Added `restAdminSettings()` credentials using `test_user` for cluster bootstrap/cleanup. |
| `CloudIamRealmIT.testRejectsInvalidSignature` false pass due STS success cache keyed only by access key | 1 | Hardened STS cache key to include signature context; added regression unit test. |
