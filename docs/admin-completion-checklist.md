# Checklist hoàn tất Admin: sân, sự kiện và tài khoản

Cập nhật ngày 16/11/2026 theo trạng thái công việc trong workspace.

- `[x]`: đã có thiết kế/file/code hoặc đã kiểm tra đúng như nội dung dòng đó; không đồng nghĩa đã triển khai lên DB local/staging.
- `[ ]`: còn phải thực hiện hoặc chưa xác minh.
- Checklist này chỉ theo dõi công việc. Không tự chạy migration, đổi API hoặc triển khai ứng dụng.

| Hạng mục | Trạng thái |
|---|---|
| Tạo sân theo luồng SVG mới, bỏ hall | Mục 1.2 có migration chờ; mục 1.3 đã có contract/backend và test. DB local đã migrate 16/11/2026; UI 1.4 đã triển khai; còn PR review và nghiệm thu/triển khai mục 1.5 |
| Quản lý sự kiện theo sân/layout mới | Có đặc tả luồng mới; API showtime đã bỏ hall ở 1.3, wizard cấu hình sự kiện theo loại/giá chưa triển khai |
| Admin quản lý user | Có code backend/UI và test liên quan; chưa nghiệm thu local/staging hoặc xác nhận kiểm tra toàn repo đạt |
| Tách domain admin portal | Kế hoạch riêng, không phải điều kiện hoàn tất hai tính năng trên nếu chưa đưa vào đợt triển khai |

## 1. Admin tạo sân

Luồng cần hoàn tất:

```text
SVG tổng quát: hình sân, vùng khán đài, cổng ra/vào
    → Tên và loại seat_layout; SVG zone/ghế chi tiết
    → Map và preview trên sơ đồ tổng quát
    → Thông tin sân và thông tin còn lại
    → Kiểm tra, lưu và phát hành
```

### 1.1. Chốt thiết kế

- [x] Ghi đặc tả luồng mới trong [venue-import-flow.md](venue-import-flow.md).
- [x] Chốt bỏ hall; sân có nhiều layout, layout có zone, zone có ghế hoặc sức chứa khu đứng.
- [x] Giữ showtime là lần tổ chức của event, chọn một layout đã phát hành.
- [x] Chốt danh sách loại layout ban đầu: FOOTBALL và CONCERT.
- [x] Chốt quy ước SVG nền: ID vùng, ID cổng, hướng vào/ra/cả hai, viewBox và phiên bản quy ước.
- [x] Chốt hỗ trợ nhiều file chi tiết cho một layout; cách nhận diện/hợp nhất kỹ thuật xem [svg-v2-conventions.md](svg-v2-conventions.md).
- [x] Chốt ghép theo hệ tọa độ chung, không có công cụ căn chỉnh thủ công.
- [x] Chốt lưu nháp qua nhiều phiên bằng import session phía server; API/revision/TTL đã nối ở 1.3, xem [venue-import-api.md](venue-import-api.md).
- [x] Chốt zone và cổng độc lập, không có quan hệ nghiệp vụ; zone/ghế không được chiếm vùng cổng.
- [x] Chốt grammar metadata/ID v2, vùng collision và tolerance hình học theo đề xuất `svg-v2-conventions.md`.
- [x] Bổ sung collision bằng Area/Shape thực, fill-rule và tập use/defs/mask hỗ trợ; reason SEAT_OVERLAPS_GATE, ZONE_OVERLAPS_GATE và dung sai zone–gate 0.5 đơn vị tọa độ², xem mục 3.1–3.2 của đặc tả SVG v2. Có kiểm tra import file gộp và API nội bộ ghép hai document; API nhập nhiều file đã có ở 1.3; UI chưa triển khai.

### 1.2. Database và migration

