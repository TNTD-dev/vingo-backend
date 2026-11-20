# Quy ước SVG v2 và import sân

**Cập nhật local 16/11/2026, 23:06:** đã migrate DB local và kiểm tra bảo toàn dữ liệu; xem [biên bản migrate local](local-venue-v2-migration.md). Các ghi chú chưa áp dụng DB local trong phần chuẩn bị/kiểm tra bên dưới mô tả thời điểm trước lần migrate này. UI và staging/production chưa triển khai.

## Quyết định nghiệp vụ đã chốt

- Chỉ FOOTBALL và CONCERT trong phạm vi đầu tiên.
- Hỗ trợ nhiều file: một overview và nhiều file chi tiết cho một layout.
- Ghép theo tọa độ chung, không có công cụ kéo/xoay/căn chỉnh thủ công.
- Lưu nháp qua nhiều phiên, không chỉ giữ trong bộ nhớ trình duyệt.
- Zone và cổng độc lập về nghiệp vụ; không có bảng zone_gate hoặc yêu cầu gán cổng cho zone. Hình học zone/ghế không chiếm vùng cổng.

Phần collision ở mục 3.1–3.2 đã được đưa vào code và contract lỗi. Schema overview/layout/import session đã có migration chờ và kiểm tra riêng tại `venue-schema-v2.md`, chưa áp dụng DB local. Parser overview v2, API phân loại/hợp nhất nhiều file và import session runtime đã triển khai ở mục 1.3, xem [venue-import-api.md](venue-import-api.md). Wizard UI chưa triển khai.

## Đối chiếu nguồn thực tế

`sources/LAYOUT__THIEN-TRUONG.svg` có viewBox `0 0 1600 1200`, 17 GATE, 17 GATE_LABEL, 27 ZONE, 6.699 SEAT, 22 CONTENT và 5 WALKWAY; chưa có AREA. File không có transform, nhưng có 24 mask, 52 use và một defs. Đây là dữ liệu COMBINED_V1, không phải overview v2 thuần.

Thông tin trên được đọc từ XML, chưa phải kết luận mọi ghế/zone/cổng của file đã đạt kiểm tra hình học mới.

## 1. Overview

| Thành phần | ID/quy ước | Ý nghĩa |
|---|---|---|
| Gốc svg | viewBox="0 0 W H", W/H hữu hạn và lớn hơn 0 | Tọa độ gốc toàn sân |
| Đồ họa nền | PITCH, CIRCULATION, WALKWAY__CODE, STAGE__CODE | Hiển thị; không tự trở thành zone bán vé |
| Vùng nền | AREA__CODE, tùy chọn | Biên hình học hỗ trợ kiểm tra chứa zone |
| Cổng | GATE__CODE, hoặc GATE__CODE__IN/OUT/BOTH | Vùng hình học thực của cổng; không có hướng thì BOTH |
| Nhãn cổng | GATE_LABEL__CODE, tùy chọn | Chữ/số trang trí, không tham gia collision |

- Overview thuần phải có ít nhất một vùng cổng có hình học hợp lệ; không có ZONE hoặc SEAT. Có PITCH/AREA nhưng không có cổng chỉ là ứng viên overview chưa hợp lệ, không được lưu hoàn tất/phát hành.
- Mã cổng duy nhất trong overview. Chỉ IN/OUT/BOTH ở hậu tố cuối có nghĩa là hướng; `GATE__B3-T1__02` có mã `B3-T1__02`, không coi `02` là hướng. Không suy ra quan hệ zone từ phần tên B3-T1.
- Giữ tương thích mã cổng cũ; với file mới nên dùng mã phẳng như G01 để giảm nhập nhằng dấu phân cách.
- Hướng ghi rõ nhưng sai giá trị phải báo lỗi khi file khai báo v2; không âm thầm đổi thành BOTH. Quy tắc phân biệt mã cũ với hướng mới cần dùng metadata phiên bản/grammar rõ ràng.
- Nhãn GATE_LABEL không tạo thêm gate và không tính vào vùng chiếm chỗ. Hình dạng GATE phải biểu diễn lối mở thực, không chỉ là biểu tượng/nhãn; nếu cần khoảng trống lớn hơn phải khai báo hình học vùng trống riêng trong quy ước triển khai.
- AREA tùy chọn: khi có nhiều AREA, kiểm tra zone với AREA được chỉ định hoặc vùng chứa duy nhất, không yêu cầu mọi zone nằm trong mọi AREA. Không đoán bằng tên hiển thị. Nếu không xác định được AREA tương ứng thì báo rõ để sửa.

