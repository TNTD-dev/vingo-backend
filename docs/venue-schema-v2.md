# Schema sân v2 — mục 1.2

**Cập nhật local 16/11/2026, 23:06:** đã migrate DB local và kiểm tra bảo toàn dữ liệu; xem [biên bản migrate local](local-venue-v2-migration.md). Các ghi chú chưa áp dụng DB local trong phần chuẩn bị/kiểm tra bên dưới mô tả thời điểm trước lần migrate này. UI và staging/production chưa triển khai.

## Trạng thái và phạm vi

Đã soạn schema/migration chờ và bộ kiểm tra DB riêng. Contract/backend mục 1.3 đã nối vào schema này, xem [venue-import-api.md](venue-import-api.md); UI mục 1.4 chưa triển khai. Chưa áp dụng lên DB local. Hai migration phải được triển khai cùng phiên bản ứng dụng đã bỏ hall, không chạy riêng trên app cũ:

1. `src/main/resources/db/migration/V20261116_1900__catalog_remove_hall.sql`.
2. `src/main/resources/db/migration/V20261116_1910__catalog_venue_svg_v2.sql`.

Cả hai (cùng `V20261117_0100` và `V20261117_0200`) đã nằm trong thư mục Flyway chính từ 17/11/2026, Flyway tự áp khi API khởi động. Không sửa migration lịch sử; muốn đổi thì tạo file mới.

## 1. Bảng và quan hệ

| Bảng | Vai trò và các trường chính |
|---|---|
| `venue` | Giữ thông tin sân hiện tại; không nới constraint để tạo sân không có tên |
| `venue_overview` | `id`, `venue_id`, `version`, `name`, `status`, `schema_version=2`, SVG nền đã làm sạch, viewBox, dấu xác nhận validation, thời điểm tạo/phát hành |
| `venue_overview_area` | Vùng nền tùy chọn; `overview_id`, `code`, `name`, `svg_id=AREA__code` |
| `venue_gate` | Cổng độc lập; `overview_id`, `code`, `name`, `direction=IN/OUT/BOTH`, `svg_id`, `label_svg_id` tùy chọn |
| `seat_layout` | Giữ ID/name/version/status hiện tại; thêm `venue_id` từ migration bỏ hall, `layout_schema_version`, `layout_type`, `overview_id` và dấu xác nhận validation |
| `seat_layout_svg` | SVG chi tiết hợp nhất; layout v2 dùng schema_version=2 và viewBox khớp overview |
| `zone` | Tiếp tục thuộc layout; thêm `overview_id` và `overview_area_id` tùy chọn để gắn vùng nền |
| `seat`, `seat_pair` | Giữ cấu trúc hiện tại; thêm chốt chặn chỉnh hình học của layout đã phát hành/đã được showtime sử dụng |
| `venue_import_session` | Nháp phía server, thuộc admin; chứa bước wizard, revision, draft_data, thời hạn và kết quả hoàn tất |
| `venue_import_file` | File nguồn, checksum, vai trò, nhóm layout, SVG đã làm sạch, kết quả parse/validation của session |

Không có bảng `zone_gate`. Cổng thuộc overview, zone thuộc layout. Va chạm giữa chúng do backend kiểm tra bằng Area trên SVG, không suy ra từ quan hệ FK.

### Loại layout và phiên bản

- Layout mới chỉ có `FOOTBALL` hoặc `CONCERT`, bắt buộc gắn overview của cùng sân.
- Nhiều layout của một sân có thể cùng loại, cùng tên; không đặt unique tên hoặc unique loại theo sân.
- `version` vẫn dùng identity toàn cục, có thể có khoảng trống; unique theo `(venue_id, version)`, không đánh lại số phiên bản cũ.
- Overview cũng có phiên bản riêng; một overview có thể được nhiều layout sử dụng.
- ViewBox overview bắt đầu tại 0,0; width/height dương, hữu hạn. Không chấp nhận NaN/Infinity.
- Gate code chấp nhận mã đơn và hậu tố số legacy như `B3-T1__02`; hướng lưu ở cột riêng. ID có thể là GATE__code hoặc GATE__code__direction. Không coi hậu tố số là hướng.
- Không lưu bản sao Path2D như nguồn dữ liệu thứ hai: SVG chuẩn hóa là nguồn hình học, các `svg_id` nhận diện vùng/cổng; backend dựng lại Area theo quy tắc parser đã hỗ trợ.

