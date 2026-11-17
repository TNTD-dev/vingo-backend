"""Kiểm tra SQL bỏ hall trong PostgreSQL 18 dùng một container tạm, không chạm DB local.

Chạy: python scripts/check_remove_hall_migration.py
Chỉ cần Docker và Python chuẩn; không cần khởi động Spring Boot.
"""

import copy
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "src/main/resources/db/migration"
PENDING = MIGRATIONS / "V20261116_1900__catalog_remove_hall.sql"


def history_before(name: str):
    """Migration lịch sử đứng trước `name`, dùng dựng schema cũ trước khi thử chuyển đổi."""
    return [m for m in sorted(MIGRATIONS.glob("V*.sql")) if m.name < name]
CONTAINER = "vingo-hall-check-" + uuid.uuid4().hex[:12]
TABLES = (
    "venue", "hall", "seat_layout", "zone", "seat", "showtime", "orders",
    "order_item", "ticket", "showtime_seat", "showtime_zone_stock",
    "payment_intent", "payment_transaction",
)


def command(*args, expect_success=True):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8")
    if expect_success and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result


def sql(database, statement):
    return command("docker", "exec", CONTAINER, "psql", "-XAt", "-U", "postgres",
                   "-d", database, "-v", "ON_ERROR_STOP=1", "-c", statement).stdout.strip()


def snapshot(database):
    entries = []
    for table in TABLES:
        if sql(database, f"SELECT to_regclass('public.{table}') IS NOT NULL") == "t":
            entries.append(
                f"'{table}', (SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]') FROM {table} t)"
            )
    return json.loads(sql(database, "SELECT jsonb_build_object(" + ",".join(entries) + ")"))


SEED = """
DO $$
DECLARE v uuid; v2 uuid; h uuid; h2 uuid; l uuid; l2 uuid; e uuid; s uuid;
        z uuid; standing uuid; seat_id uuid; buyer uuid; ord uuid; item uuid; tier uuid; intent uuid;
BEGIN
    INSERT INTO venue(name,type) VALUES ('Sân thử','STADIUM') RETURNING id INTO v;
    INSERT INTO venue(name,type) VALUES ('Sân khác','STADIUM') RETURNING id INTO v2;
    INSERT INTO hall(venue_id,name) VALUES (v,'Hall A') RETURNING id INTO h;
    INSERT INTO hall(venue_id,name) VALUES (v,'Hall B') RETURNING id INTO h2;
    INSERT INTO hall(venue_id,name) VALUES (v2,'Hall sân khác');
    INSERT INTO seat_layout(hall_id,name,status,published_at) VALUES (h,'Sơ đồ trùng tên','PUBLISHED',now()) RETURNING id INTO l;
    INSERT INTO seat_layout(hall_id,name) VALUES (h2,'Sơ đồ trùng tên') RETURNING id INTO l2;
    INSERT INTO zone(seat_layout_id,name,kind,capacity) VALUES (l,'Khán đài A','SEATED',1) RETURNING id INTO z;
    INSERT INTO zone(seat_layout_id,name,kind,capacity) VALUES (l,'Khu đứng','STANDING',100) RETURNING id INTO standing;
    INSERT INTO seat(zone_id,seat_type_id,row_label,number,x,y)
        VALUES (z,(SELECT id FROM seat_type WHERE code='STANDARD'),'A',1,10,20) RETURNING id INTO seat_id;
    INSERT INTO event(title,category) VALUES ('Sự kiện thử','FOOTBALL') RETURNING id INTO e;
    INSERT INTO showtime(event_id,hall_id,seat_layout_id,starts_at,ends_at,sale_opens_at,sale_closes_at)
        VALUES (e,h,l,now()+interval '7 days',now()+interval '8 days',now(),now()+interval '6 days') RETURNING id INTO s;
    INSERT INTO users(email,full_name,password_hash,status) VALUES ('buyer@migration.test','Buyer','hash','ACTIVE') RETURNING id INTO buyer;
    INSERT INTO orders(code,user_id,showtime_id,status,total_amount,expires_at,paid_at)
        VALUES ('ABCDEF',buyer,s,'PAID',100000,now()+interval '10 minutes',now()) RETURNING id INTO ord;
    INSERT INTO price_tier(event_id,name,price) VALUES (e,'Thường',100000) RETURNING id INTO tier;
    INSERT INTO order_item(order_id,zone_id,seat_id,price_tier_id,quantity,unit_price)
        VALUES (ord,z,seat_id,tier,1,100000) RETURNING id INTO item;
    INSERT INTO ticket(order_item_id,showtime_id,zone_id,seat_id,code,qr_token)
        VALUES (item,s,z,seat_id,'TICKET-1','TEST-QR');
    INSERT INTO showtime_seat(showtime_id,seat_id,status,order_id) VALUES (s,seat_id,'SOLD',ord);
    INSERT INTO showtime_zone_stock(showtime_id,zone_id,capacity) VALUES (s,standing,100);
    INSERT INTO payment_intent(order_id,txn_ref,amount,status,expires_at)
        VALUES (ord,'ABCDEF-1',100000,'SUCCEEDED',now()) RETURNING id INTO intent;
    INSERT INTO payment_transaction(payment_intent_id,source,result,amount,raw)
        VALUES (intent,'IPN','SUCCESS',100000,'{}');
END $$;
"""


def migrate(database, expected_error=None):
    result = command("docker", "exec", CONTAINER, "psql", "-X", "-U", "postgres", "-d", database,
                     "-v", "ON_ERROR_STOP=1", "--single-transaction", "-f", "/pending.sql", expect_success=False)
    if expected_error:
        assert result.returncode != 0 and expected_error in result.stderr, result.stderr
    else:
        assert result.returncode == 0, result.stderr


