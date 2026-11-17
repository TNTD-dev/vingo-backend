"""Kiểm tra schema sân v2 trong PostgreSQL 18 tạm; không kết nối DB local.

Chạy: python scripts/check_venue_schema_migration.py
"""

import copy
import json
from pathlib import Path
import sys
import time
import uuid

import check_remove_hall_migration as base


ROOT = Path(__file__).resolve().parents[1]
base.CONTAINER = "vingo-venue-v2-check-" + uuid.uuid4().hex[:12]
NEW_MIGRATION = base.MIGRATIONS / "V20261116_1910__catalog_venue_svg_v2.sql"


def sql(db, query):
    return base.command("docker", "exec", base.CONTAINER, "psql", "-XqAt", "-U", "postgres",
                        "-d", db, "-v", "ON_ERROR_STOP=1", "-c", query).stdout.strip()


def rejects(db, query, message):
    result = base.command("docker", "exec", base.CONTAINER, "psql", "-XqAt", "-U", "postgres",
                          "-d", db, "-v", "ON_ERROR_STOP=1", "-c", query, expect_success=False)
    assert result.returncode != 0 and message in result.stderr, result.stderr


def apply(db, file, error=None):
    result = base.command("docker", "exec", base.CONTAINER, "psql", "-Xq", "-U", "postgres",
                          "-d", db, "-v", "ON_ERROR_STOP=1", "--single-transaction", "-f", file,
                          expect_success=False)
    assert (result.returncode != 0 and error in result.stderr) if error else result.returncode == 0, result.stderr


def clone(name):
    sql("postgres", f"CREATE DATABASE {name} TEMPLATE venue_template")
    return name


def stamp_overview(db, overview):
    sql(db, f"UPDATE venue_overview SET validation_hash=catalog_overview_hash(id),validation_engine='test-validator',validated_at=now() WHERE id='{overview}'")


def stamp_layout(db, layout):
    sql(db, f"UPDATE seat_layout SET validation_hash=catalog_layout_hash(id),validation_engine='test-validator',validated_at=now() WHERE id='{layout}'")


OVERVIEW_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect id="GATE__G1" x="90" y="90" width="5" height="5"/></svg>'
DETAIL_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect id="ZONE__A__SEATED" width="50" height="50"/><g id="CONTENT__A"><circle id="SEAT__A__A__1" cx="10" cy="10" r="1"/><path id="PITCH_SIDE__A" d="M0 0H50"/></g></svg>'


def setup_layout(db):
    venue = sql(db, "INSERT INTO venue(name,type) VALUES ('Sân v2','STADIUM') RETURNING id")
    overview = sql(db, f"INSERT INTO venue_overview(venue_id,name,svg_content,view_box_width,view_box_height) VALUES ('{venue}','Nền',$svg${OVERVIEW_SVG}$svg$,100,100) RETURNING id")
    gate = sql(db, f"INSERT INTO venue_gate(overview_id,code,name,svg_id) VALUES ('{overview}','G1','Cổng 1','GATE__G1') RETURNING id")
    area = sql(db, f"INSERT INTO venue_overview_area(overview_id,code,name,svg_id) VALUES ('{overview}','A','Khán đài A','AREA__A') RETURNING id")
    layout = sql(db, f"INSERT INTO seat_layout(venue_id,overview_id,layout_type,name) VALUES ('{venue}','{overview}','FOOTBALL','Bóng đá') RETURNING id")
    sql(db, f"INSERT INTO seat_layout_svg(seat_layout_id,schema_version,svg_content,view_box_x,view_box_y,view_box_width,view_box_height) VALUES ('{layout}',2,$svg${DETAIL_SVG}$svg$,0,0,100,100)")
    zone = sql(db, f"INSERT INTO zone(seat_layout_id,name,kind,capacity,overview_area_id) VALUES ('{layout}','A','SEATED',2,'{area}') RETURNING id")
    seats = sql(db, f"INSERT INTO seat(zone_id,seat_type_id,row_label,number,x,y) SELECT '{zone}',id,'A',n,10*n,10 FROM seat_type CROSS JOIN generate_series(1,2) n WHERE code='STANDARD' RETURNING id").splitlines()
    return dict(venue=venue, overview=overview, gate=gate, area=area, layout=layout, zone=zone, seats=sorted(seats))


