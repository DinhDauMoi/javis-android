# PROMPT v4: Wake word "javis" bằng openWakeWord (free, không cần tài khoản) + fix TikTok dừng video

Copy toàn bộ prompt này paste vào AI (ChatGPT / Claude / Gemini...) để nó viết/sửa code app cho bạn. Đây là bản nâng cấp từ app JAVIS đã có (skeleton Kotlin đã build được APK, prompt v3 wake word). Đổi từ Porcupine sang openWakeWord vì Picovoice Console chặn email cá nhân khi đăng ký và sắp bỏ free tier — openWakeWord open-source, không cần tài khoản, không API key.

Tôi đã có app Android JAVIS (trợ lý giọng nói tiếng Việt, Kotlin, minSdk 26, targetSdk 34, điều khiển OPPO Find X8 Ultra). Bản hiện tại nghe liên tục bằng SpeechRecognizer nhưng gặp vấn đề: khi nghe liên tục, video TikTok bị dừng. Tôi muốn đổi sang cơ chế wake word. KHÔNG dùng thư viện trả phí.

## 1. Yêu cầu QUAN TRỌNG NHẤT: từ đánh thức "javis"

Tôi muốn thao tác kiểu: đang xem TikTok, tôi gọi "javis" → nó thức dậy (kêu "dạ" hoặc beep) → tôi nói "lướt lên" → nó lướt → rồi nó ngủ tiếp chờ gọi. Không chạm tay vào máy.

Cách làm (dùng openWakeWord — open-source Apache 2.0, on-device, không cần tài khoản, không cần API key, không cần mạng, không gửi audio đi đâu):

- **Thêm dependency qua JitPack** (port Kotlin của openWakeWord, chạy ONNX Runtime trên máy):
  `implementation("com.github.msnilsen:openwakeword-android:0.1.0")`
- **Viết class HotwordManager** (chạy foreground service để không bị kill):
  - Nghe nền liên tục, chờ từ đánh thức. Hai lựa chọn (làm theo thứ tự ưu tiên):
    1. **Nhanh (không cần train):** dùng model built-in `HEY_JARVIS` ("Hey Jarvis"). Người Việt đọc "javis" ≈ "jarvis" nên model này bắt được luôn.
    2. **Chuẩn:** train model riêng cho từ "javis" bằng Colab notebook của openWakeWord (free GPU, ~30–60 phút, xuất file `.onnx`), bỏ file vào `assets/`, load bằng `setModelAsset("javis.onnx")`.
  - **Khi bắt được wake word:** phát beep ngắn (hoặc TTS "dạ") → bật SpeechRecognizer nghe đúng 1 lệnh (timeout 6 giây, locale `vi-VN`) → xử lý lệnh → quay lại chờ wake word.
  - Wake word engine tạm dừng khi SpeechRecognizer/TTS đang chạy, xong thì bật lại (tránh tự kích hoạt và đè mic).
- **Thêm nút bật/tắt "Chờ gọi 'javis'" trên UI**, mặc định BẬT. Tắt → dừng hẳn detector.
- Xin quyền `RECORD_AUDIO` lúc mở app lần đầu; nếu bị từ chối thì báo bằng giọng nói.
- Mọi lỗi (mất file model, service null) đều không crash — báo bằng giọng nói + ghi log lên màn hình.

## 2. Giảm thiểu TikTok dừng video (audio focus)

Nguyên tắc: khi nghe (hotword hay lệnh) thì KHÔNG request audio focus. Chỉ xin focus khi TTS nói, và phải đúng cách:

