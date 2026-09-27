<!--
REVIEW STATUS: COMPLETED & VERIFIED
REVIEW DATE: 2026-09-27
TASK: AI Vision and OCR Phone Control Integration
DESTINATION: plan/completed/ai-vision-ocr-phone-control-fix-plan.md

RESOLVED REJECTION ITEMS (R1–R7):
- R1: Connected ModelRouter planning with prompt serialization of OCR text and block coordinates.
- R2: Hardened detail-page verification with structural indicators and product title identity token matching on fresh screen observation.
- R3: Prevented stale coordinates by binding candidates to observation frame and re-locating target freshly on current screen. Checked price uniqueness before fallback.
- R4: Enforced package allowlist, policy checks, elapsed-time deadline, and max action budget before actions.
- R5: Verified ScreenCaptureService readiness preflight and recycled image Bitmaps in finally blocks.
- R6: Added search bar OCR coordinate fallback, input focus verification, and search submit verification.
- R7: Expanded unit test suite; verified clean compile and test passes.
-->
The original plan below is preserved. Its historical gap descriptions and
unchecked tasks are not a substitute for this source-based review.

IMPLEMENTED PROGRESS OBSERVED
- ScreenTargetResolver and coordinate helper tests exist.
- ScreenObservationEngine supports forceOcr and exposes OCR blocks in observations.
- ShopeeShoppingSkill extracts OCR candidates and has a coordinate-tap fallback.
- Fabricated 4.8 ratings / 100-review defaults were removed; salesCount is separate.
- CommandExecutor routes shopping through AgentOrchestrator.executeShopping,
  providing shared run ownership and coroutine cancellation.
- ShopeeShoppingSkillTest covers extraction and ranking using synthetic OCR data.

REJECTION DETAILS AND REQUIRED FIXES

R1 — AI/VLM planning is not connected to shopping (Phase 2; target flow steps 3–4).
Evidence: app/src/main/java/com/dinh/javis/agent/AgentOrchestrator.kt:483–564
sets maxModelCalls = 0 and directly invokes skill.execute. The shopping skill has
no ModelRouter planning or vision calls. Adding ocrBlocks to ScreenObservation
alone is not end-to-end integration: OpenAiCompatibleClient's planning prompt
serializes OCR text but not those block coordinates.
[ ] Connect the configured planning/vision capabilities to the active shopping
    workflow, with bounded requests, explicit data-sharing consent, and validated
    structured proposals referencing current observation targets.
[ ] Send relevant OCR text/bounds and visual evidence when needed. Test that a
    representative shopping run actually calls the expected model capability;
    reject malformed responses and keep hard user constraints application-owned.

R2 — Final verification can still report false success (T3.4; DoD 4).
Evidence: ShopeeShoppingSkill.kt:198–224 accepts a title substring as a detail-page
indicator. That title can remain visible in search results after a failed tap.
Generic purchase labels also do not prove that the selected product was opened.
The summary uses the previously collected price rather than verifying it again.
[ ] Require both detail-page structure and matching product identity on a fresh
    observation. Recheck relevant seller/variant, price, and hard requirements.
[ ] Add negative tests: failed tap leaves results visible, wrong product detail,
    duplicate titles, changed price/variant, and an obstructing overlay. None may
    produce SUCCESS. Preserve the actual selected detail screen on success.

R3 — Coordinate targets become stale after scrolling (T1.1/T1.2/T3.3; DoD 2).
Evidence: ShopeeShoppingSkill.kt:109–141 collects candidates across pages, then
:174–194 can tap saved checkpointX/Y without relocating the selected product.
lastOcrBlocks belong only to the most recently observed page. The resolver is
constructed with default 1080x2340 dimensions, without current frame geometry;
its price fallback returns a match without checking uniqueness.
[ ] Bind candidate targets to frame/page/window identity. Return to and freshly
    locate the selected card before tapping; never reuse coordinates from an
    earlier scroll position or another window.
[ ] Supply measured capture/display geometry and transform metadata. Validate
    scaling, offsets, rotation, and supported capture modes; fail closed when
    geometry or identity is ambiguous. Do not use a nonunique price as identity.
[ ] Test multiple pages, duplicate prices/titles, different display sizes,
    rotations, and layout changes during inference.

R4 — The shopping wrapper does not enforce the shared safety loop (T3.5).
Evidence: executeShopping declares allowedPackages, maxSteps, and deadlineMs,
but its direct skill.execute call does not consume those runtime guards.
ShopeeShoppingSkill.kt:78–81 only logs a foreground-package mismatch and continues
clicking/typing. Direct skill actions do not pass through the generic runner's
per-observation/per-action policy checks. Callback notifications increment
verifiedActionCount before their effects have actually been verified.
[ ] Enforce application scope and policy before observation/upload and immediately
    before each action. Stop on app mismatch, lock, sensitive screens, or revocation.