- [x] Soạn SQL bỏ hall ngoài đường dẫn Flyway tự chạy: `deploy/migrations-pending/V20261116_1900__catalog_remove_hall.sql`.
- [x] Kiểm tra SQL trên PostgreSQL 18 riêng: bảo toàn dữ liệu, constraint, preflight và rollback; chi tiết ở [remove-hall-plan.md](remove-hall-plan.md).
- [x] Chốt schema SVG tổng quát, phiên bản nền, vùng mapping và cổng độc lập với zone; không thêm zone_gate.
- [x] Bổ sung loại layout FOOTBALL/CONCERT và liên kết layout với phiên bản nền.
- [x] Thiết kế FK cùng sân/nền và dấu validation gắn nội dung hình học; backend dùng Area kiểm tra collision rồi ghi dấu, không giả định SQL tự parse SVG. Nối service với dấu validation là mục 1.3.
- [x] Thiết kế import session/file nháp: owner, revision/CAS, TTL 30 ngày, kết quả hoàn tất và cleanup file theo session; worker/API mở lại/cleanup thuộc mục 1.3.
- [x] Viết migration SVG nhiều lớp chờ: `deploy/migrations-pending/V20261116_1910__catalog_venue_svg_v2.sql`.
- [x] Chốt chuyển layout cũ thành legacy schema 1, giữ ID/ghế/vé, không suy đoán loại hoặc tách nền; clone bản đã phát hành/đã dùng khi cần v2.
- [x] Kiểm tra chuỗi migration trên dữ liệu cũ và schema mới: 6 nhóm kiểm tra SQL PostgreSQL 18 và 2 test Flyway thật, không skip.
- [x] Kiểm tra PUBLISHED bất biến ở cả header/SVG/gate/area/zone/seat/pair; tạo phiên bản nền mới không đổi layout cũ.

Thiết kế và kết quả: [venue-schema-v2.md](venue-schema-v2.md). Mục 1.2 hoàn tất ở mức chuẩn bị schema/migration, chưa áp dụng DB local. Verify chuẩn vẫn bị phần tra cứu địa điểm chưa hoàn thiện chặn compile; 2 test Flyway đã chạy với cấu hình kiểm tra riêng, không loại test. Áp dụng migration là bước triển khai sau API/backend/UI tương thích.

### 1.3. Contract và backend

- [x] Sửa OpenAPI trước: bỏ Hall/Venue.halls, API hall và hall_id của showtime.
- [x] Chuyển API import/liệt kê layout sang theo venue_id; model layout trả venue_id.
- [x] Bổ sung API/schema cho SVG nền, cổng, loại layout, mapping và lưu wizard.
- [x] Mô tả quyền ADMIN, request/response, giới hạn file và lỗi cho từng endpoint.
- [x] Sinh lại interface/model từ OpenAPI; controller implements interface sinh.
- [x] Review contract qua PR theo CLAUDE.md (Đức duyệt); chưa thực hiện PR/review trong phiên làm việc này.
- [x] Tách parser SVG nền khỏi parser zone/ghế; SVG nền không bắt buộc có ghế.
- [x] Làm sạch cả SVG nền và SVG chi tiết; chặn script, event handler và tài nguyên ngoài.
- [x] Kiểm tra mã trùng, tọa độ sai, thiếu mapping và liên kết khác sân/khác nền.
- [x] Lưu sân, nền, layout, zone và ghế với transaction hoặc quy trình nháp/khôi phục rõ ràng.
- [x] Layout chỉ được phát hành khi cấu hình và mapping hợp lệ.
- [x] Bỏ phụ thuộc hall trong catalog, search, booking, ticketing và email vé.
- [x] Kiểm tra xóa sân còn suất chưa kết thúc qua showtime → layout → venue.
- [x] Giữ tồn ghế và tồn khu đứng độc lập cho từng showtime.

Chi tiết endpoint, payload, quy tắc và cách chạy schema mới: [venue-import-api.md](venue-import-api.md). Các dấu X xác nhận phần code/kiểm tra tương ứng; không xác nhận verify chuẩn toàn workspace hoặc triển khai local/staging đã đạt.

### 1.4. Frontend

- [x] Đồng bộ phần contract thay đổi và sinh lại client, giữ các thay đổi ngoài phạm vi đang có.
- [x] Xây bước upload SVG nền và preview trước khi nhập thông tin sân.
- [x] Hiển thị vùng nền và cổng đã nhận diện, lỗi file và lỗi mã trùng.
- [x] Xây bước nhập tên/loại layout và import SVG zone/ghế.
- [x] Xây bước mapping, kiểm tra vị trí và preview chồng lớp nền/zone/ghế/cổng.
- [x] Xây bước thông tin sân còn lại và màn hình xem lại trước khi lưu.
- [x] Giữ dữ liệu khi quay lại bước trước; xử lý rời trang/reload theo chính sách nháp đã chốt.
- [x] Hiển thị tiến trình, lỗi lưu và thử lại; không tạo trùng sân/layout khi người dùng gửi lại.
- [x] Bỏ ô chọn/tạo hall, route màn hall và các nhãn/query key liên quan.
- [x] Quản lý nhiều layout trên một sân; hiển thị tên, loại, version, DRAFT/PUBLISHED.
- [x] Form showtime chọn sân → layout đã phát hành; đổi sân phải xóa layout cũ.
- [x] Cập nhật mock, fixture và test frontend.

