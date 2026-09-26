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
  6. **Room Database v2:** Nâng cấp từ v1 lên v2 với migration `MIGRATION_1_2` an toàn, thêm các bảng `task_profiles`, `ai_profiles`, `task_runs`, `action_logs`, `policy_rules`, `behavior_aggregates`.
  7. **Tiếng Việt 100%:** Toàn bộ thông báo người dùng, toast, dialog và phản hồi giọng nói TTS đều bằng tiếng Việt tự nhiên.