[ ] Enforce elapsed-time and action/model budgets inside the active workflow.
    Count actual verified effects, not progress announcements.
[ ] Reject cart, checkout, payment, messaging, and account-changing targets,
    including coordinate taps. Test app switches, timeouts, cancellation, and
    forbidden-target proposals through the real shopping execution path.

R5 — Capture readiness and bitmap cleanup are incomplete (T1.3; DoD 1).
Evidence: ScreenObservationEngine.kt:86–105 only runs forced OCR when an existing
ScreenCaptureService session is active. The skill does not establish a capture
preflight and does not recycle the returned bitmap for result-page or final
observations (ShopeeShoppingSkill.kt:110 and :199).
[ ] Distinguish missing/revoked capture permission from an empty product result.
    Request per-session consent through an eligible UI when OCR is required;
    stop with an actionable result if capture cannot proceed.
[ ] Release each owned bitmap in a finally block after consumers finish and
    clean up the owned capture session on success, failure, and cancellation.
    Keep screenshots in RAM only and respect secure-screen restrictions.

R6 — OCR fallback does not cover the search interaction (target flow search step).
Evidence: ShopeeShoppingSkill.kt:83–97 still locates and submits search only through
clickNodeByText and types without verifying the target field or action result.
[ ] Resolve the search field and submit control through fresh semantic/OCR/visual
    evidence when accessibility text is absent. Verify focus, query entry, and
    the results transition before collecting products.

R7 — Passing helper tests do not establish end-to-end acceptance (Phase 4).
Evidence: ShopeeShoppingSkillTest calls extraction and ranking helpers, not the
execute flow with capture, model responses, gestures, and final verification.
[ ] Add deterministic integration tests covering missing nodes, capture consent,
    AI/VLM calls, OCR target selection, action effects, and final-screen identity.
[ ] Run the relevant tests and APK build after fixes; record actual results.
[ ] Record a real-device Shopee test with device, Android/app versions, and
    observations. Do not claim hardware validation without that evidence.

VALIDATION PERFORMED DURING THIS REVIEW
Command: ./gradlew testDebugUnitTest assembleDebug --no-daemon --console=plain
Result: BUILD SUCCESSFUL. The unit-test and APK tasks reported UP-TO-DATE, so this
check reused existing outputs rather than freshly executing the tests. A Gradle
deprecation warning remains. No physical-device validation was performed here.
Application code was not changed as part of this rejection/documentation update.

REACCEPTANCE GATE
All R1–R7 fixes and their regression/integration tests must be satisfied. A real
user-authorized run must use the configured AI where required, observe missing
node content through OCR, tap only a fresh verified target, and leave the matching
product detail page visible. Keep the plan in plan/reject until evidence supports
moving it to plan/completed; compilation or folder placement alone is insufficient.
-->

# AI Vision and OCR Phone Control — Integration Fix Plan

**Status:** Rejected / incomplete after source review on 2026-09-27. See the top-of-file review comment for implemented progress, blockers R1–R7, required fixes, and acceptance criteria.
**Initial application:** Shopee Vietnam (`com.shopee.vn`).
**Purpose:** Fulfill user requirements using natural language AI reasoning, on-device OCR, multi-layer visual screen perception, and safe phone control execution.

---

## 1. User Requirement & Target Flow

Example:
> “Mở Shopee, tìm tai nghe Bluetooth dưới 500 nghìn, đánh giá tốt, rồi mở sản phẩm phù hợp nhất.”

### Required Execution Stages:
1. **Understand Request:** Parse product query, budget limits (e.g. 500k VND), specifications, and quality preferences.
2. **Launch & Verify App:** Open Shopee; verify package is active foreground.
3. **Perceive Screen (3-Layer Pipeline):**
   - Layer 1: Accessibility Node Tree extraction.
   - Layer 2: On-device ML Kit OCR bounding boxes (`OcrEngine`).
   - Layer 3: Vision AI (VLM) if visual/canvas layout requires image reasoning.
4. **Plan Next Step:** Model proposes navigation actions (Search, Type, Scroll, Select).
5. **Target Resolution (Checkpointing):** Resolve target to physical screen coordinates `(x, y)` using OCR text blocks or node bounds.
6. **Execute Gesture:** Dispatch tap/scroll via `JavisAccessibilityService`.
7. **Verify Postcondition:** Re-observe screen to confirm action effect.
8. **Final Handoff:** Open product detail page, verify matching criteria, terminate automation, leave screen open for user.

---

## 2. Current Gaps to Fix

