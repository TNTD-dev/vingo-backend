-- Dữ liệu DEMO cho giao diện admin, schema v2.
-- Chỉ chạy trên DB local vingo, vingo_admin_ui_20261118 hoặc vingo_admin_ui_v2. Không xóa/ghi đè dữ liệu hiện có.
-- Toàn bộ trong một transaction; chạy lại sẽ bỏ qua nếu bộ DEMO này đã tồn tại.
-- Đây là fixture UI: payment/audit được mô phỏng, không gọi VNPay/email.
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
    dataset constant text := 'admin-ui-20261119-v2';
    credential text;
    v int; k int; z int; r int; c int; n int; q int; j int; event_n int; venue_n int;
    layout_slot int; zone_n int; order_n int;
    venue_id_ uuid; overview_id_ uuid; layout_id_ uuid; zone_id_ uuid;
    event_id_ uuid; show_id_ uuid; user_id_ uuid; order_id_ uuid; seat_id_ uuid;
    tier_id_ uuid; price_id_ uuid; ticket_id_ uuid; intent_id_ uuid; actor_id_ uuid;
    layout_type_ text; event_status_ event_status; order_status_ order_status; ticket_status_ ticket_status;
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
    operations text[] := ARRAY['createVenue','updateVenue','completeVenueImport','createEvent',
        'publishEvent','putPriceTiers','putZonePrices','adminUpdateUser'];
