-- Dữ liệu DEMO cho giao diện admin, schema sau V20261118_1600.
-- Chỉ chạy trên vingo_admin_ui_20261118. Không xóa/ghi đè dữ liệu hiện có.
-- Toàn bộ trong một transaction; chạy lại sẽ bỏ qua nếu bộ DEMO này đã tồn tại.
-- Đây là fixture UI: payment/audit/login được mô phỏng, không gọi VNPay/email/SMS.
-- Hình học dựng trực tiếp cho UI, không thay thế kiểm thử validator/import SVG.
\set ON_ERROR_STOP on
\encoding UTF8
BEGIN;
SET LOCAL lock_timeout = '10s';
SELECT pg_advisory_xact_lock(hashtextextended('vingo-admin-ui-demo-20261119-v1', 0));

CREATE FUNCTION pg_temp.demo_id(n int) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
    SELECT ('019b1900-0000-7000-8000-' || lpad(n::text,12,'0'))::uuid
$$;

DO $seed$
DECLARE
    dataset constant text := 'admin-ui-20261119-v1';
    credential text;
    standard_seat uuid;
    v int; k int; z int; r int; c int; n int; q int; j int; event_n int; venue_n int;
    layout_slot int; zone_n int; order_n int;
    venue_id_ uuid; overview_id_ uuid; layout_id_ uuid; zone_id_ uuid;
    event_id_ uuid; show_id_ uuid; user_id_ uuid; order_id_ uuid; seat_id_ uuid;
    tier_id_ uuid; item_id_ uuid; ticket_id_ uuid; intent_id_ uuid; actor_id_ uuid;
    layout_type_ text; event_status_ text; order_status_ text; ticket_status_ text;
    code_ text; label_ text; name_ text; city_ text; city_name_ text; address_ text;
    svg_ text; overview_svg_ text; circles_ text; operation_ text;
    lat_ numeric; lng_ numeric; y_ numeric; price_ bigint; total_ bigint;
    starts_ timestamptz; opens_ timestamptz; closes_ timestamptz; paid_ timestamptz;
    created_ timestamptz; expires_ timestamptz;
    names text[] := ARRAY['Nguyễn Minh Anh','Trần Hoàng Nam','Lê Ngọc Linh','Phạm Đức Huy',
        'Hoàng Thu Hà','Vũ Quang Minh','Đặng Phương Thảo','Bùi Tuấn Kiệt',
        'Đỗ Bảo Ngọc','Hồ Gia Bảo','Ngô Khánh Vy','Dương Anh Tuấn',
        'Lý Thanh Tâm','Mai Hải Đăng','Đinh Thùy Dương','Tạ Quốc Bảo'];
    titles text[] := ARRAY['Hà Nội FC — Nam Định','Đêm nhạc Thanh âm thành phố',
        'Sài Gòn United — Đà Nẵng','Summer Lights Live',
        'Đà Nẵng FC — Hải Phòng','Indie Night: Những ngày xanh',
        'Hải Phòng — Hà Nội FC','Acoustic Sunset Sessions',
        'Nam Định — Sài Gòn United','Vingo Music Weekend',
        'Cúp giao hữu mùa đông','Đêm nhạc bên bờ biển'];
    event_venues int[] := ARRAY[1,1,2,2,3,4,4,5,5,3,2,3];
    sale_events int[] := ARRAY[1,2,3,4,5,6,8,9,10];
    operations text[] := ARRAY['createVenue','updateVenue','publishSeatLayout','createEvent',
        'publishEvent','putPriceTiers','putZonePrices','adminUpdateUser'];