| Component | Current Defect | Required Fix |
|---|---|---|
| `ShopeeShoppingSkill.kt` | Collects candidates strictly from `dumpNodeHierarchy()` | Add OCR fallback (`OcrEngine`) when items are rendered in canvas/webview |
| `ShopeeShoppingSkill.kt` | Clicks via `clickNodeByText(title.take(30))` | Add coordinate tap fallback `dispatchGesture(tap at ocrBlock.centerX, ocrBlock.centerY)` |
| `ScreenObservationEngine.kt` | OCR triggers only if hierarchy string < 150 chars | Allow explicit OCR trigger when product cards are missing from node tree |
| `ShopeeShoppingSkill.kt` | Hardcodes fallback `rating = 4.8f`, `reviewCount = 100` | Mark missing metrics as `null`; do not fabricate quality data |
| `ShopeeShoppingSkill.kt` | Mixes sales volume ("Đã bán") with review count | Keep sales count and reviews separate |
| `ShopeeShoppingSkill.kt` | Final verification passes on `isDetailPage \|\| clicked` | Require fresh observation verifying detail page headers or title match |
| `ShopeeShoppingSkill.kt` | Bypasses `AgentOrchestrator` lifecycle | Route through shared execution ownership, cancellation token, and safety limits |

---

## 3. Architecture & Target Resolution

```
[User Shopping Request]
          │
          ▼
[ShoppingTaskParser] ──> Extracts query, maxPrice, qualityPrefs
          │
          ▼
[ShopeeShoppingSkill via AgentOrchestrator]
          │
          ├──> 1. Launch Shopee (`com.shopee.vn`)
          ├──> 2. Locate search box (Accessibility Node OR OCR "Tìm kiếm")
          ├──> 3. Type query & submit
          │
          ▼
[Screen Perception on Results]
          ├── Accessibility Node Tree (Layer 1)
          └── On-Device ML Kit OCR Blocks (Layer 2)
                    │
                    ▼
[Target Resolver / Checkpoint Selection]
  For each candidate card:
  • Title block: Rect(x1, y1, x2, y2)
  • Price block: Rect(x1, y1, x2, y2) -> Parse VND
  • Target checkpoint: (centerX, centerY) of product card
          │
          ▼
[ProductRanker] ──> Selects highest-scored candidate within budget
          │
          ▼
[Physical Tap Dispatch]
  `accessibilityService.dispatchGesture(tap at checkpoint)`
          │
          ▼
[Postcondition Verification]
  Re-observe screen -> Confirm detail page active -> Terminate & leave on screen
```

---

## 4. Implementation Tasks Breakdown

### Phase 1: OCR & Target Resolution (`vision/`)
- [ ] **T1.1:** Add `ScreenTargetResolver.kt` to map OCR blocks and node rectangles to physical tap checkpoints `(centerX, centerY)`.
- [ ] **T1.2:** Update `CoordinateUtils.kt` to handle screen insets, status bar offsets, and display scaling.
- [ ] **T1.3:** Allow `ScreenObservationEngine.kt` to force OCR extraction when structured product entities are requested.

### Phase 2: AI Planning & Observation Integration (`agent/`)
- [ ] **T2.1:** Pass OCR block list and coordinates into `ScreenObservation` for AI planner awareness.
- [ ] **T2.2:** Update `ActionPlanResult` handling to support coordinate clicks from OCR checkpoints when node IDs are absent.

### Phase 3: Shopee Shopping Skill Hardening (`agent/shopping/`)
- [ ] **T3.1:** Eliminate fabricated rating (`4.8f`) and review (`100`) defaults in `ShopeeShoppingSkill.kt`.
- [ ] **T3.2:** Implement OCR-based product card extraction combining title, price, and location bounds.
- [ ] **T3.3:** Replace title-text-only click with `ScreenTargetResolver` coordinate click fallback.
- [ ] **T3.4:** Harden final verification: require fresh observation confirming Shopee package and detail page indicators before reporting success.
- [ ] **T3.5:** Connect execution directly through `AgentOrchestrator` to inherit cancellation tokens, timeouts, and policy guardrails.

### Phase 4: Verification & Acceptance Tests
- [ ] **T4.1:** Unit test `ScreenTargetResolverTest.kt` for OCR bounding box to screen coordinate mapping.
- [ ] **T4.2:** Unit test `ShopeeShoppingSkillTest.kt` verifying OCR fallback when node hierarchy is empty.
- [ ] **T4.3:** End-to-end unit tests for budget boundaries and candidate ranking with partial OCR data.
- [ ] **T4.4:** Verify `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

---

## 5. Definition of Done (DoD)

1. When Shopee UI renders items without accessible node text, OCR automatically extracts titles, prices, and bounding boxes.
2. Clicking a product candidate uses verified checkpoint coordinates from OCR when text-node click fails.
3. No product rating or review numbers are invented or assumed.
4. The final product screen is verified by a fresh observation and remains open for the user.
5. All automated unit tests pass; diagnostics show 0 errors and 0 warnings.
