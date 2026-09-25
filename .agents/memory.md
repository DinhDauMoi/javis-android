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