def check_preservation():
    db = clone("venue_legacy")
    sql(db, base.SEED)
    before = base.snapshot(db)
    apply(db, "/remove-hall.sql")
    apply(db, "/svg-v2.sql")
    after = base.snapshot(db)
    expected = copy.deepcopy(before)
    halls = {h["id"]: h["venue_id"] for h in expected.pop("hall")}
    for layout in expected["seat_layout"]:
        layout["venue_id"] = halls[layout.pop("hall_id")]
    for show in expected["showtime"]:
        show.pop("hall_id")
    for table, rows in expected.items():
        new_rows = [{key: row[key] for key in rows[0]} for row in after[table]] if rows else []
        assert sorted(rows, key=lambda row: json.dumps(row, sort_keys=True)) == sorted(new_rows, key=lambda row: json.dumps(row, sort_keys=True)), table
    assert sql(db, "SELECT bool_and(layout_schema_version=1 AND layout_type IS NULL AND overview_id IS NULL) FROM seat_layout") == "t"
    legacy = sql(db, "SELECT id FROM seat_layout WHERE status='PUBLISHED' LIMIT 1")
    rejects(db, f"UPDATE seat_layout SET name='Đổi lịch sử' WHERE id='{legacy}'", "CATALOG_IMMUTABLE")
    print("PASS legacy: bảo toàn IDs/dữ liệu, không tự gán loại/nền, published legacy bất biến", flush=True)


