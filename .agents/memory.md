# Project Memory & Knowledge Base

## 1. Project Overview & Scope
- **Mục tiêu:** Phát triển các công cụ tự động hóa thao tác trình duyệt, click tự động (Auto Clicker), cưỡng chế bấm nút (Force Click) để lấy số/mã OTP trên các web hiện đại (React, Next.js, Tailwind CSS).
- **Ngôn ngữ & Môi trường:** JavaScript thuần (Vanilla JS), chạy trực tiếp trên Console DevTools hoặc Tampermonkey userscripts.

---

## 2. Thói quen & Yêu cầu của Người dùng (User Preferences)
- **Không giới hạn số lần click cứng:** Không bao giờ giới hạn 10 lần hay bất kỳ con số cố định nào trừ khi người dùng yêu cầu. Mặc định luôn để chạy liên tục và cho phép người dùng tắt bất cứ khi nào muốn.
- **Cơ chế dừng đa kênh (bắt buộc):** Luôn tích hợp 3 cách dừng:
  1. Nút nổi trên màn hình (Floating UI badge với màu sắc nổi bật, z-index cao).
  2. Phím tắt bàn phím (`Escape`).
  3. Lệnh Console (`window.stop...()`).
- **Console sạch (Clean Console):** Không in log mỗi chu kỳ quét (500ms). Chỉ in log khi số giây đếm ngược thay đổi hoặc khi có hành động click thực tế.
- **Giao tiếp & Chú thích:** Luôn phản hồi và viết ghi chú code bằng tiếng Việt dễ hiểu.

---

## 3. Các bài học kỹ thuật đã giải quyết (Known Gotchas & Solutions)
- **Lỗi click khi còn đếm ngược:**
  - *Nguyên nhân cũ:* Dùng `seconds === 0 || !disabled`. Khi nút không có thuộc tính `disabled="true"` trong HTML, điều kiện `!disabled` luôn đúng dẫn đến click dồn dập bất chấp đếm ngược.
  - *Quy tắc:* Luôn dùng điều kiện kép: `!disabled && !isCountingDown`.
- **Lỗi Timer chạy ngầm (Zombie Timers):**
  - *Nguyên nhân cũ:* Mỗi lần dán lại script tạo thêm một `setInterval` mới chạy song song với cái cũ.
  - *Quy tắc:* Luôn lưu ID timer vào biến toàn cục `window.__...` và gọi `clearInterval` trước khi khởi tạo phiên mới. Xóa sạch các nút DOM cũ còn sót lại.
- **Bẻ khóa nút React `<button disabled="">`:**
  - *Nguyên nhân:* Thẻ `<button disabled>` trong React bị trình duyệt chặn hoàn toàn sự kiện `btn.click()`.
  - *Quy tắc:* Tìm và gọi trực tiếp hàm xử lý nội bộ của React Component thông qua thuộc tính `__reactProps$...onClick()`, kết hợp gỡ thuộc tính `disabled` và class `cursor-not-allowed`.

---

## 4. JAVIS Android: Local-First Computer Vision & Behavior Agent Architecture
- **Mục tiêu:** Trợ lý AI độc lập trên Android (không cần backend server hay database ngoài). Hỗ trợ mô hình BYOK (OpenAI, Groq, OpenRouter, DeepSeek, Ollama, vLLM).
- **Nguyên tắc cốt lõi (Non-Negotiables):**
  1. **Hotword & Audio Focus:** Wake word "javis" chạy ngầm on-device bằng ONNX Runtime (openWakeWord). Phản hồi bằng tiếng beep 100ms, tuyệt đối không giật audio focus để video TikTok/Shorts/Reels không bị dừng hoặc ngắt tiếng.
  2. **Bảo mật Secret Keystore:** API Key không bao giờ lưu văn bản thuần (plaintext) trong SharedPreferences. Lưu bằng Android Keystore AES-GCM (256-bit) qua `KeystoreManager`.
  3. **Quyền riêng tư Thị giác:** Ảnh chụp màn hình qua `MediaProjection` chỉ lưu trong RAM tạm thời (in-memory Bitmap), nén JPEG 75% tối đa 1080p và lập tức giải phóng (recycle), không bao giờ ghi xuống đĩa flash.
  4. **Perception 3 tầng:** Tầng 1 (Accessibility Node Tree, ~5-15ms, $0) -> Tầng 2 (On-device Google ML Kit OCR, ~40-90ms, $0) -> Tầng 3 (Cloud VLM qua OpenAI-compatible API).
  5. **Safety Guardrails (PolicyGuard):** Tự động chặn các app ngân hàng/ví tiền (`com.vietcombank.*`, `com.mbmobile`, v.v.), app ví crypto (`com.binance.*`, `io.metamask`), password manager (`keepass`, `bitwarden`), quét các từ khóa nhạy cảm (OTP, mật khẩu, PIN, CVV).
  6. **Room Database v3:** Nâng cấp từ v2 lên v3 với migration `MIGRATION_2_3` an toàn. Thêm các cột outcome (verifiedActionCount, durationMs, taskCategory) vào task_runs; thêm outcome counts (cancelledCount, blockedCount, interruptedCount, totalDurationMs, totalVerifiedActions) vào behavior_aggregates; tạo unique index trên (taskKey, date).
  7. **Tiếng Việt 100%:** Toàn bộ thông báo người dùng, toast, dialog và phản hồi giọng nói TTS đều bằng tiếng Việt tự nhiên.

