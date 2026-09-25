# PROMPT: Xây dựng app JAVIS — Trợ lý AI điều khiển điện thoại bằng giọng nói

> Copy toàn bộ prompt này paste vào AI (ChatGPT / Claude / Gemini...) để nó viết code app cho bạn.

---

Tôi muốn bạn viết một ứng dụng Android hoàn chỉnh tên **JAVIS** — trợ lý AI cá nhân điều khiển
điện thoại bằng **giọng nói tiếng Việt**.

**Người dùng & thiết bị:** tên Dinh, điện thoại OPPO Find X8 Ultra (Android 15, ColorOS).

**Mục tiêu:** nói lệnh → điện thoại tự làm việc: mở app, lướt TikTok bằng giọng nói, chỉnh âm lượng,
bật/tắt wifi-bluetooth-đèn pin, xem giờ, hẹn giờ, gọi điện, và trả lời câu hỏi bằng AI.

## 1. Công nghệ

- Ngôn ngữ: **Kotlin**, Android Studio, `minSdk 26`, `targetSdk 34`.
- **KHÔNG dùng thư viện trả phí.** Ưu tiên API có sẵn của Android framework.
- Nhận diện giọng nói: `SpeechRecognizer` + locale `vi-VN` (miễn phí, dùng engine của Google).
  Ghi chú trong code phương án dự phòng Vosk offline (chưa cần implement).
- Đọc phản hồi: `TextToSpeech`, ngôn ngữ `Locale("vi", "VN")`.
- Điều khiển app khác: `AccessibilityService` (`ACTION_SCROLL_FORWARD/BACKWARD`,
  `dispatchGesture` để vuốt/chạm, tìm node theo text để bấm nút).
- Gọi nhanh khi đang ở app khác: `TileService` (ô Quick Settings).
- Wake word "hey javis" luôn nghe: dùng Porcupine (Picovoice) — **tính năng optional**,
  code theo dạng dễ bật/tắt, mặc định TẮT.
- Trả lời thông minh: gọi API tương thích OpenAI (cấu hình base URL + API key trong màn hình
  Cài đặt, mặc định để trống).

## 2. Kiến trúc file (gợi ý — được phép điều chỉnh nếu có cách hợp lý hơn)

- `MainActivity.kt` — UI chính: nút mic lớn, khung log hội thoại, nhận Intent `ACTION_VOICE` từ Tile
- `voice/VoiceInput.kt` — wrapper `SpeechRecognizer`: xin quyền micro, start/stop, callback text/error
- `voice/Speaker.kt` — wrapper `TextToSpeech` tiếng Việt: `speak(text)`
- `commands/CommandParser.kt` — biến text thành `Command` (dùng contains/regex tiếng Việt,
  **nhận cả câu không dấu**)
- `commands/CommandExecutor.kt` — thực thi từng loại lệnh, gọi service khi cần
- `service/JavisAccessibilityService.kt` — giữ `instance` singleton; các hàm:
  `scrollForward()`, `scrollBackward()`, `swipe(x1,y1,x2,y2)`, `tapAt(x,y)`,
  `clickNodeByText(text)`, `pressBack()`, `goHome()`
- `service/JavisTileService.kt` — bấm tile → mở MainActivity + nghe lệnh ngay
- `settings/SettingsActivity.kt` — nhập API key + base URL, bật/tắt wake word,
  quản lý lệnh tùy chỉnh
- `data/CustomCommand.kt` — data class lệnh tùy chỉnh, lưu persistent bằng Room
  (hoặc SharedPreferences dạng JSON nếu đơn giản hơn)

## 3. Danh sách lệnh PHẢI làm được (tiếng Việt, nhận cả không dấu)

### 3.1. Mở ứng dụng
- "mở tiktok / tik tok", "mở youtube", "mở zalo", "mở facebook", "mở chrome",
  "mở camera", "mở cài đặt", "mở đồng hồ", "mở tin nhắn"
- Map sẵn tên → package name các app phổ biến. Nếu không có trong map: quét
  `packageManager` tìm app theo tên (khớp gần đúng) rồi mở.
- App chưa cài → nói: "Chưa cài [tên app] trên máy".

### 3.2. Điều khiển trong ứng dụng (qua AccessibilityService)
- **"lướt lên"** = nội dung tiếp theo (scroll forward; nếu không tìm được node cuộn
  thì fallback vuốt từ dưới lên giữa màn hình)
- **"lướt xuống"** = nội dung trước đó (scroll backward; fallback vuốt từ trên xuống)
- **"bấm [tên nút]"** — tìm node có text khớp (vd: "bấm thích", "bấm theo dõi") rồi click
- **"quay lại"**, **"về màn hình chính"**
- Nếu `JavisAccessibilityService.instance == null` (chưa bật trợ năng): nói rõ
  "Mở Cài đặt → Trợ năng → bật JAVIS" **và** mở luôn màn hình cài đặt trợ năng cho user.