## 2. Constraint và validation

### Những gì DB bảo đảm

- FK kép `(seat_layout.overview_id, venue_id)` bắt overview và layout thuộc cùng sân.
- FK kép của zone bắt vùng nền thuộc đúng overview của layout; trigger điền overview_id từ layout và từ chối ID không khớp.
- Mã cổng/vùng và svg_id duy nhất trong mỗi overview; tên không chỉ có khoảng trắng.
- Giữ zone đứng và zone ngồi theo constraint hiện tại, không tạo ghế giả cho khu đứng.
- Không chuyển ID, sân sở hữu hoặc version của một bản ghi sang đối tượng khác bằng UPDATE.
- Layout/overview mới bắt đầu DRAFT; không chèn thẳng PUBLISHED.
- Dữ liệu PUBLISHED bất biến, gồm header, SVG, cổng/vùng, zone, ghế và cặp ghế. Không hạ về DRAFT để sửa; tạo phiên bản mới.
- Hình học layout đang được showtime sử dụng không sửa tại chỗ, kể cả trường hợp legacy DRAFT đã được tham chiếu.
- Phát hành layout v2 phải có overview PUBLISHED, SVG chi tiết schema 2, viewBox khớp, có zone và dấu validation đúng nội dung hiện tại.

### Những gì backend phải kiểm tra ở mục 1.3

- SVG/XML an toàn; marker/metadata đúng quy ước; phân loại/hợp nhất nhiều file.
- Hình học thật nằm trong viewBox/AREA, ghế thuộc zone, không chồng zone/ghế và không chiếm cổng; thực hiện bằng parser/Area, không bằng CHECK SQL hay bounding box.
- Tồn tại ít nhất một gate hợp lệ trong overview và đối chiếu từng svg_id với SVG thật. SQL chỉ kiểm số bản ghi cổng và định dạng ID, không tự parse XML.
- Sân còn hoạt động; ADMIN hiện tại có quyền với nháp; không nhận validation hash từ client.

### Hash xác nhận nội dung đã kiểm tra

- `catalog_overview_hash(id)` băm SHA-256 header/SVG/viewBox cùng danh sách gate/area có thứ tự ổn định.
- `catalog_layout_hash(id)` băm header/SVG/zone/ghế/cặp ghế **và hash nội dung overview**, không chỉ băm overview_id. Đổi nền đang DRAFT làm kết quả kiểm tra layout cũ hết hiệu lực dù ID nền không đổi.
- Backend sau khi kiểm tra thành công ghi `validation_hash`, `validation_engine`, `validated_at`; cả ba phải cùng có hoặc cùng không có.
- Sửa header/hình học làm xóa dấu validation tương ứng. Publish/finalize so hash đang lưu với hash tính lại.
- Trigger sửa child khóa hàng parent; publish kiểm tra sau khi khóa parent. Hàm hash dùng snapshot mới khi gọi để không tin dữ liệu cũ sau khi chờ lock.
- Đây là chốt chống dùng kết quả validation đã cũ. Nó không chứng minh SQL tự thực hiện collision; tài khoản DB ứng dụng vẫn là bên tin cậy và API không được cho client tự ghi dấu validation.

## 3. Lưu file và nháp qua nhiều phiên

### Quyết định lưu trữ

- File nháp lưu trong PostgreSQL (`bytea`), SVG làm sạch lưu `text`; chưa cần object storage hoặc thư viện mới.
- Tối đa 10 MiB mỗi file nguồn, 32 file và 40 MiB tổng nguồn mỗi session. SVG đã làm sạch cũng giới hạn 10 MiB/file; dung lượng này bổ sung ngoài tổng nguồn.
- Đây là giới hạn DB. API phải có giới hạn request/multipart tương ứng; có thể upload từng file khi admin chọn nhiều file, không mặc định tăng giới hạn HTTP toàn app.
- Không phục vụ `source_bytes` như nội dung có thể thực thi. Preview chỉ dùng sanitized_svg đã được backend kiểm tra.
- Tên file chỉ là metadata, không dùng làm đường dẫn filesystem.

### Session