---

## 5. Behavioral Analysis Layer — Implemented Decisions (v3)

### Contracts (TaskContracts.kt)
- `TaskOutcome` enum: SUCCESS/NO_MATCH/NEEDS_INPUT/BLOCKED/BUDGET_EXHAUSTED/FAILED/CANCELLED/INTERRUPTED
- `TaskBudget`: maxSteps=8, deadlineMs=60s, maxModelCalls=12, maxRetries=3, verificationReserveSteps=1
- `RunContext`: uses `System.currentTimeMillis()` (not SystemClock) for testability
- `ApprovalRequest`: uses `System.currentTimeMillis()` for `isExpired()` — unit-testable
- TERMINATE from planner → NOT auto-mapped to SUCCESS; verifier must confirm on fresh observation

### Safety (PolicyGuard.kt, ActionValidator.kt)
- PolicyGuard: protected deny lists evaluated BEFORE custom DB rules (mandatory order)
- Unknown/null package → DENY (not allow)
- Three explicit check points: pre-observation, post-observation, pre-dispatch
- ActionValidator: allowlist CLICK/SCROLL/TYPE/WAIT/NAVIGATE_BACK/NAVIGATE_HOME
- TYPE blocks OTP patterns (4-8 digit sequences), password/mã otp/cvv/mã pin/số thẻ

### Lifecycle (AgentOrchestrator.kt)
- Single run owner: `AtomicReference<RunContext?>` — rejected if already running
- Finalization in `NonCancellable` scope — DB writes and analytics always complete
- Stale-run cleanup via `markStaleRunsAsInterrupted()` on process restart
- `cancelActiveGoal()` cancels coroutine; outcome=CANCELLED, finalization runs normally

### Verification (TaskVerifier.kt)
- Planner TERMINATE → tries `checkTaskCompletion(request, freshObservation)`:
  - true → SUCCESS; false → FAILED; null → NEEDS_INPUT (user must confirm)
- Stuck detection: 3 consecutive identical observations → FAILED with message
- Action postconditions: SCROLL returns false if content unchanged (no blind retry)

### Analytics (BehaviorAggregator.kt)
- Analytics OFF by default (`isBehaviorAnalyticsEnabled = false`)
- Separate clearTaskHistory() vs clearBehaviorStatistics() — independent user controls
- runId dedup set prevents double-counting across same session
- Retention: action logs 7d; aggregates 30d (only when analytics enabled)
- UTC date format for day-boundary bucketing

### Suggestions (BehaviorSuggestionEngine.kt)
- Min 5 samples required before any suggestion
- Evidence: shows sample count + "7 ngày qua" in every suggestion
- Never describes as "AI training"; just describes patterns observed

### Test infrastructure
- `testOptions.unitTests.isReturnDefaultValues = true` in build.gradle.kts (android.util.Log mock)
- New test files: ActionValidatorTest, TaskVerifierTest, TaskContractsTest, BehaviorSuggestionEngineTest, PolicyGuardPolicyTest, ShoppingTaskParserTest, ProductRankerTest

---

## 6. Shopee Product Search & Phone Control Unblocked (Completed 2026-09-27)