def check_relations_and_publication(db):
    v = setup_layout(db)
    other = setup_layout(db)
    rejects(db, f"INSERT INTO seat_layout(venue_id,overview_id,layout_type) VALUES ('{v['venue']}','{other['overview']}','FOOTBALL')", "seat_layout_overview_venue_fk")
    rejects(db, f"INSERT INTO seat_layout(venue_id,overview_id,layout_type) VALUES ('{v['venue']}','{v['overview']}','OTHER')", "seat_layout_v2_shape_check")
    rejects(db, f"INSERT INTO seat_layout(venue_id,layout_schema_version) VALUES ('{v['venue']}',1)", "CATALOG_INVALID_STATE")
    rejects(db, f"UPDATE zone SET overview_area_id='{other['area']}' WHERE id='{v['zone']}'", "zone_area_overview_fk")
    rejects(db, f"UPDATE venue_overview SET status='PUBLISHED',published_at=now() WHERE id='{v['overview']}'", "CATALOG_VALIDATION_REQUIRED")
    rejects(db, f"UPDATE venue_overview SET validation_hash=repeat('a',64),validated_at=now() WHERE id='{v['overview']}'", "check constraint")
    rejects(db, f"UPDATE venue_overview SET view_box_width='NaN'::numeric WHERE id='{v['overview']}'", "check constraint")
    rejects(db, f"INSERT INTO venue_gate(overview_id,code,name,svg_id) VALUES ('{v['overview']}','G1__INVALID','Sai','GATE__G1__INVALID')", "check constraint")
    sql(db, f"INSERT INTO venue_gate(overview_id,code,name,svg_id,direction) VALUES ('{v['overview']}','B3-T1__02','Cổng cũ','GATE__B3-T1__02','BOTH')")
    stamp_overview(db, v["overview"])
    stamp_layout(db, v["layout"])
    sql(db, f"UPDATE venue_gate SET name='Cổng đổi' WHERE id='{v['gate']}'")
    assert sql(db, f"SELECT validation_hash IS NULL FROM venue_overview WHERE id='{v['overview']}'") == "t"
    assert sql(db, f"SELECT validation_hash IS DISTINCT FROM catalog_layout_hash(id) FROM seat_layout WHERE id='{v['layout']}'") == "t"
    stamp_overview(db, v["overview"])
    sql(db, f"UPDATE venue_overview SET status='PUBLISHED',published_at=now() WHERE id='{v['overview']}'")
    for query in [
        f"UPDATE venue_overview SET svg_content=svg_content||' ' WHERE id='{v['overview']}'",
        f"UPDATE venue_overview SET status='DRAFT',published_at=NULL WHERE id='{v['overview']}'",
        f"DELETE FROM venue_overview WHERE id='{v['overview']}'",
        f"UPDATE venue_gate SET direction='IN' WHERE id='{v['gate']}'",
        f"DELETE FROM venue_gate WHERE id='{v['gate']}'",
        f"INSERT INTO venue_gate(overview_id,code,name,svg_id) VALUES ('{v['overview']}','G2','Cổng 2','GATE__G2')",
        f"UPDATE venue_overview_area SET name='Đổi' WHERE id='{v['area']}'",
    ]:
        rejects(db, query, "CATALOG_IMMUTABLE")
    rejects(db, f"UPDATE seat_layout SET status='PUBLISHED',published_at=now() WHERE id='{v['layout']}'", "CATALOG_VALIDATION_REQUIRED")
    stamp_layout(db, v["layout"])
    sql(db, f"UPDATE seat SET x=x+1 WHERE id='{v['seats'][0]}'")
    assert sql(db, f"SELECT validation_hash IS NULL FROM seat_layout WHERE id='{v['layout']}'") == "t"
    stamp_layout(db, v["layout"])
    sql(db, f"UPDATE seat_layout_svg SET view_box_width=200 WHERE seat_layout_id='{v['layout']}'")
    stamp_layout(db, v["layout"])
    rejects(db, f"UPDATE seat_layout SET status='PUBLISHED',published_at=now() WHERE id='{v['layout']}'", "CATALOG_VALIDATION_REQUIRED")
    sql(db, f"UPDATE seat_layout_svg SET view_box_width=100 WHERE seat_layout_id='{v['layout']}'")
    sql(db, f"INSERT INTO seat_pair(seat_a_id,seat_b_id) VALUES ('{v['seats'][0]}','{v['seats'][1]}')")
    stamp_layout(db, v["layout"])
    sql(db, f"UPDATE seat_layout SET status='PUBLISHED',published_at=now() WHERE id='{v['layout']}'")
    for query in [
        f"UPDATE seat_layout SET name='Đổi' WHERE id='{v['layout']}'",
        f"DELETE FROM seat_layout WHERE id='{v['layout']}'",
        f"UPDATE seat_layout_svg SET svg_content=svg_content||' ' WHERE seat_layout_id='{v['layout']}'",
        f"UPDATE zone SET name='Đổi' WHERE id='{v['zone']}'",
        f"UPDATE seat SET x=99 WHERE id='{v['seats'][0]}'",
        f"DELETE FROM seat WHERE id='{v['seats'][0]}'",
        f"INSERT INTO seat_pair(seat_a_id,seat_b_id) VALUES ('{v['seats'][0]}','{v['seats'][1]}')",
        f"DELETE FROM seat_pair WHERE seat_a_id='{v['seats'][0]}'",
    ]:
        rejects(db, query, "CATALOG_IMMUTABLE")
    snapshot = sql(db, f"SELECT catalog_layout_hash('{v['layout']}')")
    next_overview = sql(db, f"INSERT INTO venue_overview(venue_id,name,svg_content,view_box_width,view_box_height) VALUES ('{v['venue']}','Nền mới',$svg${OVERVIEW_SVG}$svg$,100,100) RETURNING id")
    assert sql(db, f"SELECT catalog_layout_hash('{v['layout']}')") == snapshot
    assert sql(db, f"SELECT overview_id::text FROM seat_layout WHERE id='{v['layout']}'") == v["overview"]
    assert next_overview != v["overview"]
    print("PASS quan hệ/publication: FK cùng sân/nền, validation hash, viewBox và bất biến published", flush=True)
    return v


def new_session(db, owner, target=None):
    target_sql = f"'{target}'" if target else "NULL"
    return sql(db, f"INSERT INTO venue_import_session(owner_id,target_venue_id) VALUES ('{owner}',{target_sql}) RETURNING id")


def file(db, session, label, role="UNKNOWN", key=None, validated=False):
    key_sql = f"'{key}'" if key else "NULL"
    content = OVERVIEW_SVG if role == "OVERVIEW" else DETAIL_SVG
    content += f"<!--{label}-->"
    status = "VALIDATED" if validated else "UPLOADED"
    sanitized = f"$svg${content}$svg$" if validated else "NULL"
    schema = "2" if validated else "NULL"
    return sql(db, f"INSERT INTO venue_import_file(session_id,original_filename,role,layout_key,source_bytes,validation_status,sanitized_svg,schema_version) VALUES ('{session}','{label}.svg','{role}',{key_sql},convert_to($svg${content}$svg$,'UTF8'),'{status}',{sanitized},{schema}) RETURNING id")


