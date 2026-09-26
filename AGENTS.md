# Agent Rules & Execution Policy

## Chế độ Tự Động Toàn Diện (Always-Proceed Mode)
1. **Thực thi trực tiếp, không hỏi xác nhận**:
   - Khi nhận yêu cầu từ người dùng, tự động phân tích, sửa code, tạo file, cấu hình và thực hiện toàn bộ các bước từ đầu đến cuối.
   - Không dùng công cụ hỏi (ask_question) để xin xác nhận các bước hiển nhiên hoặc thông thường.
   - Luôn chủ động hoàn thành trọn gói nhiệm vụ và báo cáo kết quả rõ ràng.

2. **Tiêu chuẩn chất lượng dự án JAVIS**:
   - Tuân thủ nghiêm ngặt PROMPT v4: Wake word "javis" bằng openWakeWord, tối ưu không dừng TikTok, phản hồi beep 100ms.
   - Giữ nguyên các tính năng cũ (Accessibility, Quick Settings Tile, gọi điện, nhắn tin, AI).
   - Mọi thông báo hiển thị và giọng nói hướng tới người dùng đều bằng tiếng Việt.