- `owner_id` bắt buộc, FK users, không chuyển chủ bằng UPDATE. Kiểm tra ADMIN/ACTIVE và quyền sở hữu qua CurrentUser nằm ở API/service mục 1.3, không giả định FK đồng nghĩa phân quyền.
- Một session tạo một layout: có thể tạo sân mới (`target_venue_id=NULL`) hoặc thêm layout vào sân đã chọn (`target_venue_id` cố định).
- `draft_data` là object JSON cho dữ liệu wizard chưa hoàn chỉnh; không dùng để tạo venue rỗng. Backend validate từng bước trước lưu.
- Bước: OVERVIEW, LAYOUT, PREVIEW, DETAILS, REVIEW.
- Trạng thái lưu: DRAFT, COMPLETED, CANCELLED. EXPIRED được tính khi DRAFT có expires_at <= hiện tại.
- TTL mặc định: DRAFT 30 ngày trượt từ lần lưu/file mutation; COMPLETED/CANCELLED giữ 7 ngày cho retry/đối chiếu rồi cleanup. Thời hạn do backend quản lý, không cho client tùy ý sửa expires_at.
- Mỗi lần sửa session hoặc thêm/sửa/xóa file tăng revision. Backend cập nhật với `WHERE id=:id AND owner_id=:owner AND revision=:expected` hoặc lock session rồi so revision. Không cập nhật được thì trả lỗi xung đột đã mô tả trong contract tương lai.
- Nháp hết hạn hoặc đã đóng không được sửa/file mutation. API danh sách/mở lại chỉ trả nháp thuộc admin còn quyền.
- Cleanup worker xóa session hết hạn theo lô; FK CASCADE dọn file của session, không xóa venue/layout/đơn/vé. Job cleanup chưa triển khai trong đợt DB này.

### File

- Vai trò: UNKNOWN, OVERVIEW, SEAT_LAYOUT, COMBINED_V1. Trạng thái kiểm tra: UPLOADED, VALIDATED, INVALID.
- Checksum và size sinh từ bytea, không tin giá trị client gửi; cùng nội dung trong một session không lưu trùng.
- Bộ hoàn tất gồm đúng một OVERVIEW và một hoặc nhiều SEAT_LAYOUT có cùng layout_key. UNKNOWN/COMBINED_V1/INVALID phải xử lý hoặc thay file trước hoàn tất; schema không hứa tự tách mọi SVG gộp.
- Lưu kết quả parse trong parsed_metadata và lỗi trong issues. File VALIDATED phải có schema_version và sanitized_svg.
- SVG đã qua kiểm tra an toàn nhưng lỗi hình học có thể giữ sanitized_svg với trạng thái INVALID để preview/tô vùng lỗi. Không hiển thị source_bytes thô và không cho hoàn tất khi còn file INVALID.
- Role, schema_version, sanitized_svg và kết quả validation chỉ backend được ghi. Khi parse ngoài transaction, ghi kết quả phải đối chiếu checksum/revision của đầu vào đã parse để không lưu kết quả cũ lên file vừa được thay.
- Thay bytes nguồn xóa kết quả validation/parse, đặt role UNKNOWN; phải phân loại và kiểm tra lại.
- Service mục 1.3 phải ánh xạ lỗi SQL sang lỗi nghiệp vụ đã mô tả trong contract, không trả nguyên lỗi DB chứa byte/file nháp cho client.
- File mutation khóa session trước khi kiểm tra quota/nhóm layout và tăng revision để tránh vượt quota do upload đồng thời.

### Hoàn tất và retry

1. Đọc nháp/file và revision; parse, làm sạch và kiểm tra hình học trước khi giữ lock dài.
2. Bắt đầu transaction, khóa session, kiểm tra owner/quyền/expiry/revision. Nếu COMPLETED thì trả kết quả đã lưu, không tạo lại sân/layout.
3. Kiểm tra sân đích; tạo venue nếu cần, rồi overview/gate/area và layout/SVG/zone/ghế DRAFT từ đúng dữ liệu đã kiểm tra.
4. Ghi dấu validation nội dung đã persist; không lấy hash client. Các dấu này được tính sau khi có ID thật.
5. Đặt session COMPLETED cùng completed_venue_id/completed_layout_id trong transaction đó. DB đối chiếu quan hệ, hash và bộ file hợp lệ; kết quả layout duy nhất giữa các session.
6. Commit. Nếu lỗi bất kỳ bước nào, rollback cả dữ liệu mới và trạng thái session. Publish là thao tác riêng sau đó.