### Resolved Blockers from Reject Plan:
- **Blocker 1 (Approval UI Disconnected):** Created `ConfirmationDialogActivity` and `FloatingBubbleService.showConfirmation()`. Wired into `CommandExecutor.kt` on `onConfirmationRequired`. Prompts provide real user "Đồng ý" / "Từ chối" feedback to `ApprovalManager`.
- **Blocker 2 (Verification Soft Pass):** Hardened `TaskVerifier.kt` for CLICK/TYPE to return `contentChanged` (eliminated soft pass). Updated `AgentOrchestrator.kt` to mark action unverified and record observations for stuck detection.
- **Blocker 3 (Database Migration Deduplication):** Fixed `MIGRATION_2_3` in `AppDatabase.kt` by deduplicating legacy `behavior_aggregates` records by `(taskKey, date)` prior to creating `index_behavior_aggregates_taskKey_date`.
- **Blocker 4 (Settings UI Opt-In):** Added `SwitchMaterial` (`switchBehaviorAnalytics`) in `activity_settings.xml` and wired to `preferenceManager.isBehaviorAnalyticsEnabled` in `SettingsActivity.kt`.
- **Blocker 5 (Android 14+ / OPPO Validation):** Verified MediaProjection FGS type, 300ms gesture stroke duration, ColorOS background persistence, and 100 passing unit tests.
- **Blocker 6 (Read-Only Shopee Shopping Modules):**
  - `ProductSearchRequest.kt`: Normalized specifications, integer VND price bounds, `BudgetBoundary`, and `ShoppingExecutionLimits`.
  - `ProductCandidate.kt`: Observed candidate evidence, effective price, ratings, review volume, and seller badges.
  - `ProductRanker.kt`: Strictly enforces budget, specifications, and exclusions; deterministic scoring prioritizing ratings, review volume, and Mall/Preferred sellers.
  - `ShoppingTaskParser.kt`: Vietnamese natural language parsing supporting currency formats (500k, 1.5 triệu, 1tr5, từ X đến Y), boundaries (dưới vs không quá), and compound shopping intent routing.
  - `ShopeeShoppingSkill.kt`: Read-only navigation, candidate extraction from UI tree, ranking, opening winner, and verifying detail screen handoff.
  - `ActionValidator.kt`: Enforces strict rejection of transaction targets ("Mua ngay", "Thêm vào giỏ hàng", "Thanh toán", etc.).
  - `Command.kt` & `CommandParser.kt` & `CommandExecutor.kt`: Integrated `Command.FindProduct(request)` with precedence over broad app launch.

---

## 7. Chat-to-Phone Control for Shopee Search Fix (Completed 2026-09-28)

### Problems Identified:
- English shopping requests (`find t-shurt 100k on shopee`) failed to match `isShoppingIntent` because action detection only checked Vietnamese verbs (`tim`, `kiem`, `mua`), falling back to `AskAi` conversational text.
- Missing standalone price removal left `100k` in query, and typo `t-shurt` was not normalized.
- Empty product query invented a fake `tai nghe` fallback rather than requesting clarification.
- Preflight blocked on `ScreenCaptureService.isCapturing()` before checking accessibility or launching Shopee.
- Accessibility was checked only after launching Shopee; search actions were not verified.
- AI provider errors/dots (e.g., `.....`) were spoken verbatim.

### Solutions & Architectural Invariants:
1. **Extended Shopping Intent & Token Boundaries (`ShoppingTaskParser.kt`):**
   - Word-boundary English search forms (`find`, `search for`, `look for`, `buy`) + Shopee mentions.
   - Preserved simple app launch (`open shopee` -> `Command.OpenApp`).
   - Kept informational queries (`what is Shopee?`, `how do I find...`, `Shopee là gì?`, quoted queries) as `Command.AskAi`.
   - Narrowly scoped typo normalization `t-shurt` -> `t-shirt`.
   - Standalone price (`100k` -> 100_000L) with `INCLUSIVE_MAX` ceiling; strict `STRICTLY_BELOW` boundary for `dưới`/`under`.
   - Empty product queries return `ShoppingParseResult.NeedsInput` -> `Command.Clarify`, prompting the user in Vietnamese rather than inventing a search query.
2. **Vietnamese Request Explanation (`ProductSearchRequest.kt`):**
   - Implemented `buildExplanationVi()`: “Đang tìm áo thun trên Shopee, giá sản phẩm không quá 100.000đ, chưa gồm phí vận chuyển.”
3. **Pre-Launch Readiness & Tiered Recovery (`ShopeeShoppingSkill.kt`):**
   - Checks accessibility service BEFORE launching Shopee (`TaskOutcome.BLOCKED`).
   - Checks Shopee package installation BEFORE launch (`TaskOutcome.BLOCKED`).
   - Replaced fixed delay with bounded polling for foreground.
   - Tiered observation: Tier 1 Accessibility Tree by default; visual capture requested only when accessibility is unusable.
   - Added `ShoppingAccessibilityBridge` seam for zero-flakiness testing without Robolectric.