BEGIN
    IF current_database() <> 'vingo_admin_ui_20261118' THEN
        RAISE EXCEPTION 'Chỉ được seed DB vingo_admin_ui_20261118';
    END IF;
    IF EXISTS (SELECT 1 FROM venue WHERE info->>'demo_dataset'=dataset) THEN
        RAISE NOTICE 'Bộ DEMO đã tồn tại; giữ nguyên dữ liệu, không seed trùng.';
        RETURN;
    END IF;
    SELECT password_hash INTO credential FROM users WHERE lower(email)='admin@luannt.com';
    IF credential IS NULL THEN RAISE EXCEPTION 'Cần tài khoản admin@luannt.com đã tạo trước'; END IF;
    IF EXISTS (SELECT 1 FROM users WHERE email LIKE 'demo.%@example.test') THEN
        RAISE EXCEPTION 'Đã có email demo trùng; dừng để không ghi đè tài khoản';
    END IF;
    SELECT id INTO STRICT standard_seat FROM seat_type WHERE code='STANDARD';

    -- Tài khoản DEMO dùng hash Argon2 hiện có, không chứa mật khẩu thô trong file.
    FOR n IN 1..32 LOOP
        name_ := CASE WHEN n=31 THEN 'DEMO · Nguyễn An (Quản trị)'
                      WHEN n=32 THEN 'DEMO · Trần Bình (Vận hành)'
                      ELSE names[1+(n-1)%array_length(names,1)] || CASE WHEN n>16 THEN ' '||n ELSE '' END END;
        INSERT INTO users(id,email,full_name,password_hash,role,status,admin_locked,locked_until,
                          avatar_url,created_at,updated_at)
        VALUES(pg_temp.demo_id(n),'demo.'||lpad(n::text,2,'0')||'@example.test',name_,credential,
            CASE WHEN n>30 THEN 'ADMIN' ELSE 'BUYER' END,
            CASE WHEN n IN (25,26,27) THEN 'UNVERIFIED' WHEN n IN (28,29) THEN 'LOCKED'
                 WHEN n=30 THEN 'DELETED' ELSE 'ACTIVE' END,
            n=28,CASE WHEN n=29 THEN now()+interval '15 minutes' END,
            CASE WHEN n%3<>0 THEN '/demo/admin-ui/avatar-'||(1+(n-1)%6)||'.svg' END,
            CASE WHEN n<=24 THEN now()-interval '70 days'+n*interval '4 hours'
                 ELSE now()-(1+(n*11)%65)*interval '1 day' END,now()-n*interval '1 hour');
        IF n<=24 THEN
            INSERT INTO login_attempt(user_id,success,at)
            VALUES(pg_temp.demo_id(n),true,now()-n*interval '3 hours'),
                  (pg_temp.demo_id(n),false,now()-n*interval '3 hours'-interval '10 minutes'),
                  (pg_temp.demo_id(n),true,now()-n*interval '1 day');
        END IF;
    END LOOP;

    -- Năm sân và location mở, gồm tỉnh ngoài danh sách HN/HCM/DN cũ.
    FOR v IN 1..5 LOOP
        venue_id_:=pg_temp.demo_id(100+v); overview_id_:=pg_temp.demo_id(200+v);
        actor_id_:=pg_temp.demo_id(31+(v%2));
        SELECT a.name,a.city,a.city_name,a.address,a.lat,a.lng INTO name_,city_,city_name_,address_,lat_,lng_
        FROM (VALUES
            (1,'Sân vận động Mỹ Đình','HN','Hà Nội','Lê Đức Thọ, Hà Nội',21.0205::numeric,105.7633::numeric),
            (2,'Sân vận động Thống Nhất','HCM','TP. Hồ Chí Minh','138 Đào Duy Từ, TP. Hồ Chí Minh',10.7606,106.6637),
            (3,'Sân vận động Hòa Xuân','DN','Đà Nẵng','Đường Đỗ Phát, Đà Nẵng',15.9882,108.2212),
            (4,'Sân vận động Lạch Tray','VN-HP','Hải Phòng','Lạch Tray, Hải Phòng',20.8491,106.6889),
            (5,'Sân vận động Thiên Trường','VN-ND','Nam Định','Đặng Xuân Thiều, Nam Định',20.4381,106.1803)
        ) a(n,name,city,city_name,address,lat,lng) WHERE a.n=v;
        INSERT INTO venue(id,name,type,location,info,updated_by,created_at,updated_at)
        VALUES(venue_id_,'[DEMO] '||name_,'STADIUM',
            jsonb_build_object('display_address',address_,'latitude',lat_,'longitude',lng_,
                'city_code',city_,'source','MANUAL_MAP_PIN','administrative_area',
                jsonb_build_array(jsonb_build_object('name','Việt Nam','level','COUNTRY','code','VN'),
                                  jsonb_build_object('name',city_name_,'level','CITY','code',city_))),
            jsonb_build_object('demo_dataset',dataset,'description','Địa điểm mẫu để xem giao diện, tọa độ minh họa.'),
            actor_id_,now()-interval '120 days'+v*interval '1 day',now()-v*interval '2 hours');

        overview_svg_:='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 700">'
            ||'<rect id="BACKGROUND" width="1000" height="700" rx="28" fill="#f8fafc"/>'
            ||'<rect id="CIRCULATION" x="70" y="70" width="860" height="560" rx="100" fill="#e2e8f0"/>'
            ||'<rect id="PITCH" x="300" y="250" width="400" height="200" rx="8" fill="#27845b"/>'
            ||'<path d="M500 250V450M300 250H700V450H300Z" fill="none" stroke="white" stroke-width="2"/>'
            ||'<circle cx="500" cy="350" r="36" fill="none" stroke="white" stroke-width="2"/>'
            ||'<rect id="AREA__A1" x="120" y="100" width="760" height="130" fill="#c7d2fe"/>'
            ||'<rect id="AREA__B1" x="120" y="480" width="760" height="130" fill="#bfdbfe"/>'
            ||'<rect id="AREA__GA" x="330" y="280" width="340" height="150" fill="none"/>'
            ||'<rect id="GATE__G1" x="45" y="320" width="35" height="60" fill="#0f172a"/>'
            ||'<rect id="GATE__G2" x="920" y="320" width="35" height="60" fill="#0f172a"/>'
            ||'<text id="GATE_LABEL__G1" x="25" y="305" font-size="16">G1</text>'
            ||'<text id="GATE_LABEL__G2" x="920" y="305" font-size="16">G2</text></svg>';
        INSERT INTO venue_overview(id,venue_id,name,svg_content,view_box_width,view_box_height,created_at)
        VALUES(overview_id_,venue_id_,'[DEMO] Tổng thể sân '||v,overview_svg_,1000,700,now()-interval '100 days');
        INSERT INTO venue_overview_area(id,overview_id,code,name,svg_id)
        VALUES(pg_temp.demo_id(300+v*10+1),overview_id_,'A1','Khán đài A','AREA__A1'),
              (pg_temp.demo_id(300+v*10+2),overview_id_,'B1','Khán đài B','AREA__B1'),
              (pg_temp.demo_id(300+v*10+3),overview_id_,'GA','Khu vực trung tâm','AREA__GA');
        INSERT INTO venue_gate(overview_id,code,name,svg_id,label_svg_id)
        VALUES(overview_id_,'G1','Cổng số 1','GATE__G1','GATE_LABEL__G1'),
              (overview_id_,'G2','Cổng số 2','GATE__G2','GATE_LABEL__G2');
        UPDATE venue_overview SET validation_hash=catalog_overview_hash(id),
            validation_engine='local-demo-ui-seed',validated_at=now()-interval '90 days' WHERE id=overview_id_;
        UPDATE venue_overview SET status='PUBLISHED',published_at=now()-interval '90 days' WHERE id=overview_id_;

        -- Một overview: FOOTBALL có 2 publish + 1 draft; CONCERT có 1 publish + 1 draft.
        FOR k IN 1..5 LOOP
            layout_id_:=pg_temp.demo_id(1000+v*10+k);
            layout_type_:=CASE WHEN k<=3 THEN 'FOOTBALL' ELSE 'CONCERT' END;
            INSERT INTO seat_layout(id,venue_id,overview_id,layout_type,name,created_at)
            VALUES(layout_id_,venue_id_,overview_id_,layout_type_,
                '[DEMO] '||CASE WHEN k<=3 THEN 'Bóng đá' ELSE 'Ca nhạc' END||' · Bản '||k,
                now()-interval '90 days'+k*interval '1 hour');
            svg_:='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 700">';
            FOR z IN 1..CASE WHEN k<=3 THEN 2 ELSE 3 END LOOP
                zone_id_:=pg_temp.demo_id(10000+v*100+k*10+z);
                code_:=(ARRAY['A1-T1','B1-T1','GA'])[z];
                y_:=CASE WHEN z=1 THEN 100 WHEN z=2 THEN 480 ELSE 280 END;
                label_:=(ARRAY['Khán đài A · VIP','Khán đài B · Phổ thông','Khu đứng GA'])[z];
                INSERT INTO zone(id,seat_layout_id,name,kind,capacity,code,stand_code,level,
                    svg_id,content_svg_id,pitch_side_svg_id,bounds_x,bounds_y,bounds_width,bounds_height,
                    overview_id,overview_area_id)
                VALUES(zone_id_,layout_id_,label_,CASE WHEN z=3 THEN 'STANDING' ELSE 'SEATED' END,
                    CASE WHEN z=3 THEN 180 ELSE 120 END,code_,(ARRAY['A1','B1','GA'])[z],1,
                    'ZONE__'||code_||CASE WHEN z=3 THEN '__STANDING__180' ELSE '__SEATED' END,
                    CASE WHEN z<>3 THEN 'CONTENT__'||code_ END,
                    CASE WHEN z<>3 THEN 'PITCH_SIDE__'||code_ END,
                    CASE WHEN z=3 THEN 330 ELSE 120 END,y_,CASE WHEN z=3 THEN 340 ELSE 760 END,
                    CASE WHEN z=3 THEN 150 ELSE 130 END,overview_id_,pg_temp.demo_id(300+v*10+z));
                svg_:=svg_||format('<rect id="ZONE__%s%s" x="%s" y="%s" width="%s" height="%s" rx="10" fill="%s"/>',
                    code_,CASE WHEN z=3 THEN '__STANDING__180' ELSE '__SEATED' END,
                    CASE WHEN z=3 THEN 330 ELSE 120 END,y_,CASE WHEN z=3 THEN 340 ELSE 760 END,
                    CASE WHEN z=3 THEN 150 ELSE 130 END,(ARRAY['#e0e7ff','#dbeafe','#fde68a'])[z]);
                IF z<>3 THEN
                    circles_:='';
                    FOR r IN 1..6 LOOP
                        FOR c IN 1..20 LOOP
                            INSERT INTO seat(zone_id,seat_type_id,row_label,number,x,y)
                            VALUES(zone_id_,standard_seat,chr(64+r),c,135+c*35,y_+10+r*16);
                            circles_:=circles_||format('<circle id="SEAT__%s__%s__%s" cx="%s" cy="%s" r="5" fill="%s"/>',
                                code_,chr(64+r),c,135+c*35,y_+10+r*16,CASE WHEN z=1 THEN '#6366f1' ELSE '#3b82f6' END);
                        END LOOP;
                    END LOOP;
                    svg_:=svg_||'<g id="CONTENT__'||code_||'">'||circles_
                        ||format('<path id="PITCH_SIDE__%s" d="M120 %sH880" stroke="#64748b" stroke-width="2"/>',
                            code_,CASE WHEN z=1 THEN 230 ELSE 480 END)||'</g>';
                END IF;
                svg_:=svg_||format('<text id="LABEL__%s" x="%s" y="%s" font-size="16" fill="#0f172a">%s</text>',
                    code_,CASE WHEN z=3 THEN 410 ELSE 140 END,y_-8,label_);
            END LOOP;
            INSERT INTO seat_layout_svg(seat_layout_id,schema_version,svg_content,view_box_x,view_box_y,view_box_width,view_box_height)
            VALUES(layout_id_,2,svg_||'</svg>',0,0,1000,700);
            IF k IN (1,2,4) THEN
                UPDATE seat_layout SET validation_hash=catalog_layout_hash(id),validation_engine='local-demo-ui-seed',
                    validated_at=now()-interval '85 days' WHERE id=layout_id_;
                UPDATE seat_layout SET status='PUBLISHED',published_at=now()-interval '85 days' WHERE id=layout_id_;
            END IF;
        END LOOP;
    END LOOP;

    FOR n IN 1..12 LOOP
        event_id_:=pg_temp.demo_id(30000+n); venue_n:=event_venues[n];
        layout_slot:=CASE WHEN n%2=1 THEN 1 ELSE 4 END;
        layout_id_:=pg_temp.demo_id(1000+venue_n*10+layout_slot);
        event_status_:=CASE WHEN n=11 THEN 'DRAFT' WHEN n=12 THEN 'CANCELLED'
                            WHEN n IN (9,10) THEN 'ENDED' ELSE 'PUBLISHED' END;
        INSERT INTO event(id,title,description,category,attributes,poster_url,poster_mobile_url,poster_desktop_url,
            status,max_tickets_per_order,created_at,updated_at,published_at,age_limit,venue_id,seat_layout_id)
        VALUES(event_id_,'[DEMO] '||titles[n],
            'Sự kiện minh họa để kiểm tra bố cục, trạng thái và dữ liệu admin. Không phải sự kiện bán vé thật.',
            CASE WHEN n%2=1 THEN 'FOOTBALL' ELSE 'CONCERT' END,
            jsonb_build_object('demo_dataset',dataset),
            '/demo/admin-ui/'||CASE WHEN n%2=1 THEN 'stadium.jpg' ELSE 'concert.jpg' END,
            '/demo/admin-ui/'||CASE WHEN n%2=1 THEN 'stadium.jpg' ELSE 'concert.jpg' END,
            '/demo/admin-ui/'||CASE WHEN n%2=1 THEN 'stadium.jpg' ELSE 'concert.jpg' END,
            event_status_,10,now()-interval '80 days',now()-n*interval '1 hour',
            CASE WHEN n<>11 THEN now()-interval '75 days' END,CASE WHEN n%2=0 THEN 12 ELSE 0 END,
            pg_temp.demo_id(100+venue_n),layout_id_);
        FOR z IN 1..CASE WHEN n%2=1 THEN 2 ELSE 3 END LOOP
            tier_id_:=pg_temp.demo_id(31000+n*10+z);
            INSERT INTO price_tier(id,event_id,name,price,color)
            VALUES(tier_id_,event_id_,(ARRAY['VIP','Phổ thông','GA'])[z],
                CASE WHEN n%2=1 THEN (ARRAY[350000,180000,100000])[z]
                     ELSE (ARRAY[900000,450000,250000])[z] END,
                (ARRAY['#6366f1','#3b82f6','#f59e0b'])[z]);
            INSERT INTO zone_price(event_id,zone_id,price_tier_id,display_name)
            VALUES(event_id_,pg_temp.demo_id(10000+venue_n*100+layout_slot*10+z),tier_id_,
                (ARRAY['Khán đài A · VIP','Khán đài B · Phổ thông','Khu đứng GA'])[z]);
        END LOOP;
        starts_:=(date_trunc('day',now() AT TIME ZONE 'Asia/Ho_Chi_Minh')+interval '19 hours')
            AT TIME ZONE 'Asia/Ho_Chi_Minh';
        starts_:=starts_+CASE WHEN n=9 THEN interval '-3 days' WHEN n=10 THEN interval '-15 days'
                             WHEN n=8 THEN interval '1 day' ELSE (n*2+1)*interval '1 day' END;
        opens_:=CASE WHEN n=7 THEN now()+interval '2 days' ELSE now()-interval '75 days' END;
        closes_:=CASE WHEN n=8 THEN now()-interval '2 hours' ELSE starts_ END;
        FOR j IN 1..CASE WHEN n IN (2,4) THEN 2 ELSE 1 END LOOP
            show_id_:=pg_temp.demo_id(40000+n*10+j);
            INSERT INTO showtime(id,event_id,seat_layout_id,starts_at,ends_at,sale_opens_at,sale_closes_at,status,created_at)
            VALUES(show_id_,event_id_,layout_id_,starts_+(j-1)*interval '2 days',
                starts_+(j-1)*interval '2 days'+interval '2 hours',opens_,closes_+(j-1)*interval '2 days',
                CASE WHEN n=11 THEN 'DRAFT' WHEN n=12 THEN 'CANCELLED' ELSE 'SCHEDULED' END,
                now()-interval '75 days');
            IF n<>11 THEN
                INSERT INTO showtime_seat(showtime_id,seat_id)
                SELECT show_id_,s.id FROM seat s JOIN zone zz ON zz.id=s.zone_id WHERE zz.seat_layout_id=layout_id_;
                INSERT INTO showtime_zone_stock(showtime_id,zone_id,capacity)
                SELECT show_id_,id,capacity FROM zone WHERE seat_layout_id=layout_id_ AND kind='STANDING';
            END IF;
        END LOOP;
    END LOOP;

    -- 144 PAID, 8 PENDING, 5 CANCELLED, 5 EXPIRED, 4 NEEDS_REVIEW.
    FOR n IN 1..166 LOOP
        event_n:=sale_events[1+(n-1)%array_length(sale_events,1)];
        event_id_:=pg_temp.demo_id(30000+event_n); show_id_:=pg_temp.demo_id(40000+event_n*10+1);
        order_id_:=pg_temp.demo_id(50000+n); user_id_:=pg_temp.demo_id(1+(n-1)%24);
        SELECT starts_at INTO starts_ FROM showtime WHERE id=show_id_;
        order_status_:=CASE WHEN n<=144 THEN 'PAID' WHEN n<=152 THEN 'PENDING' WHEN n<=157 THEN 'CANCELLED'
                            WHEN n<=162 THEN 'EXPIRED' ELSE 'NEEDS_REVIEW' END;
        IF order_status_='PENDING' AND event_n IN (9,10) THEN
            event_n:=2; event_id_:=pg_temp.demo_id(30002); show_id_:=pg_temp.demo_id(40021);
            SELECT starts_at INTO starts_ FROM showtime WHERE id=show_id_;
        END IF;
        paid_:=least(now()-(1+(n*7)%59)*interval '1 day',starts_-interval '1 day');
        created_:=CASE WHEN order_status_='PENDING' THEN now()-interval '1 minute'
                      WHEN order_status_='NEEDS_REVIEW' THEN paid_-interval '15 minutes'
                      ELSE paid_-interval '5 minutes' END;
        expires_:=created_+interval '10 minutes';
        INSERT INTO orders(id,code,user_id,showtime_id,status,total_amount,expires_at,buyer_name,buyer_email,
            paid_at,review_reason,created_at,updated_at)
        SELECT order_id_,'D'||lpad(n::text,5,'0'),user_id_,show_id_,order_status_,0,expires_,u.full_name,u.email,
            CASE WHEN order_status_ IN ('PAID','NEEDS_REVIEW') THEN paid_ END,
            CASE WHEN order_status_='NEEDS_REVIEW' THEN 'PAID_AFTER_EXPIRY' END,created_,
            CASE WHEN order_status_='PENDING' THEN created_ ELSE paid_ END
        FROM users u WHERE u.id=user_id_;
        INSERT INTO order_status_history(order_id,from_status,to_status,actor_id,at)
        VALUES(order_id_,NULL,'PENDING',user_id_,created_);
        IF order_status_<>'PENDING' THEN
            INSERT INTO order_status_history(order_id,from_status,to_status,actor_id,reason,at)
            VALUES(order_id_,'PENDING',order_status_,CASE WHEN order_status_='CANCELLED' THEN user_id_ END,
                '[DEMO] Lịch sử trạng thái mô phỏng',paid_);
        END IF;
        venue_n:=event_venues[event_n]; layout_slot:=CASE WHEN event_n%2=1 THEN 1 ELSE 4 END;
        zone_n:=CASE WHEN event_n%2=0 AND n%3=0 THEN 3 ELSE 1+n%2 END;
        zone_id_:=pg_temp.demo_id(10000+venue_n*100+layout_slot*10+zone_n);
        tier_id_:=pg_temp.demo_id(31000+event_n*10+zone_n);
        SELECT price INTO price_ FROM price_tier WHERE id=tier_id_;
        q:=CASE WHEN zone_n=3 THEN 3 ELSE 2 END; total_:=q*price_;
        IF zone_n=3 THEN
            INSERT INTO order_item(order_id,zone_id,price_tier_id,quantity,unit_price)
            VALUES(order_id_,zone_id_,tier_id_,q,price_) RETURNING id INTO item_id_;
            IF order_status_ IN ('PAID','PENDING') THEN
                UPDATE showtime_zone_stock SET sold=sold+CASE WHEN order_status_='PAID' THEN q ELSE 0 END,
                    held=held+CASE WHEN order_status_='PENDING' THEN q ELSE 0 END
                WHERE showtime_id=show_id_ AND zone_id=zone_id_;
            END IF;
        END IF;
        FOR j IN 1..q LOOP
            seat_id_:=NULL;
            IF zone_n<>3 THEN
                SELECT ss.seat_id INTO STRICT seat_id_ FROM showtime_seat ss JOIN seat s ON s.id=ss.seat_id
                WHERE ss.showtime_id=show_id_ AND s.zone_id=zone_id_ AND ss.status='AVAILABLE'
                ORDER BY s.row_label,s.number LIMIT 1
                OFFSET CASE WHEN order_status_ IN ('PAID','PENDING') THEN 0 ELSE j-1 END;
                INSERT INTO order_item(order_id,zone_id,seat_id,price_tier_id,quantity,unit_price)
                VALUES(order_id_,zone_id_,seat_id_,tier_id_,1,price_) RETURNING id INTO item_id_;
                IF order_status_ IN ('PAID','PENDING') THEN
                    UPDATE showtime_seat SET status=CASE WHEN order_status_='PAID' THEN 'SOLD' ELSE 'HELD' END,
                        order_id=order_id_,held_until=CASE WHEN order_status_='PENDING' THEN expires_ END
                    WHERE showtime_id=show_id_ AND seat_id=seat_id_;
                END IF;
            END IF;
            IF order_status_='PAID' THEN
                ticket_status_:=CASE WHEN starts_<now() AND n%3<>0 THEN 'USED'
                                     WHEN n IN (1,2) AND j=1 THEN 'VOID' ELSE 'ISSUED' END;
                INSERT INTO ticket(order_item_id,seq,showtime_id,zone_id,seat_id,code,qr_token,status,issued_at,used_at,voided_at)
                VALUES(item_id_,CASE WHEN zone_n=3 THEN j ELSE 1 END,show_id_,zone_id_,seat_id_,
                    'DEMO-'||lpad(n::text,4,'0')||'-'||j,
                    encode(sha256(convert_to(dataset||':'||n||':'||j||':'||uuidv7()::text,'UTF8')),'hex'),
                    ticket_status_,paid_,CASE WHEN ticket_status_='USED' THEN starts_-interval '30 minutes' END,
                    CASE WHEN ticket_status_='VOID' THEN now()-interval '1 hour' END) RETURNING id INTO ticket_id_;
                IF ticket_status_='USED' THEN
                    INSERT INTO checkin(ticket_id,showtime_id,scanned_by,gate,result,scanned_at)
                    VALUES(ticket_id_,show_id_,pg_temp.demo_id(32),'G1','OK',starts_-interval '30 minutes');
                END IF;
            END IF;
        END LOOP;
        UPDATE orders SET total_amount=total_ WHERE id=order_id_;
        IF order_status_ IN ('PAID','NEEDS_REVIEW','EXPIRED') THEN
            INSERT INTO payment_intent(order_id,txn_ref,amount,status,created_at,updated_at,expires_at)
            VALUES(order_id_,'DEMO-UI-'||n,total_,CASE WHEN order_status_='EXPIRED' THEN 'EXPIRED' ELSE 'SUCCEEDED' END,
                created_,paid_,expires_) RETURNING id INTO intent_id_;
            IF order_status_<>'EXPIRED' THEN
                INSERT INTO payment_transaction(payment_intent_id,source,result,response_code,gateway_txn_no,amount,raw,received_at)
                VALUES(intent_id_,'IPN','SUCCESS','00','DEMO-UI-'||n,total_,
                    jsonb_build_object('demo',true,'dataset',dataset,'note','Mô phỏng UI, không phải giao dịch VNPay thật'),paid_);
            END IF;
        END IF;
    END LOOP;

    -- Ghế khóa để xem trạng thái trên bản đồ suất đang bán.
    UPDATE showtime_seat ss SET status='BLOCKED'
    WHERE ss.showtime_id=pg_temp.demo_id(40011) AND ss.seat_id IN (
        SELECT s.id FROM seat s JOIN zone zz ON zz.id=s.zone_id
        WHERE zz.id=pg_temp.demo_id(10111) AND s.row_label='F' AND s.number>=17);
    INSERT INTO catalog_seat_block_log(showtime_id,seat_ids,action,reason,occurred_at)
    SELECT showtime_id,array_agg(seat_id),'BLOCK','[DEMO] Ghế dành cho kỹ thuật',now()
    FROM showtime_seat WHERE showtime_id=pg_temp.demo_id(40011) AND status='BLOCKED'
    GROUP BY showtime_id;

    -- Audit mô phỏng được đánh dấu rõ trong reason và snapshot, tách khỏi thao tác thật.
    FOR n IN 1..36 LOOP
        operation_:=operations[1+(n-1)%array_length(operations,1)];
        actor_id_:=pg_temp.demo_id(31+n%2);
        IF operation_ IN ('createVenue','updateVenue') THEN
            user_id_:=pg_temp.demo_id(101+(n%5)); SELECT name INTO label_ FROM venue WHERE id=user_id_;
        ELSIF operation_='publishSeatLayout' THEN
            user_id_:=pg_temp.demo_id(1011+(n%5)*10); SELECT name INTO label_ FROM seat_layout WHERE id=user_id_;
        ELSIF operation_='adminUpdateUser' THEN
            user_id_:=pg_temp.demo_id(1+n%24); SELECT full_name INTO label_ FROM users WHERE id=user_id_;
        ELSE
            user_id_:=pg_temp.demo_id(30001+n%8); SELECT title INTO label_ FROM event WHERE id=user_id_;
        END IF;
        INSERT INTO audit_log(actor_id,action,entity,entity_id,reason,at,after)
        VALUES(actor_id_,'ADMIN_ACTIVITY','admin_activity',actor_id_,
            '[DEMO] Nhật ký mô phỏng để xem giao diện',now()-n*interval '5 minutes',
            jsonb_build_object('demo',true,'dataset',dataset,'operation',operation_,
                'method',CASE WHEN operation_ IN ('updateVenue','adminUpdateUser') THEN 'PATCH'
                              WHEN operation_ IN ('putPriceTiers','putZonePrices') THEN 'PUT' ELSE 'POST' END,
                'route','/api/v1/admin/demo','outcome',CASE WHEN n%11=0 THEN 'FAILED' ELSE 'SUCCEEDED' END,
                'status',CASE WHEN n%11=0 THEN 409 ELSE 200 END,
                'target_ids',jsonb_build_object('id',user_id_),'result_label',label_));
    END LOOP;
    INSERT INTO audit_log(actor_id,action,entity,entity_id,reason,at,before,after)
    VALUES(pg_temp.demo_id(31),'UPDATE_ACCOUNT','users',pg_temp.demo_id(28),'[DEMO] Khóa tài khoản mẫu',
        now()-interval '4 hours','{"status":"ACTIVE","admin_locked":false}',
        '{"status":"LOCKED","admin_locked":true,"demo":true}');
    RAISE NOTICE 'Đã thêm bộ DEMO: 5 sân, 25 layout, 12 sự kiện, 14 suất, 32 người dùng, 166 đơn.';
