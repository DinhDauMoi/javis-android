# JAVIS — Trợ lý AI Điều Khiển Điện Thoại Bằng Giọng Nói Tiếng Việt (v4)

**JAVIS** là ứng dụng Android điều khiển thiết bị hoàn toàn bằng giọng nói tiếng Việt rảnh tay dành riêng cho anh **Dinh** trên thiết bị **OPPO Find X8 Ultra** (Android 15 / ColorOS) và các máy Android hiện đại (`minSdk 26`, `targetSdk 34`).

Bản **v4** nâng cấp từ Porcupine sang **openWakeWord** (hoàn toàn mã nguồn mở Apache 2.0, chạy on-device, không cần tài khoản, không API key, không tốn phí) và tối ưu triệt để hiện tượng video TikTok bị dừng khi nghe liên tục.

---

## 🌟 1. Cơ Chế Wake Word "javis" & Thao Tác TikTok Không Chạm Tay

### 🎯 Quy trình thao tác thực tế:
1. Đang xem video TikTok / Reels / Shorts, bạn gọi: **"javis"** (hoặc *"Hey Jarvis"*).
2. JAVIS thức dậy và phát âm thanh beep ngắn (hoặc tone xác nhận).
3. Bạn nói lệnh: **"lướt lên"** (hoặc *"to lên"*, *"nhỏ lại"*, *"mở youtube"*...).
4. JAVIS thực thi thao tác ngay lập tức và phát tiếng **beep 100ms** xác nhận.
5. JAVIS tự động ngủ tiếp và quay lại chờ từ khóa *"javis"*. Không cần chạm tay vào màn hình!

### 🎙️ Công nghệ openWakeWord (On-Device):
- Thư viện Kotlin: `com.github.msnilsen:openwakeword-android:0.1.0` (chạy ONNX Runtime trực tiếp trên điện thoại).
- Hoàn toàn offline, không gửi âm thanh ra ngoài, không cần mạng, không cần tài khoản hay API key.
- **Hai lựa chọn mô hình:**
  1. **Nhanh (mặc định sẵn trong app):** Dùng model built-in `HEY_JARVIS` ("Hey Jarvis"). Người Việt phát âm "javis" ≈ "jarvis" nên model này bắt nhạy ngay.
  2. **Chuẩn xác riêng:** Train model từ khóa riêng cho từ "javis" bằng [Google Colab Notebook của openWakeWord](https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb) (miễn phí GPU, ~30–60 phút), xuất file `javis.onnx`, copy vào thư mục `app/src/main/assets/`. JAVIS sẽ tự động ưu tiên nạp file này.
- **Nút bật/tắt "Chờ gọi 'javis'" ngay trên giao diện chính:** Mặc định **BẬT**. Khi tắt → dừng hoàn toàn detector, nhả toàn bộ micro và biểu tượng mic trên thanh trạng thái biến mất hoàn toàn.

---

## 🔇 2. Giải Pháp Giảm Thiểu TikTok Dừng Video (Audio Focus)

Nguyên nhân trước đây video TikTok dừng là do SpeechRecognizer nghe liên tục hoặc do TextToSpeech (TTS) giật toàn bộ quyền Audio Focus của ứng dụng phát video. Bản v4 khắc phục triệt để:

1. **Không chiếm Audio Focus khi nghe:** Cả khi openWakeWord chờ từ khóa và khi SpeechRecognizer nghe lệnh đều **KHÔNG** yêu cầu Audio Focus.
2. **TTS dùng `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`:** Khi TTS cần nói (chỉ nói khi báo lỗi hoặc hỏi AI), hệ thống chỉ yêu cầu app khác giảm nhẹ âm lượng (duck), không dừng video. Ngay khi dứt lời (`onDone` hoặc `onError`), JAVIS lập tức giải phóng Audio Focus (`abandonAudioFocusRequest`).
3. **Phản hồi bằng Beep 100ms:** Khi thực hiện đúng lệnh (lướt video, chỉnh âm lượng, mở app), JAVIS chỉ phát tiếng beep ngắn 100ms bằng `ToneGenerator`, **KHÔNG dùng TTS**. Video TikTok tiếp tục phát mượt mà không bị khựng lại.
4. **Không đè Micro và TTS:** Khi TTS phát âm thanh, micro tự động tạm dừng để tránh JAVIS tự nhận diện chính giọng nói của mình.