def check_sessions(db, v):
    owner = sql(db, "INSERT INTO users(email,full_name,password_hash,role,status) VALUES ('admin@schema.test','Admin','hash','ADMIN','ACTIVE') RETURNING id")
    session = new_session(db, owner)
    assert sql(db, f"SELECT revision FROM venue_import_session WHERE id='{session}'") == "0"
    sql(db, f"UPDATE venue_import_session SET draft_data='{{\"name\":\"Sân nháp\"}}' WHERE id='{session}' AND revision=0")
    assert sql(db, f"WITH changed AS (UPDATE venue_import_session SET step='LAYOUT' WHERE id='{session}' AND revision=0 RETURNING id) SELECT count(*) FROM changed") == "0"
    assert "Sân nháp" in sql(db, f"SELECT draft_data FROM venue_import_session WHERE id='{session}'")
    rejects(db, f"UPDATE venue_import_session SET owner_id=gen_random_uuid() WHERE id='{session}'", "IMPORT_IMMUTABLE_IDENTITY")
    rejects(db, f"UPDATE venue_import_session SET status='COMPLETED',completed_venue_id='{v['venue']}',completed_layout_id='{v['layout']}' WHERE id='{session}'", "IMPORT_COMPLETION_INVALID")
    f = file(db, session, "overview", "OVERVIEW", validated=True)
    assert sql(db, f"SELECT revision FROM venue_import_session WHERE id='{session}'") == "2"
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) SELECT session_id,'duplicate.svg',source_bytes FROM venue_import_file WHERE id='{f}'", "venue_import_file_session_id_checksum_key")
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,role,source_bytes) VALUES ('{session}','other.svg','OVERVIEW',convert_to('other','UTF8'))", "IMPORT_MULTIPLE_OVERVIEWS")
    sql(db, f"UPDATE venue_import_file SET source_bytes=convert_to('new svg','UTF8') WHERE id='{f}'")
    assert sql(db, f"SELECT validation_status||':'||role FROM venue_import_file WHERE id='{f}'") == "UPLOADED:UNKNOWN"
    sql(db, f"DELETE FROM venue_import_file WHERE id='{f}'")
    file(db, session, "overview", "OVERVIEW", validated=True)
    key = str(uuid.uuid4())
    invalid = file(db, session, "bad-detail", "SEAT_LAYOUT", key, True)
    sql(db, f"UPDATE venue_import_file SET validation_status='INVALID',issues='[{{\"reason\":\"ZONE_OVERLAPS_GATE\"}}]' WHERE id='{invalid}'")
    assert sql(db, f"SELECT sanitized_svg IS NOT NULL FROM venue_import_file WHERE id='{invalid}'") == "t"
    rejects(db, f"UPDATE venue_import_session SET status='COMPLETED',completed_venue_id='{v['venue']}',completed_layout_id='{v['layout']}' WHERE id='{session}'", "IMPORT_COMPLETION_INVALID")
    sql(db, f"DELETE FROM venue_import_file WHERE id='{invalid}'")
    file(db, session, "detail1", "SEAT_LAYOUT", key, True)
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,role,layout_key,source_bytes) VALUES ('{session}','wrong-group.svg','SEAT_LAYOUT',gen_random_uuid(),convert_to('another','UTF8'))", "IMPORT_MULTIPLE_LAYOUT_GROUPS")
    file(db, session, "detail2", "SEAT_LAYOUT", key, True)
    sql(db, f"UPDATE venue_import_session SET status='COMPLETED',completed_venue_id='{v['venue']}',completed_layout_id='{v['layout']}' WHERE id='{session}'")
    result = sql(db, f"SELECT revision FROM venue_import_session WHERE id='{session}'")
    sql(db, f"UPDATE venue_import_session SET status='COMPLETED' WHERE id='{session}'")
    assert sql(db, f"SELECT revision FROM venue_import_session WHERE id='{session}'") == result
    rejects(db, f"UPDATE venue_import_session SET step='DETAILS' WHERE id='{session}'", "IMPORT_CLOSED_OR_EXPIRED")
    rejects(db, f"DELETE FROM venue_import_file WHERE session_id='{session}'", "IMPORT_CLOSED_OR_EXPIRED")
    sql(db, f"DELETE FROM venue_import_session WHERE id='{session}'")
    assert sql(db, f"SELECT count(*) FROM venue_import_file WHERE session_id='{session}'") == "0"
    expired = new_session(db, owner)
    sql(db, f"UPDATE venue_import_session SET expires_at=statement_timestamp()+interval '100 milliseconds' WHERE id='{expired}'; SELECT pg_sleep(.2)")
    rejects(db, f"UPDATE venue_import_session SET step='LAYOUT' WHERE id='{expired}'", "IMPORT_CLOSED_OR_EXPIRED")
    print("PASS nháp: owner, revision/CAS, nhiều file, reset validation, hoàn tất/retry, TTL và cleanup", flush=True)
    return owner