## 2. Phân loại nhiều file

Phân loại bằng XML theo quy ước, không dựa tên file. Không cam kết suy ra đúng 100% ý nghĩa của SVG tùy ý.

| Kết quả | Dấu hiệu và cách xử lý |
|---|---|
| OVERVIEW candidate | Có dấu hiệu nền/cổng, không có zone/seat; chỉ hợp lệ khi đạt đầy đủ điều kiện overview |
| SEAT_LAYOUT candidate | Có ít nhất một ZONE hợp lệ, không có các thành phần dành riêng cho nền/cổng; SEATED có ghế, STANDING có sức chứa |
| COMBINED_V1 | Có cả nền/cổng và zone/seat; chuyển qua luồng tương thích, không giả vờ là overview/detail v2 |
| UNKNOWN/INVALID | Không có marker, marker sai hoặc mâu thuẫn, seat mồ côi, metadata không khớp; báo rõ, không tự đoán |

- Metadata gốc SVG tùy chọn: `data-schema-version="2"` và `data-document-kind="OVERVIEW"` hoặc `"SEAT_LAYOUT"`. Sanitizer chỉ chấp nhận đúng tên/giá trị này ở root; marker và metadata phải khớp nhau.
- Backend là nơi xác nhận cuối; frontend chỉ phân loại/preview sơ bộ và phải dùng cùng phiên bản quy tắc.
- Phân tích thành phần thực được render; không lấy marker ẩn trong defs hoặc chữ nhãn làm bằng chứng duy nhất. Theo dõi tham chiếu use/mask để xác định hình học thực.
- Một bộ import cho một layout gồm đúng một overview và một hoặc nhiều file detail. Nhiều overview phải được admin chọn, không tự chọn file đầu tiên.
- File detail của các layout khác nhau không được gộp chung. Mỗi bộ phải có tên, loại và định danh nhóm layout rõ ràng.
- Không trùng mã zone, ID ghế chuẩn hóa hoặc các định danh nghiệp vụ giữa file detail cùng bộ. Kiểm tra trùng cả hàng/số ghế sau chuẩn hóa số.
- Khi hợp nhất, namespace/rewrite ID kỹ thuật và mọi tham chiếu nội bộ tương ứng; không đổi mã zone/ghế nghiệp vụ. Giữ đủ defs, mask, clipPath/use và thứ tự vẽ nếu hỗ trợ; cấu trúc không hỗ trợ phải báo lỗi.
- File COMBINED_V1 chỉ tự tách khi phân tích được đầy đủ cây thành phần và phụ thuộc. Nếu không tách chắc chắn, yêu cầu xuất lại hai lớp và nêu lỗi; không cam kết tự tách mọi SVG.
- Giới hạn đã áp dụng: 10 MiB/file/request nguồn; 32 file và 40 MiB nguồn/session; detail hợp nhất tối đa 10 MiB; 100.000 node/file. Tập path và độ sâu hình học theo mục 3.2.

## 3. Ghép theo tọa độ và kiểm tra hình học

