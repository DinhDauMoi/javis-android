# AI Vision and OCR Phone Control — Integration Fix Plan

**Status:** Proposed implementation; all tasks require implementation and validation.
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
