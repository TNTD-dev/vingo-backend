# Luồng admin tạo sự kiện

## Trạng thái

Quy tắc phạm vi đầu tiên đã chốt tại mục 2.2 của [checklist admin](admin-completion-checklist.md#22-chốt-quy-tắc-quản-lý-sự-kiện). Sân, layout và API đọc SVG nhiều lớp đã có trên local. Các mục 2.3 trở đi theo dõi phần triển khai và nghiệm thu còn lại.

## 1. Nhập tên sự kiện, chọn sân và loại sự kiện

- Nhập tên sự kiện đầu tiên; tên này được giữ xuyên suốt các bước tiếp theo.
- Chọn sân, sau đó chọn loại sự kiện FOOTBALL hoặc CONCERT. Loại sự kiện là lựa chọn riêng, không thay bằng tên layout.
- Sau khi chọn loại, tải danh sách seat_layout PUBLISHED thuộc đúng sân và có layout_type khớp loại đã chọn.
- Có một layout phù hợp: tự chọn và tải preview nền, zone, ghế và cổng của đúng phiên bản layout.
- Có nhiều layout phù hợp: hiển thị tên/version để admin chọn; không tự đoán một layout bất kỳ. Sau khi chọn mới dùng layout đó để preview và cấu hình.
- Không có layout phù hợp: hiển thị trạng thái thiếu cấu hình và liên kết quản lý sân để thêm/phát hành layout; không dùng layout khác loại hoặc bản DRAFT thay thế.
- Đổi sân hoặc loại sự kiện phải tải lại layout phù hợp, xóa lựa chọn zone và cấu hình phụ thuộc không còn hợp lệ sau khi admin xác nhận.
- FOOTBALL: mặc định và giới hạn một showtime cho event đại diện một trận đấu.
- CONCERT: form bắt đầu với một suất, cho thêm hoặc hủy suất. Khi công bố và trong lúc đã công bố, phải còn ít nhất một suất chưa hủy. Bản nháp có thể chưa có suất đã lưu.
- Phạm vi đầu tiên: một event chọn một sân và một layout; mọi showtime của event sử dụng layout đó. Hỗ trợ mỗi suất một layout khác là mở rộng riêng.

## 2. Cấu hình zone và giá ngay trên sơ đồ

Admin tương tác trực tiếp với các zone, trước khi nhập thông tin còn lại của sự kiện.

### Chọn zone

- Click để chọn một zone; chế độ chọn nhiều cho phép bật/tắt từng zone và có nút bỏ chọn tất cả.
- Trên điện thoại có thao tác chọn nhiều rõ ràng, không phụ thuộc phím Ctrl/Shift.
- Panel cho biết số zone đã chọn, tên/mã từng zone và giá hiện tại. Zone có dữ liệu khác nhau hiển thị trạng thái hỗn hợp.
- Trạng thái được chọn dùng viền/đánh dấu riêng để không nhầm với màu giá vé.

### Gán giá hàng loạt

- Nhập giá VND nguyên không âm và áp dụng cho toàn bộ zone đã chọn trong một lần.
- Có thể tạo/chọn hạng vé có tên hiển thị, nhưng màu nhóm theo giá trị giá vé trong event.
- Các zone có cùng giá phải có chung màu, kể cả được cấu hình qua nhiều lượt chọn khác nhau hoặc thuộc các hạng vé có tên khác nhau.
- Cùng một giá luôn dùng màu ổn định trong phiên cấu hình và sau khi lưu/tải lại. Màu cần lưu hoặc ánh xạ ổn định phía backend, không chọn ngẫu nhiên mỗi lần render.
- Đổi giá một zone chỉ đổi nhóm màu của zone đó; đổi giá một hạng vé phải hiển thị rõ các zone cùng hạng sẽ bị ảnh hưởng trước khi áp dụng.
- Zone chưa được cấu hình dùng màu/trạng thái riêng; không tự xem như vé giá 0.
- Có chú giải giá và màu; vẫn hiển thị giá dạng chữ để không phụ thuộc riêng vào màu sắc.

### Đặt tên zone

- Tên hiển thị cho event được lưu như cấu hình của event–zone; không đổi mã zone, ghế, hình dạng hoặc tên zone gốc của layout PUBLISHED.
- Chưa có tên theo event thì mặc định dùng tên zone từ layout; admin có thể đổi thành VIP A, Khán đài B, Khu đứng…
- Khi chọn nhiều zone, thao tác giá áp dụng chung. Tên từng zone được sửa trong danh sách cùng panel, có thể nhập tên nhóm và sinh tên riêng theo mã zone; không tự ghi đè tất cả bằng một tên làm mất phân biệt khu.
- Tên theo event phải dùng nhất quán trên preview, trang mua vé, đơn hàng và vé/email. Không chỉ đổi nhãn ở UI admin.
- Không thay đổi capacity hoặc danh sách ghế ở bước giá/tên. Bố trí mới phải tạo layout mới trong quản lý sân.

## 3. Nhập thông tin còn lại của event và showtime

Sau khi sơ đồ và giá được cấu hình, nhập:

- Mô tả, ảnh và thông tin theo loại sự kiện; tên đã nhập từ bước đầu, có thể quay lại chỉnh sửa.
- Giới hạn tuổi, số vé tối đa mỗi đơn và các thông tin cần thiết khác.
- Thời gian bắt đầu/kết thúc và thời gian mở/đóng bán vé cho từng showtime.
- FOOTBALL giữ một suất; CONCERT được thêm/xóa suất theo quy tắc trên.

Giá và tên zone trong phạm vi đầu tiên áp dụng chung cho mọi showtime của event. Giá khác theo từng suất cần yêu cầu/mô hình riêng.

## 4. Xem lại, lưu nháp và công bố

- Hiển thị tên sự kiện, sân/loại/layout, sơ đồ có màu giá và tên zone, thông tin event, số suất và thời gian bán.
- Cho phép quay lại bước trước mà không mất cấu hình.
- Đổi sân/loại/layout sau khi đã cấu hình phải xác nhận, xóa mapping giá/tên và lựa chọn zone không còn phù hợp; không ghép theo tên zone để tái sử dụng nhầm.
- Lưu nháp tối thiểu với tên không rỗng và loại FOOTBALL hoặc CONCERT. Nếu chưa nhập giới hạn vé mỗi đơn, dùng mặc định 10. Sân, layout, zone/giá, mô tả, ảnh, thông tin theo loại và suất có thể chưa đầy đủ ở trạng thái DRAFT. Chỉ gửi bản ghi suất khi đủ dữ liệu bắt buộc của suất đó.
- Khi công bố, phải có sân hoạt động, layout cùng sân và cùng loại đã phát hành, giá hợp lệ cho tất cả zone và đủ số suất hợp lệ. FOOTBALL có đúng một suất chưa hủy. CONCERT có ít nhất một suất chưa hủy. Kiểm tra các mốc giờ bán và giờ diễn trước khi công bố.
- Giai đoạn đầu bắt buộc gán giá cho mọi zone. Thiếu giá chặn công bố. Giá 0 VND là vé miễn phí đã được cấu hình. Chưa có trạng thái khu không bán theo event; khóa ghế theo suất tiếp tục là thao tác riêng.
- Khi còn DRAFT và chưa có giao dịch, có thể đổi sân, loại, layout, giá, tên zone và lịch. Đổi sân, loại hoặc layout phải xác nhận và xóa cấu hình zone không còn thuộc layout mới. Mốc giờ mở bán của bản nháp đã qua không khóa chỉnh sửa.
- Sau công bố, giữ cố định sân, loại và layout. Trước khi bất kỳ suất nào bắt đầu bán và khi chưa có đơn/vé, có thể sửa giá, tên zone và giờ diễn/giờ bán trong phạm vi các mốc hợp lệ. Khi đã bắt đầu bán hoặc có bất kỳ đơn/vé nào, khóa cấu hình giá, tên zone và giờ. Sự kiện đã kết thúc hoặc đã hủy không cho sửa. Việc sửa nội dung mô tả không được thay đổi thông tin vé đã phát hành.
- Dùng thời gian để xác định đóng bán và kết thúc suất. Suất qua giờ kết thúc hiển thị FINISHED; sự kiện kết thúc khi mọi suất chưa hủy đã kết thúc. Không thêm thao tác kết thúc thủ công trong phạm vi này.
- Admin hủy sự kiện khi chưa có suất nào bắt đầu bán hoặc có đơn/vé. Admin hủy suất riêng trước khi suất đó bắt đầu bán và khi suất đó chưa có đơn/vé. Nếu việc hủy làm sự kiện đã công bố không còn suất hợp lệ, dùng thao tác hủy cả sự kiện nếu đủ điều kiện. Hủy lưu trạng thái, người thực hiện và lý do. Giữ ID và lịch sử. Sau khi bán hoặc có giao dịch, xử lý theo quy trình vận hành hiện có; không tự hoàn tiền, không xóa cứng.

## 5. Ảnh hưởng DB/API

- Tiếp tục sử dụng price_tier và zone_price cho giá theo event; rà lại constraint và API để thao tác hàng loạt chạy cùng transaction.
- Bổ sung tên hiển thị theo event–zone (ví dụ trường display_name trong cấu hình event–zone); schema cuối cần chốt trong contract/migration.
- Màu theo giá trong cùng event: cần quy tắc nhất quán dù nhiều price_tier có cùng giá. Không mặc định tier khác nhau được màu khác nhau.
- API nhận danh sách zone_id, kiểm tra tất cả zone thuộc layout event đã chọn; có zone sai thì từ chối toàn bộ cập nhật.
- Không nhận giá trị tiền kiểu số thực; không tin giá do buyer gửi khi đặt vé.
- Khi đặt vé, chốt chính sách snapshot tên zone/hạng/giá vào dữ liệu đơn/vé để chỉnh tên sau này không làm sai lịch sử.

## 6. Checklist

- [ ] Chốt schema event chọn sân/layout và giới hạn showtime theo layout_type.
- [ ] Xây thứ tự bắt buộc: tên sự kiện → sân → loại → layout phù hợp/preview → zone/giá → thông tin còn lại.
- [ ] Kiểm tra lọc layout đúng sân, đúng loại và PUBLISHED; xử lý đủ trường hợp không có, có một hoặc nhiều layout phù hợp.
- [ ] Chốt schema tên zone theo event và quy tắc màu dùng chung theo giá.
- [x] Chốt khu không bán, giới hạn giá, lưu nháp và chính sách sửa cấu hình sau công bố/có giao dịch.
- [ ] Thiết kế API cập nhật giá/tên nhiều zone trong một transaction, kèm ví dụ lỗi và quyền ADMIN.
- [ ] Thiết kế snapshot/đọc tên zone nhất quán cho buyer, đơn, vé và email.
- [ ] Xây chọn nhiều zone và panel cấu hình, trạng thái hỗn hợp, chú giải và giá bằng chữ.
- [ ] Xây bước thông tin sự kiện/showtime sau bước giá/tên zone.
- [ ] Kiểm tra đổi sân/loại/layout không giữ nhầm cấu hình cũ và không làm mất tên sự kiện.
- [ ] Kiểm tra cùng giá cùng màu qua nhiều lượt thao tác, lưu/tải lại và nhiều hạng vé trùng giá.
- [ ] Kiểm tra một zone không hợp lệ làm rollback toàn bộ cập nhật nhóm.
- [ ] Kiểm tra sửa tên theo event không thay đổi layout gốc hoặc event khác.
- [ ] Kiểm tra FOOTBALL một suất, CONCERT nhiều suất và tồn ghế riêng theo suất.
- [ ] Chạy kiểm tra backend/frontend và nghiệm thu local/staging trước khi triển khai.

## 7. Chạy luồng mới ở local

Các migration sân v2 đã nằm trong thư mục Flyway chính, API tự áp khi
khởi động (sao lưu DB trước lần đầu). Từ thư mục backend, chạy trong PowerShell:

```powershell
docker compose -f deploy/compose.dev.yaml up -d
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=api,local'
```

Trong terminal của `vingo-web`, chạy `npm.cmd run dev`. Dùng frontend
ở chế độ API thật, đăng nhập ADMIN rồi vào **Sự kiện & suất diễn → Tạo sự kiện**.
Sân Mỹ Đình cần có layout v2 và SVG tổng quan đã phát hành để hiện trong bước
chọn sân. Nếu chỉ có một layout đúng loại, wizard tự chọn. Dùng tab **Giá theo
khu** để gán theo sơ đồ hoặc theo danh sách. Với bóng đá, sau khi tạo suất đầu
tiên, form mở suất đó để xem hoặc sửa.

Chi tiết quản trị trả `venue_id`, `seat_layout_id` và `zone_prices`. Ví dụ
payload cho `PUT /api/v1/admin/events/{event_id}/zone-prices`:

```json
[
  {
    "zone_id": "c315fc28-db7d-462b-82ab-4d37ab2c1c75",
    "price_tier_id": "e22d4b70-82b9-4845-9c0c-b2d333bc8ac6",
    "display_name": "Khán đài A · Tầng 2"
  }
]
```

Request thay toàn bộ mapping. Tham chiếu khu hoặc hạng sai trả
`400 VALIDATION_ERROR` và rollback toàn bộ mapping. Nếu bỏ `display_name`,
tên gốc được dùng. Hạng mới được tạo/cập nhật qua `price-tiers` trước khi ghi
mapping. API tồn vé trả `code` để khớp SVG và `name` để hiển thị theo sự kiện.
Đơn lấy tên khu, tên hạng và giá tại lúc giữ chỗ. Vé và email đọc lại bản chụp
này.

Migration giữ nguyên ID cũ. Sự kiện cũ có một layout v2 đã phát hành, đúng
loại, được điền liên kết sân/layout. Sự kiện cũ có nhiều layout hoặc dùng
schema cũ giữ cách đọc qua từng suất. File SQL nằm trong
`src/main/resources/db/migration`. Khi triển khai, sao lưu PostgreSQL và áp dụng
chuỗi migration cùng bản backend/frontend tương ứng. Nếu cần khôi phục sau
khi ghi dữ liệu mới, dùng bản sao lưu trước migration theo quy trình rollback
của môi trường đích.