- Mọi file detail dùng cùng origin, trục, đơn vị và viewBox với overview; cùng viewBox là điều kiện cần, không chứng minh nội dung đã đúng vị trí.
- So sánh giá trị số sau chuẩn hóa: `1600` và `1600.0` bằng nhau; không so nguyên chuỗi viewBox. Không tự scale để chữa file lệch.
- ViewBox sai trả 422 INVALID_SEAT_LAYOUT với reason VIEW_BOX_MISMATCH sau khi trường reason được thêm vào contract.
- Transform có sẵn trong SVG cần được tính chính xác về tọa độ gốc. Không có công cụ căn chỉnh thủ công không đồng nghĩa được bỏ qua transform; cấu trúc transform không hỗ trợ phải từ chối rõ.
- Toàn bộ hình học zone/ghế phải nằm trong viewBox. Khi có AREA áp dụng, kiểm tra nằm trong hình dạng AREA thực, không chỉ trong bounding box.
- Kiểm tra toàn bộ hình ghế với zone của chính nó. Zone là hợp fill của tất cả path/use sau transform, winding và mask. Khi kiểm tra tâm ghế, khép khe làm tròn trên vùng hợp bằng phép giãn/co cùng bán kính `0.005` đơn vị SVG, giữ các lối đi/phần khoét rộng hơn giới hạn ghép. Không dùng bounding box/convex hull thay hình zone. Diện tích ghế ngoài hình zone gốc vẫn không được vượt `min(0.025 đơn vị SVG², 1% diện tích ghế)` trong tọa độ viewBox sau transform. Không tự sửa vị trí, bán kính hoặc số lượng ghế. Chỉ kiểm tra tâm ghế vẫn chưa đủ; giới hạn này không nới kiểm tra ghế giao cổng hay ghế giao ghế.
- Phần diện tích zone không được giao vùng cổng; ghế không được giao vùng cổng. Zone có thể có lỗ/cắt góc để chừa lối đi; phép kiểm tra phải tôn trọng hình dạng, fill-rule và mask được hỗ trợ.
- Hai zone bán vé không chồng diện tích; hai ghế không chồng hình. Chạm biên có thể được phép theo tolerance số được quy định, không coi mọi giao bounding box là lỗi.
- Bounding box chỉ dùng lọc nhanh các cặp có khả năng giao nhau; quyết định cuối theo hình học thực. Nhãn, nét chọn, màu preview và bóng trang trí không được coi là vùng bán vé/cổng.
- Không có ghế không luôn đồng nghĩa không phải zone: khu STANDING là zone không có ghế nhưng có sức chứa. Riêng cổng và hành lang cần để trống không được xem là khu bán vé.
- Các lý do lỗi cần mô tả trong contract: trùng ID/zone, sai viewBox, ghế ngoài zone, zone/ghế đè cổng, zone đè nhau, cấu trúc không hỗ trợ. Các reason đã được trả qua issues của API nháp/preview/complete; xem venue-import-api.md.

### 3.1. Kiểm tra va chạm bằng Area

Phần này áp dụng cho kiểm tra hình học ở mục 3; không tạo quan hệ nghiệp vụ zone–cổng.

- Dùng `java.awt.geom.Area` để kiểm tra phần diện tích giao sau khi quy đổi toàn bộ shape về tọa độ overview. Bounds/Rectangle2D chỉ lọc nhanh ứng viên.
- Đã thêm `SeatLayoutSvgGeometry.area()` và `seatArea()` để lấy Area thực, giữ API bounds cũ để tương thích. Parser import hiện tại tự kiểm tra collision khi có cổng trong file gộp.
- Đọc fill-rule thực, gồm giá trị kế thừa, để chọn WIND_NON_ZERO hoặc WIND_EVEN_ODD khi dựng Path2D. Giữ subpath/lỗ; không thay shape bằng hình chữ nhật hoặc tự nối lỗ vào phần đặc.
- Phân giải use/defs, transform cha/con và các cơ chế cắt được hỗ trợ. Phần tử chỉ vẽ stroke không tự trở thành vùng zone có fill. Nhãn cổng và nét viền trang trí không tham gia collision. Mask không thể chuyển đúng sang hình học nhị phân thì báo cấu trúc chưa hỗ trợ, không bỏ qua để xác nhận hợp lệ.
- `SEAT_OVERLAPS_GATE`: dựng hình ghế thực bằng Ellipse2D và áp dụng transform (có thể thành ellipse), kiểm tra giao Area với gate. Ghế có diện tích giao dương hoặc tâm nằm trong vùng gate bị từ chối; không cho phép tâm trong cổng chỉ vì phần giao nhỏ.
- `ZONE_OVERLAPS_GATE`: giao Area(zone) với Area(gate); chấp nhận giao rỗng hoặc diện tích giao không quá 0.5 đơn vị tọa độ overview bình phương. Vượt ngưỡng trả lỗi. Với file xuất 1 đơn vị = 1 px thì tương đương 0.5 px², không phụ thuộc zoom/CSS pixel trên màn hình.
- Ngưỡng 0.5 là dung sai diện tích hình học/nhiễu xuất file, không phải độ rộng stroke. Không cộng stroke vào shape rồi dùng ngưỡng này để hợp thức hóa va chạm; kiểm tra stroke/clearance thực tế, nếu cần, là quy tắc khác.
- `SeatLayoutAreaMeasure` dùng tích phân Green giải tích trên line/quadratic/cubic của PathIterator, cộng bù và dời gốc để giảm sai số double. Không flatten nên không có flatness hoặc sai số xấp xỉ đa giác do flatten. Diện tích tính trên Shape do Java2D biểu diễn; Ellipse2D/Area vẫn dùng số double và các đoạn Bézier của Java2D, không tuyên bố phép đo số học chính xác tuyệt đối của ellipse lý tưởng. Test kiểm tra đường cong, lỗ, tọa độ dịch lớn và hai phía/ngay ngưỡng 0.5.
- Thực hiện phép intersect trên bản sao Area để không sửa shape gốc dùng cho các cặp kiểm tra sau. Preview hiển thị zone_id, gate_id, phần giao và reason lỗi; backend vẫn là nơi xác nhận cuối.