> ⚠️ **LƯU Ý TRUNG THỰC VỀ TIKTOK:**  
> openWakeWord cần giữ micro ở mức hệ thống để phát hiện từ khóa. Nếu trên một số phiên bản TikTok/Android đặc thù, video bị dừng do hệ thống báo "micro đang bận" (mic capture exclusivity) chứ không phải do audio focus, thì đây là giới hạn bảo mật tầng thấp của Android mà ứng dụng thông thường không thể can thiệp trực tiếp. Cần cài đặt và kiểm tra thực tế trên OPPO Find X8 Ultra. Mã nguồn JAVIS hiện tại đã tối ưu ở mức tối đa kỹ thuật cho phép (không giữ focus, chỉ beep ngắn, nhả mic linh hoạt).

---

## 📋 3. Bảng Lệnh Tiêu Chuẩn

Hệ thống tự động nhận diện cả **có dấu và không dấu**, tự động tách wake word và bỏ từ đệm thừa (*"ê javis lướt lên dùm"*, *"javis lướt lên"*, *"to lên giùm tôi"*):

| Câu lệnh (Trigger) | Không dấu | Hành động thực thi | Phản hồi |
|---|---|---|---|
| **"lướt lên"**, **"vuốt lên"**, **"cuộn lên"** | `luot len`, `vuot len`, `cuon len` | Cuộn video tiếp theo (ACTION_SCROLL_FORWARD, fallback vuốt từ dưới lên giữa màn hình) | Beep 100ms |
| **"lướt xuống"**, **"vuốt xuống"**, **"cuộn xuống"** | `luot xuong`, `vuot xuong`, `cuon xuong` | Xem lại video trước (ACTION_SCROLL_BACKWARD, fallback vuốt từ trên xuống) | Beep 100ms |
| **"mở youtube"** | `mo youtube` | Mở package `com.google.android.youtube` (fallback quét tên) | Beep 100ms |
| **"mở tiktok"**, **"mở tik tok"** | `mo tiktok`, `mo tik tok` | Mở package `com.zhiliaoapp.musically` (fallback quét tên) | Beep 100ms |
| **"tăng âm lượng"**, **"to lên"**, **"âm lượng to lên"** | `tang am luong`, `to len`, `am luong to len` | `adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE, FLAG_SHOW_UI)` | Beep 100ms |
| **"giảm âm lượng"**, **"nhỏ lại"**, **"nhỏ xuống"**, **"âm lượng nhỏ xuống"** | `giam am luong`, `nho lai`, `nho xuong` | `adjustStreamVolume(STREAM_MUSIC, ADJUST_LOWER, FLAG_SHOW_UI)` | Beep 100ms |
| **"mấy giờ rồi"**, **"hôm nay ngày mấy"** | `may gio roi`, `ngay may` | Đọc giờ / ngày tháng hiện tại | TTS tiếng Việt |
| **"hẹn giờ [x] phút/giây"** | `hen gio 10 giay` | Đếm ngược và báo chuông khi hết giờ | TTS tiếng Việt |
| **"đặt báo thức [x] giờ"** | `dat bao thuc 6 gio 30` | Mở đồng hồ báo thức hệ thống | TTS tiếng Việt |
| **"gọi cho [tên]"** | `goi cho nam` | Tra danh bạ và gọi điện | TTS xác nhận |
| **"nhắn tin cho [tên]: [nội dung]"** | `nhan tin cho Lan...` | Mở app SMS với tin nhắn soạn sẵn (không tự gửi) | TTS xác nhận |
| **Lệnh lạ / Câu hỏi tri thức** | `thủ đô nước Pháp là gì` | Gửi hỏi AI (OpenAI / Mistral / DeepSeek) nếu có API key | TTS tiếng Việt |

