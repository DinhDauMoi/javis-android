# JAVIS — Checklist Test 5 Lệnh Liên Tục

> **Mục đích**: Xác nhận toàn bộ luồng nghe liên tục, TTS không đè micro, debounce, đúng chiều scroll.  
> **Điều kiện tiên quyết**: App đang chạy, Trợ năng đã bật, đang mở TikTok ở nền.

---

## Chuẩn bị

- [ ] Cài APK build mới nhất
- [ ] Bật **Trợ năng JAVIS** trong Cài đặt → Trợ năng → JAVIS (badge xanh xuất hiện)
- [ ] Mở TikTok, để một video đang chạy, rồi chuyển về JAVIS app
- [ ] Quan sát: badge mic đang nhấp nháy (đang nghe) — nếu không, bấm nút mic

---

## ✅ Bài Test 1 — Mở YouTube (lệnh có dấu)

**Nói:** `"mở YouTube"`

| Kỳ vọng | Kết quả |
|---|---|
| TTS đọc "Đang mở YOUTUBE" | ☐ Đúng / ☐ Sai |
| Mic tự tắt trong khi TTS nói | ☐ Đúng / ☐ Sai |
| Mic tự bật lại sau khi TTS xong | ☐ Đúng / ☐ Sai |
| YouTube mở đúng app | ☐ Đúng / ☐ Sai |

---

## ✅ Bài Test 2 — Mở TikTok (lệnh không dấu)

**Nói:** `"mo tik tok"` hoặc `"mo tiktok"`

| Kỳ vọng | Kết quả |
|---|---|
| TTS đọc "Đang mở TIKTOK" | ☐ Đúng / ☐ Sai |
| TikTok mở đúng (package: com.zhiliaoapp.musically) | ☐ Đúng / ☐ Sai |
| Không crash khi TikTok đã mở sẵn | ☐ Đúng / ☐ Sai |

---

## ✅ Bài Test 3 — Lướt lên (nội dung mới phía dưới)

**Điều kiện:** Đang xem TikTok, video đang phát

**Nói:** `"lướt lên"` hoặc `"luot len"` hoặc `"tiếp theo"`

| Kỳ vọng | Kết quả |
|---|---|
| TikTok chuyển sang VIDEO MỚI (không phải video trước) | ☐ Đúng / ☐ Sai |
| TTS đọc "Đã lướt lên" | ☐ Đúng / ☐ Sai |
| Mic tự resume sau TTS (không cần bấm nút) | ☐ Đúng / ☐ Sai |

> **Ghi chú chiều scroll**: "lướt lên" = ngón tay vuốt từ dưới lên = xem nội dung tiếp theo bên dưới.

---

## ✅ Bài Test 4 — Tăng âm lượng

**Nói:** `"tăng âm lượng"` hoặc `"tang am luong"` hoặc `"to lên"`

| Kỳ vọng | Kết quả |
|---|---|
| Thanh âm lượng hệ thống tăng lên | ☐ Đúng / ☐ Sai |
| TTS đọc "Đã tăng âm lượng" | ☐ Đúng / ☐ Sai |
| Mic tự resume sau TTS | ☐ Đúng / ☐ Sai |

---

## ✅ Bài Test 5 — Giảm âm lượng (ngay sau Test 4, không bấm gì thêm)

**Nói ngay sau khi TTS Test 4 xong:** `"giảm âm lượng"` hoặc `"giam am"` hoặc `"nhỏ lại"`

| Kỳ vọng | Kết quả |
|---|---|
| Thanh âm lượng hệ thống giảm xuống | ☐ Đúng / ☐ Sai |
| TTS đọc "Đã giảm âm lượng" | ☐ Đúng / ☐ Sai |
| Không nghe lại giọng TTS của Test 4 qua mic | ☐ Đúng / ☐ Sai |
| Mic vẫn tiếp tục nghe sau Test 5 (không cần restart app) | ☐ Đúng / ☐ Sai |

---

## 🔁 Bonus: Test Debounce

Nói `"lướt lên"` **hai lần liên tiếp** trong vòng 1 giây:

| Kỳ vọng | Kết quả |
|---|---|
| Chỉ thực hiện lệnh 1 lần (không lướt 2 lần) | ☐ Đúng / ☐ Sai |
| Log chat chỉ hiển thị 1 tin nhắn | ☐ Đúng / ☐ Sai |

---

## 🐛 Ghi chú lỗi (nếu có)

| # | Lỗi gặp phải | Bước tái hiện |
|---|---|---|
| 1 | | |
| 2 | | |

---

## Tóm tắt kết quả

- Tổng test: **5 bài chính + 1 bonus**
- Số test pass: _____ / 6
- Ngày test: ______________
- Phiên bản APK: ______________