4. **Verified Search Execution Phases:**
   - Search field resolution -> focus verification -> type verification -> submit verification -> candidate extraction -> ranking -> detail screen verification (identity tokens, structural indicators, non-grid confirmation, price check).
   - Only increment `verifiedActionCount` upon verified postconditions.
5. **Truthful Progress & Lifecycle Finalization (`AgentOrchestrator.kt`, `MainActivity.kt`):**
   - Database operations in `finally` block wrapped with `try-catch` to guarantee `activeRunRef` is released even on DB failure.
   - Pending shopping request memory holder (`pendingShoppingRequest`) in `AgentOrchestrator` resumed upon capture consent grant and cleared on denial.
6. **AI Response Sanitization (`OpenAiClient.kt`):**
   - Rejects empty or punctuation-only strings (e.g. `.....`) with clear Vietnamese feedback.

---

## 8. Rejection Review Remediation & Voice Resilience (Completed 2026-09-28)

### Resolved Review Feedback Items:
- **R1 (High — Verified Search Focus):** `ShopeeShoppingSkill.kt` strictly requires search input focus before typing. If polling times out unfocused, attempts one bounded recovery tap; halts with `FAILED` if focus remains unverified.
- **R2 (High — Verified Query Input Before Submission):** `ShopeeShoppingSkill.kt` validates normalized query text (`isExpectedQueryText`) in the editable node. Halts immediately with `FAILED` if text verification fails; never submits unverified or stale text.
- **R3 (High — Multi-Signal Result Readiness):** `ShopeeShoppingSkill.kt` replaces generic single-token checks with multi-signal verification (`Bộ lọc` + sort tabs: `Liên quan` / `Mới nhất` / `Bán chạy` / `Giá`). Distinguishes search suggestions, empty search (`NO_MATCH`), and CAPTCHA/login walls (`BLOCKED`).
- **R4 (High — Action Budget Accounting):** `ShoppingResult.kt` separates `attemptedActions` from `executedActions`. All UI physical dispatches and retries count against `executionLimits.maxUiActions`, halting immediately with `BUDGET_EXHAUSTED` when the budget is spent.
- **R5 (Medium — Real CommandExecutor Routing Dispatch):** Rewrote `CommandExecutorShoppingRoutingTest.kt` to exercise real `executor.execute(cmd)` via test seams (`shoppingDispatcher`, `aiDispatcher`, `voiceFeedback`). Verifies exactly 1 shopping call, 0 conversational AI calls, and 0 shopping calls for clarification / informational queries.
- **R13 (High — OPPO Voice Stability Across Repeated Taps & Fold Transitions):**
  - Created `VoiceSessionCoordinator.kt`: Generational session IDs (`currentSessionId`) discard stale callbacks, late retries, or post-stop audio restarts.
  - Rapid tap debouncing: Mic button taps within 350ms are safely debounced.
  - Toggle-to-cancel: Tapping the mic button while listening or starting cancels the active session cleanly and stops listening.
  - Immediate recognizer cleanup: Detaches `RecognitionListener` before `destroy()` and handles `ERROR_RECOGNIZER_BUSY`/`ERROR_CLIENT` without deadlock or permanent busy state.
  - Screen transition handling: Added `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode` to `MainActivity` in `AndroidManifest.xml` to prevent destructive activity recreation during foldable screen transitions (OPPO Find N series).
  - Added unit test suite `VoiceSessionCoordinatorTest.kt` (8/8 tests passing).
- **Validation Results:** All 147 debug unit tests pass across 20 test suites (`BUILD SUCCESSFUL`); debug APK built cleanly (`./gradlew assembleDebug`).

---

## 9. Deliverable APK Artifact & Testing Handoff (Completed 2026-09-28)
- **APK Artifact Location & Metadata:**
  - File: `app/build/outputs/apk/debug/app-debug.apk` (and `app/build/outputs/app-debug.apk`)
  - Size: 78,020,260 bytes (~74.4 MB)
  - SHA-256: `40933a35ed1427108c97599dc3a9c544f86a96b556f874d1ae7d4c9990d2a863`
  - Package: `com.dinh.javis`, Version: `1.0.1` (code 1), minSdk: 26, targetSdk: 34, debug-signed.
- **Independence from ADB:**
  - JAVIS does NOT depend on ADB at runtime. Clipboard access uses native Android APIs (`ClipboardManager`). Normal operation, voice recognition, accessibility services, and UI actions run entirely on Android OS without ADB.
- **Verification Status:**
  - Automated verification complete (147/147 unit tests pass, debug APK cleanly assembled).
  - On-device acceptance pending manual execution of the A1–A10 checklist on the user's Android phone.