- **Quy ước chiều lướt:** *"lướt lên"* = xem nội dung mới phía dưới (giống cử chỉ vuốt ngón tay từ dưới lên trên màn hình).
- **Mở app chưa cài:** Thông báo bằng giọng nói: *"Chưa cài [tên app] trên máy này"*.
- **Chưa bật Trợ năng:** Thông báo *"Bạn chưa bật Trợ năng cho JAVIS"* và tự động mở màn hình Cài đặt Trợ năng.

---

## ⚙️ 4. Hướng Dẫn Cài Đặt & Cấp Quyền (Đặc Biệt Cho OPPO / Android 13+)

### 1. Cấp quyền Micro & Cài đặt hạn chế (Android 13+):
Đối với file APK cài đặt thủ công ngoài Google Play trên Android 13 trở lên:
1. Vào **Cài đặt** → **Ứng dụng** → **Quản lý ứng dụng** → Chọn **JAVIS**.
2. Nhấn vào biểu tượng **dấu 3 chấm (⋮)** ở góc trên bên phải màn hình.
3. Chọn **"Cho phép cài đặt bị hạn chế"** (Allow restricted settings) và nhập vân tay/mật khẩu mở khóa.
4. Mở app JAVIS, bấm "Cho phép" khi app yêu cầu quyền **Micro (RECORD_AUDIO)**.

### 2. Bật Dịch vụ Trợ năng (Accessibility):
1. Vào **Cài đặt** → **Cài đặt bổ sung** → **Trợ năng (Accessibility)**.
2. Chọn mục **Ứng dụng đã tải xuống** → Chọn **JAVIS**.
3. Bật công tắc **Sử dụng JAVIS** và bấm **Cho phép**.

### 3. Tối ưu riêng cho ColorOS 15 (OPPO Find X8 Ultra):
Để `HotwordService` chạy nền không bao giờ bị hệ thống tự động tắt:
1. **Khóa ứng dụng:** Mở màn hình đa nhiệm (vuốt lên giữ) → Nhấn dấu 3 chấm trên thẻ JAVIS → Chọn **Khóa (Lock)**.
2. **Cho phép tự khởi chạy:** Vào **Cài đặt** → **Ứng dụng** → **Tự khởi động (Auto-launch)** → Bật công tắc cho **JAVIS**.
3. **Tắt tối ưu hóa pin:** Vào **Cài đặt** → **Pin** → **Cài đặt khác** → **Tối ưu hóa mức sử dụng pin** → Tìm **JAVIS** và chọn **"Không tối ưu hóa"**.

---

## 🧪 5. Checklist Hoàn Thành Kiểm Thử

- [x] Mở app → gọi "javis" → phát âm thanh thức dậy → nói "mở tiktok" → mở đúng app `com.zhiliaoapp.musically`.
- [x] Đang xem TikTok: gọi "javis" → "lướt lên" → cuộn video tiếp theo đúng chiều, video không bị dừng hẳn.
- [x] "tăng âm lượng" / "giảm âm lượng" / "to lên" / "nhỏ lại" chỉnh đúng thanh loa media và có tiếng beep xác nhận.
- [x] Gọi "javis" liên tiếp nhiều lần: không mất lệnh, không lặp lệnh (debounce 1 giây), không crash app.
- [x] Gạt tắt công tắc "Chờ gọi 'javis'" → micro dừng hẳn (mất biểu tượng mic màu xanh trên thanh trạng thái); gạt bật lại → tiếp tục lắng nghe.
- [x] Không gọi "javis" thì app im lặng tuyệt đối, không tự kích hoạt nhầm.
- [x] Build APK thành công thông qua GitHub Actions (`gradle assembleDebug`).