Để tránh deadlock, service mới cần thống nhất thứ tự lock trước mutation: session → venue → overview → layout → child. Trigger là chốt cuối, không thay cho thiết kế transaction của service.

## 4. Chuyển dữ liệu cũ

- Migration 1900 kiểm tra showtime/layout khớp hall và version không trùng trong sân, sau đó backfill venue_id và bỏ hall.
- Migration 1910 đánh dấu layout hiện có `layout_schema_version=1`, `layout_type=NULL`, `overview_id=NULL`; không sửa ID, SVG, zone/ghế, showtime hoặc dữ liệu giao dịch.
- Không suy ra loại layout từ event.category, vì một layout cũ có thể từng được nhiều loại sự kiện sử dụng. Không tự tách SVG gộp thành overview bằng suy đoán.
- PUBLISHED legacy vẫn phục vụ suất/đơn/vé cũ nhưng không xuất hiện trong bộ chọn tạo sự kiện mới theo loại. Query mới chỉ lấy layout schema 2, PUBLISHED, đúng type và sân.
- Layout legacy đã phát hành hoặc đã có showtime tham chiếu phải được clone thành layout v2 mới khi bổ sung nền/loại, giữ bản cũ cho lịch sử. Legacy DRAFT chưa có showtime có thể được chuyển đổi có kiểm tra ở service sau này; không tự động chuyển trong migration.
- Hai file Flyway có transaction riêng theo mặc định. Nếu file 1910 lỗi sau khi 1900 đã commit, không chạy lại app cũ với schema dở; xử lý migration tiếp hoặc khôi phục backup. Có thể chốt triển khai một migration tổng hợp hoặc Flyway group ở bước triển khai; không giả định rollback một file sẽ hoàn tác file trước.
- Không dùng baseline/repair/reset để che lỗi hoặc xóa dữ liệu cũ.

## 5. Kiểm tra và công việc tiếp theo

Chạy SQL trên PostgreSQL 18 container tạm:

```powershell
python scripts/check_venue_schema_migration.py
```

Runner kiểm tra schema rỗng và có dữ liệu cũ; cùng sân/nền; hash/stale validation; bất biến PUBLISHED; viewBox; owner/revision, hoàn tất/retry/TTL/cleanup và quota; rollback DDL khi lỗi. Dữ liệu và dấu `test-validator` là fixture kiểm tra constraint, không thay thế các test parser/collision thực tế đã có.

`VenueSchemaMigrationTest` dùng Flyway thật trong schema riêng để kiểm tra chuỗi migration, validate checksum và chạy lại không có thay đổi. Kết quả chạy được cập nhật sau khi kiểm tra.

Kết quả đã ghi nhận:

- Runner SQL PostgreSQL 18: 6 nhóm kiểm tra đạt, gồm trường hợp SVG an toàn nhưng lỗi hình học vẫn preview được và bị chặn hoàn tất.
- `VenueSchemaMigrationTest`: 2 test đạt, không skip; schema mới chạy tổng cộng 24 migration (lịch sử và 2 file chờ), schema cũ chạy tiếp đúng 2 file chờ, validate thành công và migrate lại không có thay đổi. Kiểm tra chỉ diễn ra trong schema/container test riêng.
- Verify chuẩn đã chạy nhưng compile vẫn bị `VenuePlaceLookupService` tham chiếu hai client rỗng GooglePlacesClient/GeminiParkingFeeClient trong workspace. Không sửa các file công việc đang dở này.
- Để chạy 2 test Flyway, dùng POM kiểm tra tạm chỉ loại service đang dở đó khỏi compile; không tắt/bỏ test. POM tạm được dọn sau khi chạy. Chưa xác nhận verify chuẩn toàn repo hoặc staging đạt.

Mục 1.3 tiếp theo phải thực hiện API/DTO/repository/service phù hợp schema này, nối validator với dấu validation, thao tác nháp và cleanup worker. Mục 1.4 thực hiện wizard nhiều file/mở lại nháp. Chưa được áp dụng schema chờ lên DB local đang chạy app còn hall.