BEGIN
    IF current_database() NOT IN ('vingo', 'vingo_admin_ui_20261118', 'vingo_admin_ui_v2') THEN
        RAISE EXCEPTION 'Chỉ được seed trên DB local';
    END IF;
    IF EXISTS (SELECT 1 FROM venue WHERE id=pg_temp.demo_id(101)) THEN
        RAISE NOTICE 'Bộ DEMO đã tồn tại; giữ nguyên dữ liệu, không seed trùng.';
        RETURN;
    END IF;
    SELECT password_hash INTO credential FROM user_account WHERE email='admin@vingo.local';
    IF credential IS NULL THEN RAISE EXCEPTION 'Chạy scripts/seed.sql trước để tạo admin@vingo.local'; END IF;
    IF EXISTS (SELECT 1 FROM user_account WHERE email LIKE 'demo.%@example.test') THEN
        RAISE EXCEPTION 'Đã có email demo trùng; dừng để không ghi đè tài khoản';
    END IF;

    -- Tài khoản DEMO dùng hash Argon2 hiện có, không chứa mật khẩu thô trong file.
    FOR n IN 1..32 LOOP
        name_ := CASE WHEN n=31 THEN 'DEMO · Nguyễn An (Quản trị)'
                      WHEN n=32 THEN 'DEMO · Trần Bình (Vận hành)'
                      ELSE names[1+(n-1)%array_length(names,1)] || CASE WHEN n>16 THEN ' '||n ELSE '' END END;
        INSERT INTO user_account(id,email,full_name,password_hash,role,email_verified_at,admin_locked,
                                 locked_until,deleted_at,created_at,updated_at)
        VALUES(pg_temp.demo_id(n),'demo.'||lpad(n::text,2,'0')||'@example.test',name_,CASE WHEN n<>30 THEN credential END,
            CASE WHEN n>30 THEN 'ADMIN'::user_role ELSE 'BUYER'::user_role END,
            CASE WHEN n NOT IN (25,26,27) THEN now()-interval '75 days' END,
            n=28,CASE WHEN n=29 THEN now()+interval '15 minutes' END,
            CASE WHEN n=30 THEN now()-interval '1 hour' END,
            CASE WHEN n<=24 THEN now()-interval '70 days'+n*interval '4 hours'
                 ELSE now()-(1+(n*11)%65)*interval '1 day' END,now()-n*interval '1 hour');
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
        INSERT INTO venue(id,name,address,city,lat,lng,rules,created_at,updated_at)
        VALUES(venue_id_,'[DEMO] '||name_,address_,city_,lat_,lng_,
            'Địa điểm mẫu để xem giao diện, tọa độ minh họa.',
            now()-interval '120 days'+v*interval '1 day',now()-v*interval '2 hours');

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
        INSERT INTO venue_map(id,venue_id,version,svg,created_at)
        VALUES(overview_id_,venue_id_,1,overview_svg_,now()-interval '100 days');

        -- Các phiên bản đã lưu đều bất biến. Phiên nhập dở được tạo riêng.
        FOR k IN 1..5 LOOP
            layout_id_:=pg_temp.demo_id(1000+v*10+k);
            layout_type_:=CASE WHEN k<=3 THEN 'FOOTBALL' ELSE 'CONCERT' END;
            svg_:='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 700">';
            FOR z IN 1..CASE WHEN k<=3 THEN 2 ELSE 3 END LOOP
                zone_id_:=pg_temp.demo_id(10000+v*100+k*10+z);
                code_:=(ARRAY['A1-T1','B1-T1','GA'])[z];
                y_:=CASE WHEN z=1 THEN 100 WHEN z=2 THEN 480 ELSE 280 END;
                label_:=(ARRAY['Khán đài A · VIP','Khán đài B · Phổ thông','Khu đứng GA'])[z];
                svg_:=svg_||format('<rect id="ZONE__%s%s" x="%s" y="%s" width="%s" height="%s" rx="10" fill="%s"/>',
                    code_,CASE WHEN z=3 THEN '__STANDING__180' ELSE '__SEATED' END,
                    CASE WHEN z=3 THEN 330 ELSE 120 END,y_,CASE WHEN z=3 THEN 340 ELSE 760 END,
                    CASE WHEN z=3 THEN 150 ELSE 130 END,(ARRAY['#e0e7ff','#dbeafe','#fde68a'])[z]);
                IF z<>3 THEN
                    circles_:='';
                    FOR r IN 1..6 LOOP
                        FOR c IN 1..20 LOOP
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
            INSERT INTO seat_map(id,venue_id,venue_map_id,version,layout_type,name,svg,view_box,created_at)
            VALUES(layout_id_,venue_id_,overview_id_,k,layout_type_,
                '[DEMO] '||CASE WHEN k<=3 THEN 'Bóng đá' ELSE 'Ca nhạc' END||' · Bản '||k,
                svg_||'</svg>',ARRAY[0,0,1000,700],now()-interval '90 days'+k*interval '1 hour');
            FOR z IN 1..CASE WHEN k<=3 THEN 2 ELSE 3 END LOOP
                zone_id_:=pg_temp.demo_id(10000+v*100+k*10+z);
                INSERT INTO zone(id,seat_map_id,name,kind,capacity,code)
                VALUES(zone_id_,layout_id_,(ARRAY['Khán đài A · VIP','Khán đài B · Phổ thông','Khu đứng GA'])[z],
                    CASE WHEN z=3 THEN 'STANDING'::zone_kind ELSE 'SEATED'::zone_kind END,
                    CASE WHEN z=3 THEN 180 END,(ARRAY['A1-T1','B1-T1','GA'])[z]);
                IF z<>3 THEN
                    INSERT INTO seat(seat_map_id,zone_id,row_label,number)
                    SELECT layout_id_,zone_id_,chr(64+row_no),seat_no
                    FROM generate_series(1,6) AS row_no CROSS JOIN generate_series(1,20) AS seat_no;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    FOR n IN 1..12 LOOP
        event_id_:=pg_temp.demo_id(30000+n); venue_n:=event_venues[n];
        layout_slot:=CASE WHEN n%2=1 THEN 1 ELSE 4 END;
        layout_id_:=pg_temp.demo_id(1000+venue_n*10+layout_slot);
        event_status_:=CASE WHEN n=11 THEN 'DRAFT' WHEN n=12 THEN 'CANCELLED'
                            ELSE 'PUBLISHED' END;
        INSERT INTO event(id,title,description,category,home_team,away_team,league,round,
                          poster_mobile_url,poster_desktop_url,status,max_tickets_per_order,
                          created_at,updated_at,seat_map_id)
        VALUES(event_id_,'[DEMO] '||titles[n],
            'Sự kiện minh họa để kiểm tra bố cục, trạng thái và dữ liệu admin. Không phải sự kiện bán vé thật.',
            CASE WHEN n%2=1 THEN 'FOOTBALL' ELSE 'CONCERT' END,
            CASE WHEN n%2=1 THEN split_part(titles[n],' — ',1) END,
            CASE WHEN n%2=1 THEN nullif(split_part(titles[n],' — ',2),'') END,
            CASE WHEN n%2=1 THEN 'Giải giao hữu' END,
            CASE WHEN n%2=1 THEN 'Vòng '||n END,
            '/demo/admin-ui/'||CASE WHEN n%2=1 THEN 'stadium.jpg' ELSE 'concert.jpg' END,
            '/demo/admin-ui/'||CASE WHEN n%2=1 THEN 'stadium.jpg' ELSE 'concert.jpg' END,
            event_status_,10,now()-interval '80 days',now()-n*interval '1 hour',layout_id_);
        FOR z IN 1..CASE WHEN n%2=1 THEN 2 ELSE 3 END LOOP
            tier_id_:=pg_temp.demo_id(31000+n*10+z);
            INSERT INTO ticket_type(id,event_id,name,color)
            VALUES(tier_id_,event_id_,(ARRAY['VIP','Phổ thông','GA'])[z],
                (ARRAY['#6366f1','#3b82f6','#f59e0b'])[z]);
            INSERT INTO ticket_type_price(id,ticket_type_id,price,effective_from,created_by,reason)
            VALUES(pg_temp.demo_id(32000+n*10+z),tier_id_,
                CASE WHEN n%2=1 THEN (ARRAY[350000,180000,100000])[z]
                     ELSE (ARRAY[900000,450000,250000])[z] END,
                now()-interval '80 days',pg_temp.demo_id(31),'[DEMO] Giá mở bán');
            INSERT INTO event_zone(event_id,zone_id,seat_map_id,ticket_type_id,display_name)
            VALUES(event_id_,pg_temp.demo_id(10000+venue_n*100+layout_slot*10+z),layout_id_,tier_id_,
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
            INSERT INTO showtime(id,event_id,seat_map_id,starts_at,ends_at,sale_opens_at,sale_closes_at,cancelled_at,created_at)
            VALUES(show_id_,event_id_,layout_id_,starts_+(j-1)*interval '2 days',
                starts_+(j-1)*interval '2 days'+interval '2 hours',opens_,closes_+(j-1)*interval '2 days',
                CASE WHEN n=12 THEN now()-interval '1 day' END,
                now()-interval '75 days');
            IF n<>11 THEN
                INSERT INTO seat_inventory(showtime_id,seat_id,zone_id,seat_map_id)
                SELECT show_id_,s.id,s.zone_id,s.seat_map_id FROM seat s WHERE s.seat_map_id=layout_id_;
                INSERT INTO standing_inventory(showtime_id,zone_id,seat_map_id,capacity)
                SELECT show_id_,id,seat_map_id,capacity FROM zone WHERE seat_map_id=layout_id_ AND kind='STANDING';
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
        INSERT INTO ticket_order(id,code,user_id,showtime_id,status,total_amount,expires_at,buyer_name,buyer_email,
            paid_at,buyer_phone,created_at,updated_at)
        SELECT order_id_,'D'||lpad(n::text,5,'0'),user_id_,show_id_,order_status_,0,expires_,u.full_name,u.email,
            CASE WHEN order_status_ IN ('PAID','NEEDS_REVIEW') THEN paid_ END,
            '+8491'||lpad(n::text,7,'0'),created_,
            CASE WHEN order_status_='PENDING' THEN created_ ELSE paid_ END
        FROM user_account u WHERE u.id=user_id_;
        INSERT INTO order_status_history(order_id,from_status,to_status,actor_id,at)
        VALUES(order_id_,NULL,'PENDING',user_id_,created_);
        IF order_status_<>'PENDING' THEN
            INSERT INTO order_status_history(order_id,from_status,to_status,actor_id,reason,at)
            VALUES(order_id_,'PENDING',order_status_,CASE WHEN order_status_='CANCELLED' THEN user_id_ END,
                CASE WHEN order_status_='NEEDS_REVIEW' THEN 'PAID_AFTER_EXPIRY' ELSE '[DEMO] Lịch sử trạng thái mô phỏng' END,paid_);
        END IF;
        venue_n:=event_venues[event_n]; layout_slot:=CASE WHEN event_n%2=1 THEN 1 ELSE 4 END;
        zone_n:=CASE WHEN event_n%2=0 AND n%3=0 THEN 3 ELSE 1+n%2 END;
        zone_id_:=pg_temp.demo_id(10000+venue_n*100+layout_slot*10+zone_n);
        tier_id_:=pg_temp.demo_id(31000+event_n*10+zone_n);
        SELECT id,price INTO STRICT price_id_,price_ FROM ticket_type_price WHERE ticket_type_id=tier_id_;
        q:=CASE WHEN zone_n=3 THEN 3 ELSE 2 END; total_:=q*price_;
        IF zone_n=3 THEN
            INSERT INTO order_standing(order_id,showtime_id,zone_id,ticket_type_price_id,quantity,unit_price)
            VALUES(order_id_,show_id_,zone_id_,price_id_,q,price_);
            IF order_status_ IN ('PAID','PENDING') THEN
                UPDATE standing_inventory SET sold=sold+CASE WHEN order_status_='PAID' THEN q ELSE 0 END,
                    held=held+CASE WHEN order_status_='PENDING' THEN q ELSE 0 END
                WHERE showtime_id=show_id_ AND zone_id=zone_id_;
            END IF;
        END IF;
        FOR j IN 1..q LOOP
            seat_id_:=NULL;
            IF zone_n<>3 THEN
                SELECT ss.seat_id INTO STRICT seat_id_ FROM seat_inventory ss JOIN seat s ON s.id=ss.seat_id
                WHERE ss.showtime_id=show_id_ AND s.zone_id=zone_id_ AND ss.status='AVAILABLE'
                ORDER BY s.row_label,s.number LIMIT 1
                OFFSET CASE WHEN order_status_ IN ('PAID','PENDING') THEN 0 ELSE j-1 END;
                INSERT INTO order_seat(order_id,showtime_id,seat_id,ticket_type_price_id,unit_price)
                VALUES(order_id_,show_id_,seat_id_,price_id_,price_);
                IF order_status_ IN ('PAID','PENDING') THEN
                    UPDATE seat_inventory SET status=CASE WHEN order_status_='PAID' THEN 'SOLD'::seat_status ELSE 'HELD'::seat_status END,
                        order_id=order_id_,held_until=CASE WHEN order_status_='PENDING' THEN expires_ END
                    WHERE showtime_id=show_id_ AND seat_id=seat_id_;
                END IF;
            END IF;
            IF order_status_='PAID' THEN
                ticket_status_:=CASE WHEN starts_<now() AND n%3<>0 THEN 'USED'
                                     WHEN n IN (1,2) AND j=1 THEN 'VOID' ELSE 'ISSUED' END;
                INSERT INTO ticket(order_id,showtime_id,seat_id,zone_id,seq,code,status,used_at)
                VALUES(order_id_,show_id_,seat_id_,CASE WHEN zone_n=3 THEN zone_id_ END,
                    CASE WHEN zone_n=3 THEN j END,'DEMO-'||lpad(n::text,4,'0')||'-'||j,
                    ticket_status_,CASE WHEN ticket_status_='USED' THEN starts_-interval '30 minutes' END)
                RETURNING id INTO ticket_id_;
                IF ticket_status_='VOID' THEN
                    INSERT INTO audit_log(actor_id,action,entity,entity_id,reason,at)
                    VALUES(pg_temp.demo_id(31),'VOID_TICKET','ticket',ticket_id_,'[DEMO] Vé huỷ mẫu',now()-interval '1 hour');
                END IF;
                IF ticket_status_='USED' THEN
                    INSERT INTO ticket_scan(ticket_id,showtime_id,scanned_by,gate_code,result,scanned_at)
                    VALUES(ticket_id_,show_id_,pg_temp.demo_id(32),'G1','OK',starts_-interval '30 minutes');
                END IF;
            END IF;
        END LOOP;
        UPDATE ticket_order SET total_amount=total_ WHERE id=order_id_;
        IF order_status_ IN ('PAID','NEEDS_REVIEW','EXPIRED') THEN
            INSERT INTO payment(order_id,txn_ref,payment_url,status,created_at,updated_at)
            VALUES(order_id_,'DEMO-UI-'||n,'https://example.test/payment/'||n,
                CASE WHEN order_status_='EXPIRED' THEN 'EXPIRED'::payment_status ELSE 'SUCCEEDED'::payment_status END,
                created_,paid_) RETURNING id INTO intent_id_;
            IF order_status_<>'EXPIRED' THEN
                INSERT INTO payment_gateway_log(payment_id,source,result,response_code,raw,received_at)
                VALUES(intent_id_,'IPN','SUCCESS','00',
                    jsonb_build_object('demo',true,'dataset',dataset,'vnp_TransactionNo','DEMO-UI-'||n,
                                       'vnp_Amount',total_*100),paid_);
            END IF;
        END IF;
    END LOOP;

    -- Ghế khóa để xem trạng thái trên bản đồ suất đang bán.
    UPDATE seat_inventory ss SET status='BLOCKED'
    WHERE ss.showtime_id=pg_temp.demo_id(40011) AND ss.status='AVAILABLE' AND ss.seat_id IN (
        SELECT s.id FROM seat s JOIN zone zz ON zz.id=s.zone_id
        WHERE zz.id=pg_temp.demo_id(10111) AND s.row_label='F' AND s.number>=17);
    INSERT INTO audit_log(actor_id,action,entity,entity_id,reason,at,after)
    SELECT pg_temp.demo_id(31),'BLOCK_SEATS','showtime',showtime_id,'[DEMO] Ghế dành cho kỹ thuật',now(),
           jsonb_build_object('seat_ids',array_agg(seat_id))
    FROM seat_inventory WHERE showtime_id=pg_temp.demo_id(40011) AND status='BLOCKED'
    GROUP BY showtime_id;

    -- Audit mô phỏng được đánh dấu rõ trong reason và snapshot, tách khỏi thao tác thật.
    FOR n IN 1..36 LOOP
        operation_:=operations[1+(n-1)%array_length(operations,1)];
        actor_id_:=pg_temp.demo_id(31+n%2);
        IF operation_ IN ('createVenue','updateVenue') THEN
            user_id_:=pg_temp.demo_id(101+(n%5)); SELECT name INTO label_ FROM venue WHERE id=user_id_;
        ELSIF operation_='completeVenueImport' THEN
            user_id_:=pg_temp.demo_id(1011+(n%5)*10); SELECT name INTO label_ FROM seat_map WHERE id=user_id_;
        ELSIF operation_='adminUpdateUser' THEN
            user_id_:=pg_temp.demo_id(1+n%24); SELECT full_name INTO label_ FROM user_account WHERE id=user_id_;
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
    VALUES(pg_temp.demo_id(31),'UPDATE_ACCOUNT','user_account',pg_temp.demo_id(28),'[DEMO] Khóa tài khoản mẫu',
        now()-interval '4 hours','{"status":"ACTIVE","admin_locked":false}',
        '{"status":"LOCKED","admin_locked":true,"demo":true}');
    RAISE NOTICE 'Đã thêm bộ DEMO: 5 sân, 25 layout, 12 sự kiện, 14 suất, 32 người dùng, 166 đơn.';
END;
$seed$;

-- Trạng thái chỉ có nháp và chưa có sơ đồ.
INSERT INTO venue(id,name,address,city,lat,lng,rules)
VALUES
    (pg_temp.demo_id(106),'[DEMO] Nhà hát Sông Hồng · Chỉ có nháp','Địa điểm mẫu tại Hà Nội','HN',21.024,105.856,'Nội quy mẫu'),
    (pg_temp.demo_id(107),'[DEMO] Quảng trường Ánh Dương · Chưa có sơ đồ','Địa điểm mẫu tại Đà Nẵng','DN',16.061,108.226,'Nội quy mẫu')
ON CONFLICT (id) DO NOTHING;

INSERT INTO venue_import_session(id,owner_id,venue_id,draft)
VALUES(pg_temp.demo_id(206),pg_temp.demo_id(31),pg_temp.demo_id(106),
    '{"layout_name":"[DEMO] Ca nhạc · Đang soạn","layout_type":"CONCERT"}')
ON CONFLICT (id) DO NOTHING;

COMMIT;

SELECT 'venue' AS dataset,count(*) AS records FROM venue WHERE id BETWEEN pg_temp.demo_id(101) AND pg_temp.demo_id(107)
UNION ALL SELECT 'layout',count(*) FROM seat_map WHERE id BETWEEN pg_temp.demo_id(1011) AND pg_temp.demo_id(1055)
UNION ALL SELECT 'event',count(*) FROM event WHERE id BETWEEN pg_temp.demo_id(30001) AND pg_temp.demo_id(30012)
UNION ALL SELECT 'showtime',count(*) FROM showtime WHERE id BETWEEN pg_temp.demo_id(40011) AND pg_temp.demo_id(40121)
UNION ALL SELECT 'user',count(*) FROM user_account WHERE id BETWEEN pg_temp.demo_id(1) AND pg_temp.demo_id(32)
UNION ALL SELECT 'order',count(*) FROM ticket_order WHERE id BETWEEN pg_temp.demo_id(50001) AND pg_temp.demo_id(50166)
UNION ALL SELECT 'ticket',count(*) FROM ticket WHERE code LIKE 'DEMO-%';