Đối chiếu Thiên Trường: `ZONE__B3-T1__SEATED` thực tế là nhóm g có use trỏ defs, mask và một path viền, không phải một path độc lập. Không kết luận lỗ/cutout và giao với cổng chỉ bằng bounding box hoặc nhìn ID; cần fixture kiểm tra hình học đã phân giải đầy đủ với GATE__B3-T1__02 và GATE__B3-T1__03.

Checklist thực hiện:

- [x] Thêm shape/area API nội bộ, winding rule và phân giải hình học thực.
- [x] Chốt cấu trúc mask/use/transform được hỗ trợ và cách báo lỗi phần không hỗ trợ.
- [x] Thêm phép đo diện tích giao và sai số đường cong, dùng ngưỡng zone–gate 0.5 đơn vị².
- [x] Thêm reason SEAT_OVERLAPS_GATE và ZONE_OVERLAPS_GATE vào contract, có ID đối tượng và dữ liệu lỗi phù hợp.
- [x] Test bounding box giao nhưng zone thực không giao do lỗ/cutout.
- [x] Test tâm ghế ngoài cổng nhưng mép ghế cắt cổng, ghế ở trong cổng và ghế chạm biên.
- [x] Test fill-rule evenodd/nonzero, winding và transform tạo ellipse.
- [x] Test zone–gate giao rỗng, chạm biên, nhỏ hơn/bằng/lớn hơn ngưỡng, đường cong và lỗ.
- [x] Test fixture thực Thiên Trường sau khi phân giải use/defs/mask, không giả định sẵn kết quả collision.

### 3.2. Tập hình học đã hỗ trợ và điểm tích hợp

- Fill: path `M/L/H/V/Q/C/Z` tuyệt đối/tương đối, nhiều subpath, polygon/polyline, rect kể cả rx/ry và circle. Fill-rule evenodd/nonzero có kế thừa. Stroke và GATE_LABEL không tham gia diện tích collision.
- Transform: matrix, translate, scale, rotate kể cả tâm quay, skewX/skewY; ghế được transform thành ellipse theo đúng Shape. Ma trận không hữu hạn hoặc ghế suy biến bị từ chối.
- Use: chỉ tham chiếu nội bộ tới primitive được parser cho phép; dùng fill-rule riêng của target và style kế thừa từ use, giữ transform/x/y. Không hỗ trợ chuỗi use tham chiếu use/g hoặc vòng lặp.
- Mask: luminance nhị phân, fill white/#fff/#ffffff và black/#000/#000000, opacity/fill-opacity chỉ 0 hoặc 1, không stroke; children là primitive/use, không nhóm g. White thêm vùng, black trừ vùng theo thứ tự vẽ. Mask phải gắn trực tiếp vào primitive/use, không trên ancestor group, root hoặc ghế.
- maskContentUnits chỉ userSpaceOnUse (cũng là mặc định). maskUnits hỗ trợ objectBoundingBox với số/tỷ lệ phần trăm, hoặc userSpaceOnUse khi khai báo đủ x/y/width/height bằng số tuyệt đối. Không hỗ trợ transform ngay trên mask, alpha mask, mask màu xám/bán trong suốt hoặc mask lồng.
- Arc `A`, smooth `S/T`, CSS, cấu trúc clipPath và các phần ngoài tập parser hiện tại chưa hỗ trợ; không bỏ qua để kết luận hợp lệ. Tùy lớp từ chối có thể là lỗi structural hiện có hoặc UNSUPPORTED_COLLISION_GEOMETRY.
- API nội bộ `validateGateCollisions(overviewBytes, detailBytes)` kiểm tra XML/allowlist, vai trò file, viewBox so theo số, cấu trúc layout rồi collision giữa hai document. VenueSvgProcessor dùng cùng validator Area cho bộ overview/detail sau khi hợp nhất nhiều file; endpoint và session ở venue-import-api.md.
- Luồng tương thích/parser SVG gộp đã kiểm tra collision; API complete mới trả 422 với `issues[].reason`, `layer_id`, `zone_id`, `gate_id`, `intersection_area` và `tolerance`. Cấu trúc không hỗ trợ trả UNSUPPORTED_COLLISION_GEOMETRY thay vì im lặng bỏ qua.
- Test API trên PostgreSQL xác nhận lỗi collision không tạo thêm layout. Mục 1.3 đã nối lưu nháp, nền riêng và API preview nhiều file. Migration vẫn chờ áp dụng DB local; UI preview chưa triển khai.