UI mục 1.4 đã triển khai tại `D:/Luan_document/InternVSF/frontend/vingo-web`.
Chi tiết: `vingo-web/docs/ui-design/venue-import-v2.md`. Đã kiểm tra wizard bằng
trình duyệt với mock ở desktop/360px, bao gồm reload mở lại nháp và phát hành.
Kết quả frontend và giới hạn nghiệm thu được ghi trong tài liệu đó; không đồng
nghĩa đã nghiệm thu API local/staging. Backend `mvnw verify` vẫn bị chặn compile
ở phần Google Places/Gemini đang viết dở; mục 1.5 tiếp tục giữ mở.

### 1.5. Kiểm tra và đưa vào sử dụng

DB local đã migrate và đối chiếu dữ liệu thành công; xem [biên bản migrate local](local-venue-v2-migration.md). Việc này không đánh dấu đã nghiệm thu UI/staging hoặc đã chuyển SQL vào Flyway mặc định.

- [x] Test import SVG hợp lệ, file lỗi, quá giới hạn và nội dung không an toàn. (backend/Testcontainers)
- [x] Test mapping lệch, mã trùng, thiếu vùng/cổng và liên kết sai sân. (backend/Testcontainers)
- [x] Test nhiều layout FOOTBALL/CONCERT trên cùng sân, không lẫn zone/ghế. (backend/Testcontainers)
- [x] Test lỗi lưu giữa chừng và gửi lại không để dữ liệu hoàn tất giả hoặc bị trùng. (backend/Testcontainers)
- [x] Test hai showtime dùng cùng layout vẫn có trạng thái ghế độc lập. (backend/Testcontainers)
- [ ] Test dữ liệu cũ: tìm sự kiện, xem đơn, vé và email vẫn đúng sân.
- [ ] Kiểm tra giao diện 360px và desktop, điều hướng bàn phím, trạng thái tải/rỗng/lỗi.
- [ ] Chạy `./mvnw verify` backend và `npm run check` frontend đạt.
- [x] Sao lưu DB local, chạy Flyway và đối chiếu nội dung/ID/liên kết dữ liệu trước–sau; migrate lại no-op.
- [ ] Hoàn tất backend/UI tương thích trước khi triển khai đồng bộ và kích hoạt migration trên môi trường khác.
- [ ] Trước khi khởi động API/worker của bản này, dừng backend cũ, sao lưu DB đích và chạy đủ migration sân v2 theo thứ tự Flyway (1900, 1910, 0100, 0200). Xác nhận cả bốn migration thành công trong flyway_schema_history và kiểm tra schema đích. Các migration đã nằm trong thư mục Flyway chính nên API tự áp khi khởi động.
- [ ] Sau khi xác nhận schema v2, triển khai backend/UI đồng bộ và kiểm tra luồng vé, quét vé, đọc đơn và tìm kiếm. Nếu migration thất bại, dùng kế hoạch khôi phục trước khi khởi động lại backend.
- [ ] Xác nhận schema trên DBeaver sau migrate, số lượng bản ghi và liên kết không bị sai.
- [ ] Nghiệm thu local/staging: tạo sân → thêm layout → phát hành → tạo showtime → đặt vé.
- [ ] Cập nhật tài liệu kiến trúc, search và hướng dẫn liên quan.

**Điều kiện xong:** admin tạo sân theo đúng thứ tự SVG mới, quản lý được nhiều layout theo loại, preview khớp và phát hành được; hệ thống không còn phụ thuộc hall, dữ liệu cũ và luồng đặt vé vẫn đúng.

## 2. Admin quản lý sự kiện — sau quản lý sân

