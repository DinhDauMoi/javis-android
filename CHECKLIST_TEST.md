# JAVIS v4 — Checklist Test Từ Khóa Wake Word & Lướt TikTok

> **Mục đích**: Xác nhận toàn bộ chu trình Wake Word ("javis") bằng openWakeWord, không dừng video TikTok, phản hồi beep 100ms, debounce 1 giây, và nút gạt bật/tắt detector.  
> **Điều kiện tiên quyết**: Đã cài APK v4, đã cấp quyền Micro, đã bật Dịch vụ Trợ năng JAVIS.

---

## Chuẩn bị thiết bị (OPPO Find X8 Ultra / Android)

- [ ] Cài đặt APK v4 mới nhất.
- [ ] Bật **Trợ năng JAVIS** trong Cài đặt → Cài đặt bổ sung → Trợ năng → JAVIS.
  *(Lưu ý Android 13+: nếu bị chặn, vào Cài đặt → Ứng dụng → JAVIS → ⋮ → "Cho phép cài đặt hạn chế" rồi bật lại Trợ năng).*
- [ ] Cho phép JAVIS **Tự khởi động** và chọn **Không tối ưu hóa pin** trong Cài đặt pin ColorOS.
- [ ] Mở JAVIS app: Thấy công tắc **"Chờ gọi 'javis'"** đang BẬT, trạng thái ghi *"Đang chờ gọi 'javis'..."*.

---

## ✅ Bài Test 1 — Gọi "javis" mở TikTok
1. Mở app JAVIS.
2. Nói rõ: `"javis"` (hoặc `"Hey Jarvis"`).
3. **Kỳ vọng:** App phát âm thanh thức dậy (wake beep / "dạ") và chuyển trạng thái *"Đang nghe lệnh (6s)..."*.
4. Nói tiếp: `"mở tiktok"` (hoặc `"mo tik tok"`).
5. **Kỳ vọng:** App phát tiếng beep 100ms và mở đúng ứng dụng TikTok (package `com.zhiliaoapp.musically`).

---

## ✅ Bài Test 2 — Đang phát video TikTok: Gọi "javis" lướt lên
1. Khi video TikTok đang phát bình thường.
2. Không chạm tay vào màn hình, nói: `"javis"`.
3. Nghe tiếng beep thức dậy, nói ngay: `"lướt lên"` (hoặc `"vuốt lên"`).
4. **Kỳ vọng:**
   - TikTok tự động cuộn chuyển sang VIDEO TIẾP THEO (nội dung mới phía dưới).
   - Video không bị dừng hẳn / không bị giật lag focus.
   - JAVIS phát tiếng beep xác nhận 100ms.
   - JAVIS tự động ngủ tiếp và quay lại chờ từ khóa `"javis"`.

---

## ✅ Bài Test 3 — Lướt video trước đó
1. Đang xem video TikTok, nói: `"javis"`.
2. Sau tiếng beep, nói: `"lướt xuống"` (hoặc `"vuốt xuống"`).
3. **Kỳ vọng:** TikTok cuộn ngược lại video trước đó phía trên.

---

## ✅ Bài Test 4 — Chỉnh âm lượng loa Media
1. Đang mở video, nói: `"javis"` → `"to lên"` (hoặc `"tăng âm lượng"`).
   - **Kỳ vọng:** Thanh âm lượng media tăng lên 1 nấc + tiếng beep 100ms.
2. Nói tiếp: `"javis"` → `"nhỏ lại"` (hoặc `"giảm âm lượng"`).
   - **Kỳ vọng:** Thanh âm lượng media giảm xuống 1 nấc + tiếng beep 100ms.

---

## ✅ Bài Test 5 — Gọi liên tiếp 5 lần không crash & Debounce 1 giây
1. Gọi 5 lần liên tiếp (mỗi lần 1 lệnh sau khi lệnh trước hoàn thành):
   - Lần 1: `"javis"` → `"lướt lên"`
   - Lần 2: `"javis"` → `"to lên"`
   - Lần 3: `"javis"` → `"lướt lên"`
   - Lần 4: `"javis"` → `"nhỏ lại"`
   - Lần 5: `"javis"` → `"mở youtube"`
2. **Kỳ vọng:** Cả 5 lần đều nhận diện chính xác, không mất lệnh, không lặp đôi lệnh, không crash ứng dụng.
3. **Test Debounce:** Nói liền `"lướt lên"` 2 lần trong 1 giây → app chỉ thực hiện 1 lần duy nhất.

---

## ✅ Bài Test 6 — Bật/Tắt công tắc "Chờ gọi 'javis'"
1. Vào app JAVIS, gạt tắt công tắc **"Chờ gọi 'javis'"**.
2. **Kỳ vọng:**
   - Trạng thái hiển thị *"Đã tắt chờ gọi 'javis'"*.
   - Micro dừng hẳn, biểu tượng chấm tròn / icon micro màu xanh lá của hệ thống trên thanh trạng thái biến mất hoàn toàn.
   - Gọi "javis" → app im lặng tuyệt đối, không phản ứng gì.
3. Gạt bật lại công tắc:
   - Trạng thái quay về *"Đang chờ gọi 'javis'..."*.
   - Gọi "javis" → thức dậy và nghe lệnh bình thường.

---

## ✅ Bài Test 7 — Nói liền 1 câu có từ khóa
1. Nói câu dài: `"javis lướt lên"` hoặc `"ê javis lướt lên dùm"`.
2. **Kỳ vọng:** Parser tự động tách bỏ từ khóa `"javis"` và từ đệm `"dùm"`, thực hiện đúng lệnh cuộn trang.
