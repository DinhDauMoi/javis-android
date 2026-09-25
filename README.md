# JAVIS — Trợ lý AI Điều Khiển Điện Thoại Bằng Giọng Nói Tiếng Việt

**JAVIS** là ứng dụng Android điều khiển thiết bị hoàn toàn bằng giọng nói tiếng Việt tự nhiên dành riêng cho anh **Dinh** trên thiết bị **OPPO Find X8 Ultra** (Android 15 / ColorOS) và các thiết bị Android hiện đại (`minSdk 26`, `targetSdk 34`).

---

## 🌟 1. Tính Năng Nổi Bật

### 📱 1.1. Mở Ứng Dụng Nhanh
- Nói lệnh mở các ứng dụng phổ biến: *"mở tiktok"*, *"mở youtube"*, *"mở zalo"*, *"mở facebook"*, *"mở chrome"*, *"mở camera"*, *"mở cài đặt"*, *"mở tin nhắn"*, *"mở đồng hồ"*.
- **Tự động quét danh bạ ứng dụng:** Nếu bạn nói tên app chưa có trong danh sách mặc định, JAVIS sẽ quét toàn bộ ứng dụng đã cài đặt trên máy để mở.
- Nếu chưa cài app: JAVIS sẽ thông báo bằng giọng nói: *"Chưa cài [tên app] trên máy"*.

### 👆 1.2. Điều Khiển Thao Tác Màn Hình (Accessibility Service)
- **"lướt lên" / "video tiếp theo" / "tiếp theo":** Chuyển sang video TikTok, Reels, Shorts tiếp theo (tự động fallback vuốt từ dưới lên).
- **"lướt xuống" / "video trước":** Xem lại video trước đó.
- **"bấm [tên nút]" / "nhấn [tên nút]":** Tự động dò tìm chữ hiển thị trên màn hình (như *"bấm thích"*, *"bấm theo dõi"*, *"bấm mua ngay"*) và click vào nút.
- **"quay lại" (Back)** và **"về màn hình chính" (Home)**.

### ⚙️ 1.3. Điều Khiển Hệ Thống Thiết Bị
- **Âm lượng:** *"tăng âm lượng"*, *"giảm âm lượng"*, *"tắt tiếng"*, *"bật tiếng"*.
- **Kết nối:** *"bật wifi"*, *"tắt wifi"*, *"bật bluetooth"*, *"tắt bluetooth"*.
- **Đèn pin:** *"bật đèn pin"*, *"tắt đèn pin"*.
- **Khóa màn hình:** *"khóa màn hình"* (sử dụng Trợ năng hoặc Device Admin).
- **Chụp màn hình:** *"chụp màn hình"* (sử dụng cử chỉ Trợ năng).

### ⏰ 1.4. Tiện Ích Đời Sống & Đàm Thoại
- **Xem giờ & ngày:** *"mấy giờ rồi"*, *"hôm nay ngày mấy"*.
- **Hẹn giờ đếm ngược:** *"hẹn giờ 10 giây"*, *"hẹn giờ 5 phút"* (báo chuông & đọc giọng nói khi hết giờ).
- **Đặt báo thức:** *"đặt báo thức 6 giờ 30"* (mở đồng hồ hệ thống).
- **Gọi điện thoại:** *"gọi cho Mẹ"*, *"gọi Nam"* (tra danh bạ và gọi điện).
- **Soạn tin nhắn:** *"nhắn tin cho [tên]: [nội dung]"* (mở app SMS với tin nhắn soạn sẵn, không tự gửi lén).

### 🤖 1.5. Trí Tuệ Nhân Tạo (OpenAI Compatible LLM)
- Khi câu nói không thuộc các lệnh điều khiển hệ thống, JAVIS tự động gửi câu hỏi đến mô hình AI (OpenAI, DeepSeek, Gemini, v.v.).
- Câu trả lời được cô đọng súc tích và đọc to bằng giọng nói tiếng Việt tự nhiên qua TextToSpeech.

### 🛠️ 1.6. Lệnh Tùy Chỉnh (Custom Commands)
- Người dùng tự định nghĩa trong màn hình **Cài đặt**:
  - Tên câu lệnh bạn muốn nói (ví dụ: *"lướt video tiếp"*).
  - Hành động thực hiện: Mở app, vuốt lên, vuốt xuống, bấm nút có chữ X, mở URL website.
  - Lưu trữ bền vững bằng Room Database, ưu tiên so khớp trước lệnh mặc định.

### ⚡ 1.7. Phím Tắt Nhanh (Quick Settings Tile & Floating Mic)
- **Quick Settings Tile:** Ô cài đặt nhanh trên thanh thông báo. Kéo thanh trạng thái xuống, bấm ô **JAVIS Voice** để kích hoạt mic ngay cả khi đang ở app khác.
- **Nút Mic Nổi (Floating Bubble):** Nút mic tròn có thể kéo thả di chuyển trên màn hình, chạm để nói bất cứ lúc nào.

---

## 🛠️ 2. Công Nghệ & Kiến Trúc Dự Án

- **Ngôn ngữ:** Kotlin 100%
- **Android Target:** `minSdk 26` (Android 8.0), `targetSdk 34` (Android 14/15)
- **Kiến trúc phân tầng rõ ràng:**
  - `voice/VoiceInput.kt`: Wrapper chuẩn cho `SpeechRecognizer` tiếng Việt (`vi-VN`).
  - `voice/Speaker.kt`: Wrapper cho `TextToSpeech` tiếng Việt.
  - `voice/WakeWordDetector.kt`: Mô-đun từ khóa "Hey Javis" (Porcupine).
  - `commands/CommandParser.kt`: Chuẩn hóa tiếng Việt bỏ dấu (`TextNormalizer`) & regex phân tích lệnh.
  - `commands/CommandExecutor.kt`: Điều phối thực thi thao tác và phản hồi.
  - `service/JavisAccessibilityService.kt`: Dịch vụ trợ năng điều khiển cử chỉ cuộn, chạm, click.
  - `service/JavisTileService.kt`: Dịch vụ Quick Settings Tile.
  - `service/FloatingBubbleService.kt`: Dịch vụ vẽ nút mic nổi trên màn hình.
  - `ai/OpenAiClient.kt`: Gọi API AI bằng OkHttp và `org.json`.
  - `data/AppDatabase.kt`: Quản trị Room Database lưu trữ lệnh tùy chỉnh.

