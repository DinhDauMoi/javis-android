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