### 3.3. Kết quả phần collision trước mục 1.3

- 39 test hình học/collision, 22 test parser, 21 test API import trên PostgreSQL 18 và 2 test phân module đều đạt.
- Lượt verify cấu hình kiểm tra riêng: 82 suite, 833 test, 0 failure/error/skipped.
- Verify chuẩn đã được chạy nhưng chưa compile được do `VenuePlaceLookupService` trong workspace tham chiếu `GooglePlacesClient` và `GeminiParkingFeeClient` đang là file rỗng. Không sửa hoặc xóa các file công việc đang dở này.
- Để kiểm tra hồi quy, dùng POM tạm chỉ loại `VenuePlaceLookupService.java` khỏi compile; không tắt hoặc bỏ test. POM tạm được xóa sau khi chạy. Kết quả này không phải xác nhận verify chuẩn toàn workspace đã đạt hoặc Swagger staging đã được nghiệm thu.
- Không thay đổi dữ liệu DB local, migration hoặc UI trong phần collision này.

## 4. Lưu nháp qua nhiều phiên

- Tạo import session thuộc ADMIN hiện tại trước khi có đủ metadata venue; không tạo venue có tên rỗng để lách constraint.
- Lưu thông tin wizard, metadata file, bản SVG đã kiểm tra, kết quả parse và bước đang làm phía server; chỉ lưu draft/session ID và trạng thái giao diện cần thiết phía client, không lưu token vào localStorage.
- Schema lưu nháp đã chốt trong `venue-schema-v2.md`: DRAFT mặc định 30 ngày trượt, COMPLETED/CANCELLED giữ 7 ngày; API danh sách/mở lại nháp, phân quyền và cleanup worker còn cần triển khai.
- Có revision để phát hiện hai phiên/tab ghi đè nhau, và cơ chế retry không tạo trùng file/venue/layout khi hoàn tất.
- Khi hoàn tất, backend kiểm tra lại dữ liệu, lưu venue/overview/layout/zone/ghế nguyên tử và đánh dấu session đã hoàn tất. Không gọi lưu file bên ngoài trong transaction đang giữ lock.
- Kiểm tra quay lại sau logout/login, đóng trình duyệt, mạng lỗi và mở cùng nháp ở nhiều tab.

## 5. Trạng thái triển khai

Đã triển khai collision ở mục 3.1–3.2 và schema lỗi trong contract. Đã soạn migration SVG nhiều lớp/import session ở thư mục chờ, chưa áp dụng DB local. Chưa triển khai toàn bộ SVG v2, API nhập nhiều file, mở lại nháp, cleanup worker hoặc UI; xem `venue-schema-v2.md` cho phạm vi DB đã hoàn thành.

### 3.4. Kiểm tra đầy đủ bộ SVG v2

Ngoài collision cổng, VenueSvgProcessor kiểm tra containment zone/ghế, mapping AREA, zone–zone và seat–seat. File Thiên Trường gốc sau khi tách lớp có zone A6-T1 giao C5-T1; test giữ kết quả đo này, không mặc định file gốc đạt mọi ràng buộc v2. Chi tiết xử lý và fixture HTTP độc lập tại venue-import-api.md.
