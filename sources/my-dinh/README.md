# Bộ SVG Mỹ Đình để test import v2

Sơ đồ minh họa dựa trên ảnh tham khảo, không phải bản vẽ đo đạc hay sức chứa thật.
Cả ba file có viewBox 0 0 1600 1200. SVG không có chữ vì allowlist backend không hỗ trợ text.

1. Upload my-dinh-overview.svg làm OVERVIEW (4 cổng, 5 AREA).
2. Tạo layout FOOTBALL, upload riêng my-dinh-football.svg: 8 zone SEATED, 384 ghế.
3. Tạo layout CONCERT, upload riêng my-dinh-concert.svg: 6 zone SEATED, 288 ghế và 3 zone STANDING, tổng sức chứa đứng 540.
4. Dùng lại cùng overview cho hai layout. Không gộp hai file detail vào một layout.
5. Preview, kiểm tra mapping tự động, hoàn tất và publish từng layout; tạo sự kiện/suất và giá zone để test đặt vé.

A phía dưới, B phía trên, C bên trái, D bên phải như ảnh tham khảo. B đóng trong concert vì nằm sau sân khấu. VIP-L/VIP-R/CAT1 nằm trong AREA FIELD; zone khán đài tự map về AREA A/C/D tương ứng.