END;
$seed$;

-- Hai địa điểm bổ sung để xem trạng thái chỉ có nháp và chưa có sơ đồ.
DO $edges$
DECLARE
    v int; overview_ uuid; layout_ uuid; zone_ uuid; standard_ uuid; svg_ text; n int;
BEGIN
    IF current_database()<>'vingo_admin_ui_20261118' THEN RAISE EXCEPTION 'Sai DB dev'; END IF;
    SELECT id INTO STRICT standard_ FROM seat_type WHERE code='STANDARD';
    FOR v IN 6..7 LOOP
        IF EXISTS(SELECT 1 FROM venue WHERE id=pg_temp.demo_id(100+v)) THEN CONTINUE; END IF;
        INSERT INTO venue(id,name,type,location,info,updated_by,created_at,updated_at)
        VALUES(pg_temp.demo_id(100+v),
            CASE WHEN v=6 THEN '[DEMO] Nhà hát Sông Hồng · Chỉ có nháp' ELSE '[DEMO] Quảng trường Ánh Dương · Chưa có sơ đồ' END,
            CASE WHEN v=6 THEN 'THEATER' ELSE 'OUTDOOR' END,
            jsonb_build_object('display_address',CASE WHEN v=6 THEN 'Địa điểm mẫu tại Hà Nội' ELSE 'Địa điểm mẫu tại Đà Nẵng' END,
                'latitude',CASE WHEN v=6 THEN 21.024 ELSE 16.061 END,
                'longitude',CASE WHEN v=6 THEN 105.856 ELSE 108.226 END,
                'city_code',CASE WHEN v=6 THEN 'HN' ELSE 'DN' END,'source','MANUAL_MAP_PIN',
                'administrative_area',jsonb_build_array(jsonb_build_object('level','CITY',
                    'name',CASE WHEN v=6 THEN 'Hà Nội' ELSE 'Đà Nẵng' END,
                    'code',CASE WHEN v=6 THEN 'HN' ELSE 'DN' END))),
            '{"demo_dataset":"admin-ui-20261119-v1","description":"Địa điểm hư cấu để xem trạng thái trống/nháp"}',
            pg_temp.demo_id(31),now()-interval '2 days',now()-interval '2 hours');
        IF v=6 THEN
            overview_:=pg_temp.demo_id(206); layout_:=pg_temp.demo_id(1064); zone_:=pg_temp.demo_id(10641);
            INSERT INTO venue_overview(id,venue_id,name,svg_content,view_box_width,view_box_height)
            SELECT overview_,pg_temp.demo_id(106),'[DEMO] Tổng thể chưa công bố',svg_content,1000,700
            FROM venue_overview WHERE id=pg_temp.demo_id(201);
            INSERT INTO venue_overview_area(overview_id,code,name,svg_id)
            VALUES(overview_,'A1','Khán đài A','AREA__A1');
            INSERT INTO venue_gate(overview_id,code,name,svg_id,label_svg_id)
            VALUES(overview_,'G1','Cổng số 1','GATE__G1','GATE_LABEL__G1'),
                  (overview_,'G2','Cổng số 2','GATE__G2','GATE_LABEL__G2');
            INSERT INTO seat_layout(id,venue_id,overview_id,layout_type,name)
            VALUES(layout_,pg_temp.demo_id(106),overview_,'CONCERT','[DEMO] Ca nhạc · Đang soạn');
            INSERT INTO zone(id,seat_layout_id,name,kind,capacity,code,stand_code,level,
                svg_id,content_svg_id,pitch_side_svg_id,bounds_x,bounds_y,bounds_width,bounds_height,overview_id,overview_area_id)
            SELECT zone_,layout_,'Khán đài A · Nháp','SEATED',20,'A1-T1','A1',1,
                'ZONE__A1-T1__SEATED','CONTENT__A1-T1','PITCH_SIDE__A1-T1',120,100,760,130,overview_,id
            FROM venue_overview_area WHERE overview_id=overview_ AND code='A1';
            svg_:='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 700">'
                ||'<rect id="ZONE__A1-T1__SEATED" x="120" y="100" width="760" height="130" fill="#e0e7ff"/>'
                ||'<g id="CONTENT__A1-T1"><path id="PITCH_SIDE__A1-T1" d="M120 230H880" stroke="#64748b"/>';
            FOR n IN 1..20 LOOP
                INSERT INTO seat(zone_id,seat_type_id,row_label,number,x,y) VALUES(zone_,standard_,'A',n,135+n*35,160);
                svg_:=svg_||format('<circle id="SEAT__A1-T1__A__%s" cx="%s" cy="160" r="5" fill="#6366f1"/>',n,135+n*35);
            END LOOP;
            INSERT INTO seat_layout_svg(seat_layout_id,schema_version,svg_content,view_box_x,view_box_y,view_box_width,view_box_height)
            VALUES(layout_,2,svg_||'</g></svg>',0,0,1000,700);
        END IF;
    END LOOP;
END;
$edges$;

COMMIT;

SELECT 'venue' AS dataset,count(*) AS records FROM venue WHERE info->>'demo_dataset'='admin-ui-20261119-v1'
UNION ALL SELECT 'layout',count(*) FROM seat_layout l JOIN venue v ON v.id=l.venue_id WHERE v.info->>'demo_dataset'='admin-ui-20261119-v1'
UNION ALL SELECT 'event',count(*) FROM event WHERE attributes->>'demo_dataset'='admin-ui-20261119-v1'
UNION ALL SELECT 'showtime',count(*) FROM showtime s JOIN event e ON e.id=s.event_id WHERE e.attributes->>'demo_dataset'='admin-ui-20261119-v1'
UNION ALL SELECT 'user',count(*) FROM users WHERE email LIKE 'demo.%@example.test'
UNION ALL SELECT 'order',count(*) FROM orders WHERE id::text LIKE '019b1900-%'
UNION ALL SELECT 'ticket',count(*) FROM ticket WHERE code LIKE 'DEMO-%';