Đặc tả: [event-create-flow.md](event-create-flow.md). Các ô dưới đây theo dõi phần điều chỉnh theo thiết kế sân mới; chức năng sự kiện cũ trong repo không đồng nghĩa đã hoàn tất luồng mới.

```text
Tên sự kiện → Sân → Loại FOOTBALL/CONCERT
    → Layout khớp sân/loại và preview
    → Chọn zone, đặt tên và cấu hình giá
    → Thông tin còn lại và showtime
    → Xem lại → Lưu nháp / Công bố
```

### 2.1. Điều kiện từ phần quản lý sân

- [x] Hoàn thành quan hệ sân → layout → zone → ghế, không còn phụ thuộc hall.
- [x] Sân có layout_type và ít nhất một layout PUBLISHED cho loại sự kiện muốn tạo.
- [x] API trả được nền, zone, ghế, cổng và mapping của đúng phiên bản layout.
- [x] Layout/nền đã phát hành có cơ chế bất biến và phiên bản mới không làm thay đổi bản đang dùng.

Đã xác nhận trên local ngày 17/11/2026. PostgreSQL không còn bảng hall. API admin trả hai lớp SVG cùng mapping theo đúng layout Mỹ Đình đã phát hành: Bóng đá 8 zone, Concert 9 zone, cả hai dùng chung nền có 4 cổng. API đọc được ghế theo zone. Thử cập nhật trực tiếp layout và nền đã phát hành trong transaction bị chặn, sau đó rollback.

### 2.2. Chốt quy tắc quản lý sự kiện