---

## 📋 3. Hướng Dẫn Cài Đặt & Cấp Quyền

### Bước 1: Mở và Build APK trong Android Studio
1. Mở **Android Studio** (bản Hedgehog hoặc Iguana/Jellyfish).
2. Chọn **File → Open** và duyệt đến thư mục `Code` này.
3. Chờ Gradle đồng bộ (Sync Project with Gradle Files).
4. Kết nối điện thoại **OPPO Find X8 Ultra** qua cáp USB (đã bật **Gỡ lỗi USB / USB Debugging**).
5. Nhấn nút **Run 'app'** (tam giác xanh) hoặc vào menu **Build → Build Bundle(s) / APK(s) → Build APK(s)** để cài lên máy.

### Bước 2: Cấp Quyền Cho JAVIS
Khi khởi chạy app lần đầu, hãy cấp các quyền sau:
1. **Micro (Thu âm):** Bấm "Cho phép khi dùng ứng dụng" để nhận diện giọng nói.
2. **Danh bạ & Cuộc gọi:** Cho phép để thực hiện lệnh tra cứu tên và gọi điện thoại.
3. **BẬT DỊCH VỤ TRỢ NĂNG (BẮT BUỘC ĐỂ ĐIỀU KHIỂN TIKTOK/APP):**
   - Vào **Cài đặt điện thoại** → **Cài đặt bổ sung / Trợ năng** (Accessibility).
   - Chọn mục **Ứng dụng đã tải xuống** → Chọn **JAVIS**.
   - Bật công tắc **Sử dụng JAVIS** và bấm **Cho phép**.
4. **Quyền vẽ trên ứng dụng khác (Tùy chọn):** Nếu muốn dùng nút Mic nổi, hãy bật công tắc trong Cài đặt JAVIS và cho phép quyền hiển thị trên màn hình khác.
5. **Thêm Quick Settings Tile:** Vuốt thanh thông báo xuống 2 lần → Nhấn biểu tượng chỉnh sửa (cây bút hoặc dấu 3 chấm) → Kéo ô **JAVIS Voice** lên danh sách thao tác nhanh.

---

## ⚠️ 4. Lưu Ý Quan Trọng Cho Điện Thoại OPPO / ColorOS

Hệ điều hành **ColorOS 15** của OPPO có cơ chế quản lý pin và tối ưu ứng dụng rất nghiêm ngặt. Để JAVIS và dịch vụ Trợ năng không bị hệ thống tự động tắt:

1. **Khóa ứng dụng chạy ngầm:**
   - Mở màn hình đa nhiệm (vuốt lên và giữ).
   - Nhấn dấu 3 chấm trên thẻ ứng dụng JAVIS → Chọn **Khóa (Lock)** để không bị đóng khi bấm xóa tất cả.
2. **Cho phép tự khởi chạy:**
   - Vào **Cài đặt** → **Ứng dụng** → **Tự khởi động (Auto-launch)**.
   - Tìm **JAVIS** và bật công tắc cho phép ứng dụng tự khởi động.
3. **Tắt tối ưu hóa pin:**
   - Vào **Cài đặt** → **Pin** → **Cài đặt khác** → **Tối ưu hóa mức sử dụng pin**.
   - Tìm **JAVIS** và chọn **Không tối ưu hóa (Don't optimize)**.
4. **Kiểm tra ứng dụng Google:**
   - `SpeechRecognizer` tiếng Việt của Android hoạt động dựa trên ứng dụng Google. Hãy đảm bảo app **Google** đã được cập nhật bản mới nhất trên máy và ngôn ngữ tìm kiếm bằng giọng nói đã chọn Tiếng Việt.

---

## 🎯 5. Danh Sách Lệnh Mẫu Để Thử Ngay

| Câu lệnh nói | Kết quả thực thi |
|---|---|
| *"mở tiktok"* | Mở ngay ứng dụng TikTok |
| *"lướt lên"* / *"video tiếp theo"* | Tự động cuộn sang video TikTok tiếp theo |
| *"lướt xuống"* | Quay lại video trước đó |
| *"bấm thích"* | Tự động tìm và bấm vào nút Tim / Thích trên màn hình |
| *"mấy giờ rồi"* | JAVIS đọc giờ hiện tại bằng tiếng Việt |
| *"hôm nay ngày mấy"* | JAVIS đọc thứ, ngày, tháng, năm |
| *"hẹn giờ 10 giây"* | Đếm ngược và đọc thông báo khi hết giờ |
| *"tăng âm lượng"* / *"tắt tiếng"* | Chỉnh âm lượng thiết bị |
| *"bật đèn pin"* / *"tắt đèn pin"* | Bật/tắt đèn Flash |
| *"quay lại"* | Nhấn nút Back của hệ thống |
| *"về màn hình chính"* | Trở về Home Launcher |
| *"Thủ đô của nước Pháp là gì?"* | Tự động hỏi AI và đọc câu trả lời |

Chúc anh **Dinh** có trải nghiệm điều khiển rảnh tay tuyệt vời cùng trợ lý **JAVIS**!