def check_quotas(db, owner):
    session = new_session(db, owner)
    sql(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) SELECT '{session}','f'||n||'.svg',convert_to('file'||n,'UTF8') FROM generate_series(1,32) n")
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) VALUES ('{session}','f33.svg',convert_to('file33','UTF8'))", "IMPORT_QUOTA_EXCEEDED")
    assert sql(db, f"SELECT count(*) FROM venue_import_file WHERE session_id='{session}'") == "32"
    large = new_session(db, owner)
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) VALUES ('{large}','too-large.svg',convert_to(repeat('a',10485761),'UTF8'))", "check constraint")
    sql(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) SELECT '{large}','big'||n||'.svg',convert_to(repeat(chr(64+n),10485760),'UTF8') FROM generate_series(1,4) n")
    rejects(db, f"INSERT INTO venue_import_file(session_id,original_filename,source_bytes) VALUES ('{large}','extra.svg',convert_to('extra','UTF8'))", "IMPORT_QUOTA_EXCEEDED")
    assert sql(db, f"SELECT sum(size_bytes) FROM venue_import_file WHERE session_id='{large}'") == "41943040"
    print("PASS quota: 10 MiB/file, 32 file/session và 40 MiB tổng source, không ghi một phần khi lỗi", flush=True)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    created = False
    try:
        base.command("docker", "run", "--detach", "--name", base.CONTAINER,
                     "-e", "POSTGRES_PASSWORD=" + uuid.uuid4().hex, "postgres:18-alpine")
        created = True
        for _ in range(60):
            if base.command("docker", "exec", base.CONTAINER, "pg_isready", "-U", "postgres", expect_success=False).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError("PostgreSQL không sẵn sàng")
        base.command("docker", "cp", str(ROOT / "src/main/resources/db/migration"), base.CONTAINER + ":/migrations")
        base.command("docker", "cp", str(base.PENDING), base.CONTAINER + ":/remove-hall.sql")
        base.command("docker", "cp", str(NEW_MIGRATION), base.CONTAINER + ":/svg-v2.sql")
        sql("postgres", "CREATE DATABASE venue_template")
        for migration in base.history_before(base.PENDING.name):
            apply("venue_template", "/migrations/" + migration.name)
        check_preservation()
        db = clone("venue_fresh")
        apply(db, "/remove-hall.sql")
        apply(db, "/svg-v2.sql")
        assert sql(db, "SELECT count(*) FROM venue") == "0"
        assert sql(db, "SELECT to_regclass('zone_gate') IS NULL") == "t"
        print("PASS schema mới: chạy được khi chưa có sân/layout, không có zone_gate", flush=True)
        v = check_relations_and_publication(db)
        owner = check_sessions(db, v)
        check_quotas(db, owner)
        rollback = clone("venue_rollback")
        apply(rollback, "/remove-hall.sql")
        sql(rollback, "CREATE TABLE venue_gate(id integer)")
        apply(rollback, "/svg-v2.sql", "already exists")
        assert sql(rollback, "SELECT to_regclass('venue_overview') IS NULL") == "t"
        assert sql(rollback, "SELECT count(*) FROM information_schema.columns WHERE table_name='seat_layout' AND column_name='layout_type'") == "0"
        print("PASS rollback: lỗi migration v2 không để lại schema v2 một phần", flush=True)
    finally:
        if created:
            base.command("docker", "rm", "--force", base.CONTAINER)


if __name__ == "__main__":
    main()