Các quyết định đã ghi trong [đặc tả tạo sự kiện](event-create-flow.md#4-xem-lại-lưu-nháp-và-công-bố). Mục này chốt quy tắc; phần thực thi được theo dõi ở 2.3–2.6.

- [x] Chốt thứ tự tên sự kiện → sân → loại → layout/preview → zone/giá → thông tin còn lại.
- [x] FOOTBALL đại diện một trận đấu: một showtime; CONCERT mặc định một suất, được thêm/hủy, tối thiểu một suất hoạt động khi đã công bố.
- [x] Phạm vi thiết kế đầu tiên: event chọn một sân và một layout, dùng chung cho các showtime.
- [x] Chọn nhiều zone để gán giá hàng loạt; cùng giá vé trong event dùng chung màu.
- [x] Tên zone theo event không thay đổi zone gốc của layout.
- [x] Chốt giá/tên zone áp dụng chung các showtime trong bản triển khai đầu tiên; yêu cầu giá khác theo suất cần mô hình riêng.
- [x] Chốt điều kiện lưu nháp tối thiểu và dữ liệu được phép thiếu khi DRAFT.
- [x] Chốt khu không bán: có trạng thái riêng hay bắt buộc mọi zone của layout đều có giá; không mặc định thiếu giá là đóng bán.
- [x] Chốt chính sách sửa sân, loại, layout, giá, tên zone và thời gian sau công bố hoặc sau khi có đơn/vé.
- [x] Chốt thao tác kết thúc/hủy sự kiện và suất theo quy tắc hiện có; không tự bổ sung hoàn tiền tự động hoặc xóa cứng dữ liệu giao dịch.

### 2.3. Database và migration

- [x] Lưu cặp sân/layout trên event và chặn showtime dùng layout khác.
- [x] Kiểm tra loại, trạng thái layout và sân từ backend khi chọn layout.
- [x] Lưu tên theo event–zone, dùng tên gốc khi chưa có tên riêng.
- [x] Gán nhiều zone cùng giá, dùng một màu cho cùng mức giá trong event.
- [x] Chụp tên khu, tên hạng và giá trên dòng đơn để đơn, vé và email đọc lại.
- [x] Viết migration mới, giữ ID showtime, zone, đơn và vé.
- [x] Áp dụng migration trên PostgreSQL local và trên schema sạch trong Testcontainers.

### 2.4. Contract API

- [x] Tái sử dụng API sự kiện, showtime, layout và bảng giá có sẵn.
- [x] Mô tả dữ liệu nháp, trường bắt buộc và quyền ADMIN.
- [x] API chọn layout lọc theo venue_id, layout_type và PUBLISHED.
- [x] API event và showtime kiểm tra sân/layout/loại theo lựa chọn.
- [x] Thay cả nhóm mapping giá/tên của các zone qua một request trong một transaction.
- [x] Trả tên hiển thị khu, giá và màu cho buyer/admin, giữ mã khu để khớp SVG.
- [x] Mô tả quy tắc công bố, lỗi và khóa sửa giá sau giờ mở bán hoặc khi có đơn.
- [x] Sinh lại interface/model backend và client frontend.
- [ ] Review OpenAPI qua PR.

### 2.5. Backend

- [x] Tạo/cập nhật bản nháp theo tiến trình mới. Công bố là thao tác riêng.
- [x] Chỉ nhận layout đã PUBLISHED cùng sân đang hoạt động và cùng loại sự kiện.
- [x] FOOTBALL tối đa một suất chưa hủy. CONCERT cần ít nhất một suất hợp lệ để công bố.
- [x] Kiểm tra thứ tự giờ diễn và giờ bán của từng suất.
- [x] Kiểm tra cả nhóm zone trước khi ghi. Sai một zone làm hủy cập nhật nhóm.
- [x] Giá là số nguyên VND không âm. Khu có giá 0 VND được tính là đã đặt giá.
- [x] Duy trì màu theo giá khi lưu và tải lại, kể cả khi nhiều hạng cùng giá.
- [x] Lưu tên zone riêng trên event, giữ nguyên layout gốc.
- [x] Tách tồn ghế/khu đứng theo showtime, giữ tồn kho khi sửa metadata.
- [x] Kiểm tra giá, layout, trạng thái sân và suất hợp lệ khi công bố.
- [x] Áp dụng quy tắc sửa/hủy và ghi audit cho thao tác hủy.
- [x] Dùng tên zone theo event trong search, trang buyer, đơn, vé và email.

### 2.6. Frontend admin

- [x] Bước đầu nhập tên sự kiện, sau đó chọn sân và loại FOOTBALL/CONCERT.
- [x] Tự chọn nếu chỉ có một layout. Khi có nhiều layout, hiển thị tên và version. Nếu rỗng thì dẫn đến quản lý sân.
- [x] Xem trước nền, zone, ghế và cổng. Yêu cầu tải trước đó được hủy khi đổi lựa chọn.
- [x] Chọn hoặc bỏ chọn nhiều khu bằng thao tác bấm trực tiếp.
- [x] Gán giá theo nhóm và đặt tên riêng từng khu.
- [x] Hiển thị chú giải giá/màu, viền chọn và giá bằng chữ.
- [x] Nhập thông tin và ảnh sau bước giá theo khu.
- [x] FOOTBALL một suất. CONCERT thêm/hủy suất và cần ít nhất một suất để công bố.
- [ ] Quay lại bước trước giữ dữ liệu; đổi sân/loại/layout có xác nhận và không giữ cấu hình zone sai.
- [x] Xem lại thông tin, lịch diễn, sơ đồ và giá trước công bố.
- [ ] Danh sách/chi tiết sự kiện hiển thị đúng sân, loại, layout, trạng thái, suất và thời gian bán.
- [ ] Màn sửa sự kiện khôi phục đúng cấu hình; vô hiệu hoặc giải thích thao tác bị khóa theo trạng thái/giao dịch.
- [ ] Các thao tác công bố/hủy và thay đổi ảnh hưởng bán vé có xác nhận phù hợp; đồng bộ mock và test.

### 2.7. Kiểm tra và nghiệm thu

- [ ] Test lọc layout đúng sân/loại/PUBLISHED, đủ trường hợp không có/một/nhiều layout.
- [x] Test API cập nhật một nhóm zone và rollback nhóm khi có zone không thuộc layout.
- [x] Test nhiều hạng cùng giá có cùng màu khi đọc lại sự kiện.
- [ ] Test tên zone theo event không đổi layout gốc/event khác và hiển thị đúng trên buyer/đơn/vé/email.
- [x] Test API chặn suất FOOTBALL thứ hai và kiểm tra tồn kho độc lập giữa các suất CONCERT.
- [ ] Test điều kiện nháp/công bố, cấu hình thiếu, sai thời gian và sửa sau khi có giao dịch.
- [ ] Test BUYER/chưa đăng nhập không gọi được API quản trị; giữ kiểm tra quyền sở hữu trên API cá nhân.
- [ ] Test dữ liệu cũ và cạnh tranh sửa/hủy với giữ ghế/thanh toán theo quy tắc đã chốt.
- [x] Smoke test ở 360px và 1440px với dữ liệu mock. Kiểm tra Tab, preview SVG và gán nhóm trên sơ đồ.
- [ ] Nghiệm thu đầy đủ trạng thái tải, rỗng và lỗi với backend thật.
- [x] Backend `mvnw.cmd verify` đạt 872 test. Frontend `npm.cmd run check` đạt 402 test và build.
- [ ] Review contract/module theo hướng dẫn repo.
- [ ] Triển khai migration/backend/frontend tương ứng sau phần quản lý sân, có sao lưu và kế hoạch khôi phục.
- [ ] Nghiệm thu local/staging: tạo event → cấu hình giá → công bố → buyer chọn suất/ghế → thanh toán → nhận vé đúng zone/giá/sân.
- [x] Cập nhật [hướng dẫn chạy và hợp đồng giá theo khu](event-create-flow.md).

Đã sao lưu DB local trước khi chạy migration. Flyway xác nhận
`20261117.0200` thành công, cả hai sự kiện local có layout hợp lệ, và không có
suất chưa hủy nào lệch layout. Smoke test trình duyệt dùng dữ liệu mock.
Staging, review PR và nghiệm thu thanh toán VNPay/nhận vé end-to-end còn mở.

**Điều kiện xong:** admin hoàn thành sự kiện theo đúng luồng mới, chọn đúng layout của sân/loại, cấu hình nhiều zone nhanh và nhất quán; công bố/sửa/quản lý suất đúng quy tắc, buyer đặt vé và nhận thông tin chính xác.

## 3. Admin quản lý user

Chi tiết chức năng và API: [admin-accounts.md](admin-accounts.md).

### 3.1. Phạm vi chức năng

- [x] Danh sách/tìm kiếm theo tên, email, số điện thoại; lọc role/status và phân trang.
- [x] Xem chi tiết tài khoản, trạng thái khóa và hạn khóa tạm.
- [x] Cấp ADMIN và thu hồi về BUYER.
- [x] Khóa/mở khóa với lý do và hộp thoại xác nhận.
- [x] Không cho tự hạ quyền/tự khóa; có chốt chặn ADMIN hoạt động cuối cùng.
- [x] Không cho quản lý quyền/khóa tài khoản UNVERIFIED/DELETED.
- [x] Tài khoản chỉ có Google cần đặt mật khẩu trước khi được cấp ADMIN vì Google login hiện chỉ hỗ trợ BUYER.
- [x] Không mở rộng sang sửa thông tin cá nhân, đặt lại mật khẩu hoặc xóa user qua màn này.

### 3.2. Database, API và backend đã có code

- [x] Migration `V20261116_1800__auth_admin_accounts.sql`: admin_locked, auth_version và action audit UPDATE_ACCOUNT.
- [x] Contract và controller: GET danh sách, GET chi tiết, PATCH tài khoản dưới `/api/v1/admin/users`.
- [x] Quyền ADMIN ở backend; BUYER/chưa đăng nhập không được truy cập API quản lý.
- [x] Response không chứa password hash, Google subject hoặc token.
- [x] Khóa quản trị không tự hết hạn; đặt lại mật khẩu không tự mở khóa quản trị.
- [x] Đổi quyền/khóa thu hồi mọi phiên trong cùng transaction.
- [x] Ghi actor, reason, trạng thái trước/sau vào audit cùng transaction với thay đổi tài khoản.
- [x] Gửi lại cùng trạng thái không tạo thêm thay đổi/audit.

### 3.3. Frontend đã có code

- [x] Trang `/admin/users`, menu Hệ thống → Tài khoản người dùng.
- [x] Tìm kiếm, lọc, phân trang, chi tiết và các nút đổi quyền/khóa.
- [x] Yêu cầu xác nhận và lý do; giữ hộp thoại và hiển thị lỗi khi cập nhật thất bại.
- [x] Vô hiệu nút tự khóa/tự hạ quyền và nút cấp ADMIN khi tài khoản chưa có mật khẩu.
- [x] Có mock phục vụ chạy UI không cần backend.

### 3.4. Kiểm tra đã có và phần còn thiếu

- [x] Test tích hợp mới cho quyền truy cập, lọc/phân trang, dữ liệu nhạy cảm, đổi quyền, khóa/mở khóa, token cũ, body lỗi và gọi lại.
- [x] Test UI mới cho xác nhận có lý do, tự khóa/hạ quyền, tài khoản Google, mở khóa và lỗi backend.
- [x] Lần kiểm tra đã ghi nhận: frontend typecheck/build và 343 test đạt; 18 test backend liên quan đạt với cấu hình compile kiểm tra riêng.
- [ ] Bổ sung/xác nhận test thao tác đồng thời để bảo vệ quyền ADMIN hoạt động cuối cùng.
- [ ] Bổ sung/xác nhận test đặt lại mật khẩu và Google login không vượt được khóa quản trị.
- [ ] Nghiệm thu việc audit UPDATE_ACCOUNT hiển thị đúng trên trang nhật ký, gồm trước/sau và lý do.
- [ ] Nghiệm thu chuyển trạng thái tài khoản trên UI sau khi cập nhật; lỗi 401/403 và nhiều tab.
- [ ] Kiểm tra trực quan 360px/desktop và khả năng dùng bàn phím.
- [ ] Xử lý các vấn đề workspace đang chặn kiểm tra chuẩn, rồi chạy lại `./mvnw verify` và `npm run check` đạt.

### 3.5. Triển khai và nghiệm thu

- [ ] Review contract, auth, security và thay đổi audit theo hướng dẫn repo.
- [ ] Sao lưu, áp dụng migration auth vào DB đích bằng Flyway và kiểm tra schema trên DBeaver.
- [ ] Triển khai backend/frontend tương ứng; không cho UI mới gọi backend cũ chưa hỗ trợ API.
- [ ] Nghiệm thu ADMIN tìm user → cấp ADMIN → người dùng đăng nhập lại → truy cập admin thành công.
- [ ] Nghiệm thu thu hồi quyền → Phiên cũ không dùng được → đăng nhập lại chỉ có BUYER.
- [ ] Nghiệm thu khóa → không đăng nhập/gọi API được; mở khóa → đăng nhập mới thành công.
- [ ] Xác nhận không tự khóa/tự hạ quyền, không mất ADMIN hoạt động cuối cùng và không lộ bí mật xác thực.
- [ ] Ghi kết quả nghiệm thu local/staging và hướng dẫn sử dụng.

**Điều kiện xong:** quản trị tài khoản chạy được với DB thật, quyền và phiên được cập nhật đúng, audit đầy đủ, kiểm tra chuẩn đạt và có kết quả nghiệm thu triển khai.

## 4. Thứ tự ưu tiên

Luồng tạo sự kiện sau khi có sân/layout được định nghĩa riêng trong [event-create-flow.md](event-create-flow.md): tên sự kiện → chọn sân → loại FOOTBALL/CONCERT → layout phù hợp và preview → chọn nhiều zone để cấu hình giá/tên → thông tin còn lại/showtime → xem lại và công bố. Các hạng mục này chưa được tính là đã triển khai trong checklist tạo sân.

1. Hoàn tất review và nghiệm thu phần quản lý user đã có code; xử lý các lỗi đang chặn kiểm tra chuẩn.
2. Chốt quy ước/schema SVG nhiều lớp và cách lưu wizard tạo sân.
3. Viết migration bổ sung, sửa contract rồi thực hiện backend và frontend tạo sân.
4. Triển khai quản lý sự kiện theo mục 2 sau khi API/schema sân/layout ổn định: tên → sân → loại → preview → zone/giá → thông tin còn lại/showtime.
5. Kiểm tra chuyển dữ liệu cũ và toàn bộ luồng sân/layout/event/showtime/đặt vé.
6. Triển khai đồng bộ và nghiệm thu; không áp dụng migration bỏ hall riêng khi app còn dùng hall.

Các vấn đề kiểm tra đã ghi nhận ở lần chạy trước: backend có phần tra cứu địa điểm chưa hoàn thiện, frontend kiểm tra format toàn repo còn báo nhiều file có sẵn. Đây là trạng thái được ghi nhận, cần kiểm tra lại trước triển khai; chưa có xác nhận đã xử lý.