- TTS dùng `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (không giật focus hẳn của app khác), và abandon focus ngay trong `UtteranceProgressListener.onDone` / `onError`.
- TTS và micro không đè nhau: khi TTS nói thì dừng SpeechRecognizer; TTS xong mới nghe tiếp. (Nếu không app sẽ nghe nhầm chính giọng của nó.)
- Phản hồi sau lệnh: chỉ beep ngắn 100ms khi nhận đúng lệnh; TTS chỉ dùng cho trường hợp lỗi, câu hỏi, hoặc AI trả lời. Mục đích: càng ít TTS càng ít tranh focus với TikTok/YouTube.
- **LƯU Ý TRUNG THỰC (ghi vào README):** wake word vẫn giữ mic ở mức hệ thống. Nếu TikTok dừng vì "mic bận" (chứ không phải vì audio focus) thì đó là giới hạn của Android, app thường không bypass được — phải test thực tế trên máy mới kết luận. Code trên đã làm tối đa những gì có thể (không giữ focus, TTS tối thiểu).

## 3. Config sẵn các câu lệnh (không cần user tự thêm)

Parser phải nhận cả có dấu và không dấu ("tăng âm lượng" = "tang am luong"), chịu được từ thừa ("ê javis lướt lên dùm" — lưu ý user có thể nói luôn "javis lướt lên" trong 1 câu; parser phải tách được wake word khỏi phần lệnh). Mỗi lệnh đúng thì beep xác nhận:

| Câu lệnh (trigger) | Hành động |
|---|---|
| "lướt lên", "vuốt lên", "cuộn lên" | Scroll nội dung tiếp theo: thử ACTION_SCROLL_FORWARD qua AccessibilityService, fallback vuốt từ dưới lên giữa màn hình (`dispatchGesture`) |
| "lướt xuống", "vuốt xuống", "cuộn xuống" | Ngược lại: ACTION_SCROLL_BACKWARD, fallback vuốt từ trên xuống |
| "mở youtube" | Mở package `com.google.android.youtube` |
| "mở tiktok", "mở tik tok" | Mở package `com.zhiliaoapp.musically` |
| "tăng âm lượng", "to lên", "âm lượng to lên" | `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE, FLAG_SHOW_UI)` |
| "giảm âm lượng", "nhỏ lại", "nhỏ xuống", "âm lượng nhỏ xuống" | `ADJUST_LOWER` tương tự |

- **Quy ước chiều lướt:** "lướt lên" = xem nội dung mới phía dưới (giống vuốt ngón tay từ dưới lên). Ghi chú rõ trong code.
- **Mở app:** dùng đúng package name ở trên, không đoán mò. Nếu app chưa cài → nói "Chưa cài [tên app] trên máy này".

## 4. Fix các lỗi nhỏ

1. **TTS đè micro** → làm như mục 2 (dừng nghe khi đang nói).
2. **Lệnh bị kích hoạt 2 lần** (do onResults + onPartialResults hoặc restart nhanh): thêm debounce — bỏ qua kết quả trùng nội dung trong vòng 1 giây.
3. **"Mở youtube/tiktok" không mở app** → dùng package name chính xác ở bảng trên; thêm fallback: nếu `getLaunchIntentForPackage` null thì quét `packageManager` tìm app theo tên gần đúng.
4. **Chưa bật Trợ năng mà ra lệnh lướt** → nói "Bạn chưa bật Trợ năng cho JAVIS" và tự mở màn hình Cài đặt → Trợ năng cho user bật (dùng `Settings.ACTION_ACCESSIBILITY_SETTINGS`).
5. **Mọi lỗi (mất mạng, service null, text rỗng) đều không được crash** — báo bằng giọng nói + ghi log lên màn hình.

## 5. Giữ nguyên từ bản cũ (đừng làm mất)

- `JavisAccessibilityService` (scroll, swipe, tap, click theo text, back, home).
- `JavisTileService` (ô Quick Settings → mở app + nghe ngay 1 lệnh).
- Các lệnh cũ: mở app khác (zalo, facebook, chrome, camera, cài đặt...), bật/tắt wifi-bluetooth-đèn pin, "mấy giờ rồi", hẹn giờ, đặt báo thức, gọi điện/nhắn tin (có xác nhận trước khi gọi/gửi), AI trả lời khi có API key (gợi ý: API Mistral free tier tương thích OpenAI, https://api.mistral.ai/v1).
- Lệnh tùy chỉnh do user thêm trong Cài đặt, lưu persistent, ưu tiên khớp trước lệnh mặc định.
- Comment tiếng Việt ở hàm quan trọng; mọi text user thấy đều tiếng Việt.

## 6. Quyền & lưu ý (ghi vào README tiếng Việt)

- `RECORD_AUDIO` (runtime).
- Accessibility (bật thủ công; lưu ý Android 13+ chặn app cài ngoài CH Play → hướng dẫn: Cài đặt → Ứng dụng → JAVIS → dấu ⋮ → "Cho phép cài đặt hạn chế" rồi mới bật Trợ năng được).
- ColorOS/OPPO: cho app tự khởi động + tắt tối ưu pin để service hotword nền không bị kill.
- Wake word dùng openWakeWord: không cần đăng ký tài khoản, không API key, không mạng. (Không dùng Picovoice/Porcupine vì console của họ chặn email cá nhân khi đăng ký và sắp bỏ free tier.)
- Muốn từ "javis" chuẩn xác: train model `.onnx` bằng Colab notebook của openWakeWord (free GPU) rồi bỏ vào `assets/`; dùng tạm model built-in "Hey Jarvis" cũng được vì người Việt phát âm "javis" ≈ "jarvis".
- Ghi rõ lưu ý trung thực về TikTok ở mục 2 (test thực tế mới kết luận).

## 7. Checklist hoàn thành

- [ ] Mở app → gọi "javis" → beep/"dạ" → nói "mở tiktok" → mở đúng app
- [ ] Đang xem TikTok (video đang phát): gọi "javis" → "lướt lên" → đổi video đúng chiều, video không bị dừng hẳn (test thực tế, ghi nhận kết quả)
- [ ] "tăng âm lượng" / "giảm âm lượng" chỉnh đúng loa media
- [ ] Gọi "javis" 5 lần liên tiếp, mỗi lần 1 lệnh: không mất lệnh, không lặp lệnh, không crash
- [ ] Tắt nút "Chờ gọi 'javis'" → micro dừng hẳn (mất icon mic); bật lại → nghe tiếp
- [ ] Không gọi "javis" thì app im lặng tuyệt đối (không tự kích hoạt)
- [ ] Build APK thành công qua GitHub Actions như cũ