### 3.3. Điều khiển hệ thống
- "tăng âm lượng" / "giảm âm lượng", "tắt tiếng" / "bật tiếng" (`AudioManager`)
- "bật wifi" / "tắt wifi", "bật bluetooth" / "tắt bluetooth", "bật đèn pin" / "tắt đèn pin"
- "khóa màn hình" (dùng `DevicePolicyManager` — code sẵn, hướng dẫn cấp quyền admin trong README)
- "chụp màn hình" (`MediaProjection`) — đánh dấu **optional/khó**, có thể để stub

### 3.4. Tiện ích
- "mấy giờ rồi", "hôm nay ngày mấy" → đọc giờ/ngày hiện tại
- "hẹn giờ [x] phút/giây" → đếm ngược trong app, hết giờ báo bằng giọng nói
  (vd: "hẹn giờ 10 giây", "hẹn giờ 5 phút")
- "đặt báo thức [giờ]" → mở intent đặt báo thức của hệ thống
- "gọi cho [tên]" → tìm danh bạ (`READ_CONTACTS`), **đọc lại tên để user xác nhận**
  rồi mới gọi (`CALL_PHONE`)
- "nhắn tin cho [tên]: [nội dung]" → mở app SMS/Zalo với nội dung soạn sẵn,
  **không tự gửi lén**

### 3.5. Trả lời thông minh (AI)
- Câu nào không khớp lệnh nào → nếu đã cấu hình API key: gửi cho LLM, đọc câu trả lời bằng TTS.
- Chưa cấu hình key → nói "Tôi chưa hiểu lệnh này" + gợi ý mở Cài đặt xem danh sách lệnh.

## 4. Lệnh tùy chỉnh (user tự thêm)

- Trong màn hình Cài đặt, user thêm được: **tên lệnh → hành động**
  (mở app X / vuốt lên-xuống / bấm nút có text X / mở URL).
- Lưu persistent, load khi khởi động, **ưu tiên khớp trước** lệnh mặc định.

## 5. Yêu cầu kỹ thuật & chất lượng code

- Xử lý lifecycle đúng: `destroy()` SpeechRecognizer, `shutdown()` TTS trong `onDestroy`.
- Không crash khi: service null, mất mạng, API key sai, text rỗng — mọi lỗi đều báo
  bằng giọng nói + ghi log trên màn hình.
- Comment **tiếng Việt** ở các hàm quan trọng; tên biến/hàm tiếng Anh rõ nghĩa.
- Tách UI / logic / service rõ ràng, không nhét hết vào Activity.
- Mọi text hiển thị với user đều **tiếng Việt**.
- Nhận diện lệnh phải chịu được: nói không dấu ("mo tiktok"), nói thừa từ ("ê javis mở youtube dùm"),
  nói sai chính tả nhẹ.

## 6. Quyền & lưu ý cài đặt (ghi vào README.md tiếng Việt)

- `RECORD_AUDIO` (xin runtime), `BIND_ACCESSIBILITY_SERVICE` (user bật thủ công trong
  Cài đặt → Trợ năng), `SYSTEM_ALERT_WINDOW` (nếu làm nút mic nổi — optional),
  `READ_CONTACTS` + `CALL_PHONE` (gọi điện), `BIND_DEVICE_ADMIN` (khóa màn hình).
- Lưu ý riêng ColorOS/OPPO: cho app **tự khởi động**, **tắt tối ưu pin** cho app để
  service không bị hệ thống kill.
- `SpeechRecognizer` cần có app Google trên máy mới chạy.
- Hướng dẫn: mở project bằng Android Studio → Build APK → cài → cấp quyền → dùng.

## 7. Tiêu chí hoàn thành (checklist để tự kiểm)

- [ ] Build APK thành công, cài được lên máy thật
- [ ] Nói "mở youtube" → mở đúng app + có phản hồi bằng giọng nói
- [ ] Đang xem TikTok, nói "lướt lên / lướt xuống" → đổi video đúng chiều
- [ ] Chưa bật trợ năng → app hướng dẫn đúng chỗ bật (mở màn hình trợ năng)
- [ ] "mấy giờ rồi" → đọc đúng giờ; "hẹn giờ 10 giây" → báo đúng lúc
- [ ] Lệnh lạ + đã nhập API key → AI trả lời được bằng giọng nói
- [ ] Thêm lệnh tùy chỉnh trong Cài đặt → restart app vẫn dùng được

**Hãy viết code đầy đủ từng file, sẵn sàng build. Bắt đầu từ cấu trúc project Gradle,
sau đó viết từng file Kotlin, XML layout, AndroidManifest và README.**