def cloned(name, seed=True):
    sql("postgres", f"CREATE DATABASE {name} TEMPLATE hall_template")
    if seed:
        sql(name, SEED)
    return name


def check_failure(name, change, error):
    database = cloned(name)
    sql(database, change)
    before = snapshot(database)
    migrate(database, error)
    assert snapshot(database) == before, f"{name}: dữ liệu bị thay đổi khi rollback"
    assert sql(database, "SELECT count(*) FROM information_schema.columns WHERE table_name='seat_layout' AND column_name='venue_id'") == "0"
    assert sql(database, "SELECT count(*) FROM pg_constraint WHERE conname='seat_layout_hall_version_uk'") == "1"
    assert sql(database, "SELECT to_regclass('seat_layout_hall_idx') IS NOT NULL") == "t"
    print(f"PASS {name}: từ chối dữ liệu/dependency không hợp lệ và rollback đầy đủ", flush=True)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    created = False
    try:
        command("docker", "run", "--detach", "--name", CONTAINER,
                "-e", "POSTGRES_PASSWORD=" + uuid.uuid4().hex, "postgres:18-alpine")
        created = True
        for _ in range(60):
            result = command("docker", "exec", CONTAINER, "pg_isready", "-U", "postgres", expect_success=False)
            if result.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("PostgreSQL container không sẵn sàng")

        command("docker", "cp", str(ROOT / "src/main/resources/db/migration"), CONTAINER + ":/migrations")
        command("docker", "cp", str(PENDING), CONTAINER + ":/pending.sql")
        sql("postgres", "CREATE DATABASE hall_template")
        for migration in history_before(PENDING.name):
            command("docker", "exec", CONTAINER, "psql", "-Xq", "-U", "postgres", "-d", "hall_template",
                    "-v", "ON_ERROR_STOP=1", "--single-transaction", "-f", "/migrations/" + migration.name)

        database = cloned("hall_success")
        before = snapshot(database)
        expected = copy.deepcopy(before)
        halls = {h["id"]: h["venue_id"] for h in expected.pop("hall")}
        for layout in expected["seat_layout"]:
            layout["venue_id"] = halls[layout.pop("hall_id")]
        for show in expected["showtime"]:
            show.pop("hall_id")
        migrate(database)
        after = snapshot(database)
        # JSON aggregate ordering có thể đổi khi cấu trúc object đổi; đối chiếu theo nội dung.
        for table in expected:
            assert sorted(expected[table], key=lambda row: json.dumps(row, sort_keys=True)) == sorted(after[table], key=lambda row: json.dumps(row, sort_keys=True)), table
        assert "hall" not in after
        assert sql(database, "SELECT count(*) FROM information_schema.columns WHERE table_name IN ('showtime','seat_layout') AND column_name='hall_id'") == "0"
        assert sql(database, "SELECT count(*) FROM pg_constraint WHERE conname IN ('seat_layout_venue_id_fkey','seat_layout_venue_version_uk')") == "2"
        assert sql(database, "SELECT is_nullable FROM information_schema.columns WHERE table_name='seat_layout' AND column_name='venue_id'") == "NO"
        print("PASS dữ liệu: nhiều hall gộp vào sân, giữ nguyên layout/version/tên, đơn/vé/thanh toán", flush=True)

        for label, statement, expected_error in (
            ("FK", "UPDATE seat_layout SET venue_id=gen_random_uuid()", "foreign key"),
            ("NOT NULL", "UPDATE seat_layout SET venue_id=NULL", "not-null"),
            ("UNIQUE", "UPDATE seat_layout SET version=1", "GENERATED ALWAYS"),
        ):
            # version là identity ALWAYS, kiểm tra unique bằng INSERT OVERRIDING SYSTEM VALUE bên dưới.
            if label == "UNIQUE":
                statement = "INSERT INTO seat_layout(venue_id,name,version) OVERRIDING SYSTEM VALUE SELECT venue_id,'Trùng version',version FROM seat_layout LIMIT 1"
                expected_error = "seat_layout_venue_version_uk"
            result = command("docker", "exec", CONTAINER, "psql", "-X", "-U", "postgres", "-d", database,
                             "-v", "ON_ERROR_STOP=1", "-c", statement, expect_success=False)
            assert result.returncode != 0 and expected_error in result.stderr, result.stderr
        print("PASS constraints: FK, NOT NULL, UNIQUE theo sân", flush=True)

        check_failure("hall_mismatch", "UPDATE showtime SET hall_id=(SELECT id FROM hall WHERE name='Hall B')", "REMOVE_HALL_PREFLIGHT: showtime")
        check_failure("hall_duplicate", "INSERT INTO seat_layout(hall_id,name,version) OVERRIDING SYSTEM VALUE SELECT h.id,'Duplicate',sl.version FROM hall h CROSS JOIN seat_layout sl WHERE h.name='Hall B' AND h.id<>sl.hall_id LIMIT 1", "REMOVE_HALL_PREFLIGHT: duplicate")
        check_failure("hall_dependency", "CREATE TABLE external_hall_reference(hall_id uuid REFERENCES hall(id))", "depend on it")
        migrate(cloned("hall_empty", seed=False))
        print("PASS schema rỗng: migration không phụ thuộc seed", flush=True)
    finally:
        if created:
            command("docker", "rm", "--force", CONTAINER)


if __name__ == "__main__":
    main()
