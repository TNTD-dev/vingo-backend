-- CHỜ TRIỂN KHAI, sau V20261116_1900__catalog_remove_hall.sql.
-- Flyway/psql phải chạy trong transaction. Không áp dụng cùng ứng dụng cũ.
LOCK TABLE venue, seat_layout, seat_layout_svg, zone, seat, seat_pair IN ACCESS EXCLUSIVE MODE;

CREATE TABLE venue_overview (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    venue_id uuid NOT NULL REFERENCES venue(id),
    version bigint GENERATED ALWAYS AS IDENTITY,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200 AND name ~ '[^[:space:]]'),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED')),
    schema_version smallint NOT NULL DEFAULT 2 CHECK (schema_version = 2),
    svg_content text NOT NULL CHECK (length(svg_content) > 0 AND octet_length(svg_content) <= 10485760),
    view_box_x numeric NOT NULL DEFAULT 0 CHECK (view_box_x = 0),
    view_box_y numeric NOT NULL DEFAULT 0 CHECK (view_box_y = 0),
    view_box_width numeric NOT NULL CHECK (view_box_width > 0 AND view_box_width < 'Infinity'::numeric),
    view_box_height numeric NOT NULL CHECK (view_box_height > 0 AND view_box_height < 'Infinity'::numeric),
    validation_hash text CHECK (validation_hash ~ '^[0-9a-f]{64}$'),
    validation_engine text,
    validated_at timestamptz,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CHECK ((validation_hash IS NULL AND validation_engine IS NULL AND validated_at IS NULL)
        OR (validation_hash IS NOT NULL AND validation_engine IS NOT NULL AND length(validation_engine) > 0 AND validated_at IS NOT NULL)),
    UNIQUE (venue_id,version),
    UNIQUE (id,venue_id)
);
CREATE INDEX venue_overview_venue_idx ON venue_overview(venue_id,status,version DESC);

CREATE TABLE venue_overview_area (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    overview_id uuid NOT NULL REFERENCES venue_overview(id),
    code text NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9-]*$'),
    name text NOT NULL CHECK (name ~ '[^[:space:]]' AND length(name) <= 200),
    svg_id text NOT NULL,
    CHECK (svg_id = 'AREA__' || code),
    UNIQUE (overview_id,code), UNIQUE (overview_id,svg_id), UNIQUE (id,overview_id)
);

CREATE TABLE venue_gate (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    overview_id uuid NOT NULL REFERENCES venue_overview(id),
    code text NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]*(__[0-9]+)?$'),
    name text NOT NULL CHECK (name ~ '[^[:space:]]' AND length(name) <= 200),
    direction text NOT NULL DEFAULT 'BOTH' CHECK (direction IN ('IN','OUT','BOTH')),
    svg_id text NOT NULL,
    label_svg_id text,
    CHECK (svg_id = 'GATE__' || code OR svg_id = 'GATE__' || code || '__' || direction),
    CHECK (label_svg_id IS NULL OR label_svg_id = 'GATE_LABEL__' || code),
    UNIQUE (overview_id,code), UNIQUE (overview_id,svg_id)
);

-- Không tự suy đoán loại/nền của layout cũ. Chỉ layout mới có schema v2 mặc định.
ALTER TABLE seat_layout
    ADD COLUMN layout_schema_version smallint NOT NULL DEFAULT 1,
    ADD COLUMN layout_type text,
    ADD COLUMN overview_id uuid,
    ADD COLUMN validation_hash text CHECK (validation_hash ~ '^[0-9a-f]{64}$'),
    ADD COLUMN validation_engine text,
    ADD COLUMN validated_at timestamptz,
    ADD CONSTRAINT seat_layout_v2_shape_check CHECK (
        (layout_schema_version = 1 AND layout_type IS NULL AND overview_id IS NULL)
        OR (layout_schema_version = 2 AND layout_type IS NOT NULL
            AND layout_type IN ('FOOTBALL','CONCERT') AND overview_id IS NOT NULL)),
    ADD CONSTRAINT seat_layout_validation_check CHECK (
        (validation_hash IS NULL AND validation_engine IS NULL AND validated_at IS NULL)
        OR (validation_hash IS NOT NULL AND validation_engine IS NOT NULL AND length(validation_engine) > 0 AND validated_at IS NOT NULL)),
    ADD CONSTRAINT seat_layout_id_overview_uk UNIQUE (id,overview_id),
    ADD CONSTRAINT seat_layout_id_venue_uk UNIQUE (id,venue_id),
    ADD CONSTRAINT seat_layout_overview_venue_fk FOREIGN KEY (overview_id,venue_id)
        REFERENCES venue_overview(id,venue_id);
ALTER TABLE seat_layout ALTER COLUMN layout_schema_version SET DEFAULT 2;
CREATE INDEX seat_layout_v2_selection_idx ON seat_layout(venue_id,layout_type,version DESC)
    WHERE layout_schema_version = 2 AND status = 'PUBLISHED';

ALTER TABLE zone
    ADD COLUMN overview_id uuid,
    ADD COLUMN overview_area_id uuid,
    ADD CONSTRAINT zone_layout_overview_fk FOREIGN KEY (seat_layout_id,overview_id)
        REFERENCES seat_layout(id,overview_id),
    ADD CONSTRAINT zone_area_overview_fk FOREIGN KEY (overview_area_id,overview_id)
        REFERENCES venue_overview_area(id,overview_id),
    ADD CONSTRAINT zone_area_needs_overview CHECK (overview_area_id IS NULL OR overview_id IS NOT NULL);
CREATE INDEX zone_overview_area_idx ON zone(overview_area_id) WHERE overview_area_id IS NOT NULL;

-- Hash gắn kết quả validator với đúng nội dung đang lưu. Backend mới có trách nhiệm
-- gọi validator SVG/Area thật trước khi ghi hash; DB không chạy Java hay tự đọc path SVG.
CREATE FUNCTION catalog_overview_hash(p_id uuid) RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT encode(sha256(convert_to(jsonb_build_object(
        'overview', to_jsonb(o) - ARRAY['status','published_at','validation_hash','validation_engine','validated_at'],
        'areas', (SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY a.id),'[]') FROM venue_overview_area a WHERE a.overview_id=o.id),
        'gates', (SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY g.id),'[]') FROM venue_gate g WHERE g.overview_id=o.id)
    )::text,'UTF8')),'hex') FROM venue_overview o WHERE o.id=p_id
$$;

CREATE FUNCTION catalog_layout_hash(p_id uuid) RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT encode(sha256(convert_to(jsonb_build_object(
        'layout', to_jsonb(l) - ARRAY['status','published_at','validation_hash','validation_engine','validated_at'],
        'overview_hash', catalog_overview_hash(l.overview_id),
        'svg', (SELECT to_jsonb(s) FROM seat_layout_svg s WHERE s.seat_layout_id=l.id),
        'zones', (SELECT coalesce(jsonb_agg(to_jsonb(z) ORDER BY z.id),'[]') FROM zone z WHERE z.seat_layout_id=l.id),
        'seats', (SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY s.id),'[]') FROM seat s JOIN zone z ON z.id=s.zone_id WHERE z.seat_layout_id=l.id),
        'pairs', (SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY p.seat_a_id,p.seat_b_id),'[]') FROM seat_pair p
                  JOIN seat s ON s.id=p.seat_a_id JOIN zone z ON z.id=s.zone_id WHERE z.seat_layout_id=l.id)
    )::text,'UTF8')),'hex') FROM seat_layout l WHERE l.id=p_id
$$;

CREATE FUNCTION catalog_guard_overview() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE: published overview'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN RAISE EXCEPTION 'CATALOG_INVALID_STATE: overview must start DRAFT'; END IF;
        NEW.validation_hash:=NULL; NEW.validation_engine:=NULL; NEW.validated_at:=NULL;
        RETURN NEW;
    END IF;
    IF OLD.status='PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'CATALOG_IMMUTABLE: published overview';
    END IF;
    IF ROW(NEW.id,NEW.venue_id,NEW.version,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.venue_id,OLD.version,OLD.created_at) THEN
        RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: overview';
    END IF;
    IF ROW(NEW.name,NEW.svg_content,NEW.schema_version,NEW.view_box_x,NEW.view_box_y,NEW.view_box_width,NEW.view_box_height)
       IS DISTINCT FROM ROW(OLD.name,OLD.svg_content,OLD.schema_version,OLD.view_box_x,OLD.view_box_y,OLD.view_box_width,OLD.view_box_height) THEN
        NEW.validation_hash:=NULL; NEW.validation_engine:=NULL; NEW.validated_at:=NULL;
    END IF;
    IF NEW.status='PUBLISHED' AND OLD.status<>'PUBLISHED' THEN
        IF NEW.validation_hash IS DISTINCT FROM catalog_overview_hash(OLD.id) OR NEW.validated_at IS NULL
           OR NOT EXISTS (SELECT 1 FROM venue_gate WHERE overview_id=OLD.id) THEN
            RAISE EXCEPTION 'CATALOG_VALIDATION_REQUIRED: overview';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER venue_overview_guard BEFORE INSERT OR UPDATE OR DELETE ON venue_overview
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_overview();

CREATE FUNCTION catalog_guard_overview_child() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid; parent_status text;
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.overview_id) IS DISTINCT FROM ROW(OLD.id,OLD.overview_id) THEN
        RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: overview child';
    END IF;
    IF TG_OP='DELETE' THEN p:=OLD.overview_id; ELSE p:=NEW.overview_id; END IF;
    SELECT status INTO parent_status FROM venue_overview WHERE id=p FOR UPDATE;
    IF parent_status IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE: overview child'; END IF;
    UPDATE venue_overview SET validation_hash=NULL,validation_engine=NULL,validated_at=NULL WHERE id=p;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER venue_gate_guard BEFORE INSERT OR UPDATE OR DELETE ON venue_gate
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_overview_child();
CREATE TRIGGER venue_overview_area_guard BEFORE INSERT OR UPDATE OR DELETE ON venue_overview_area
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_overview_child();

CREATE FUNCTION catalog_guard_layout() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o venue_overview%ROWTYPE; s seat_layout_svg%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE: published layout'; END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'DRAFT' OR NEW.layout_schema_version<>2 THEN
            RAISE EXCEPTION 'CATALOG_INVALID_STATE: new layouts must be v2 DRAFT';
        END IF;
        NEW.validation_hash:=NULL; NEW.validation_engine:=NULL; NEW.validated_at:=NULL;
        RETURN NEW;
    END IF;
    IF OLD.status='PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE: published layout'; END IF;
    IF ROW(NEW.id,NEW.venue_id,NEW.version,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.venue_id,OLD.version,OLD.created_at) THEN
        RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: layout';
    END IF;
    IF ROW(NEW.overview_id,NEW.layout_type,NEW.layout_schema_version) IS DISTINCT FROM ROW(OLD.overview_id,OLD.layout_type,OLD.layout_schema_version)
       AND EXISTS (SELECT 1 FROM showtime WHERE seat_layout_id=OLD.id) THEN
        RAISE EXCEPTION 'CATALOG_LAYOUT_IN_USE: clone layout instead';
    END IF;
    IF ROW(NEW.name,NEW.overview_id,NEW.layout_type,NEW.layout_schema_version) IS DISTINCT FROM ROW(OLD.name,OLD.overview_id,OLD.layout_type,OLD.layout_schema_version) THEN
        NEW.validation_hash:=NULL; NEW.validation_engine:=NULL; NEW.validated_at:=NULL;
    END IF;
    IF NEW.status='PUBLISHED' AND OLD.status<>'PUBLISHED' THEN
        SELECT * INTO o FROM venue_overview WHERE id=NEW.overview_id FOR UPDATE;
        SELECT * INTO s FROM seat_layout_svg WHERE seat_layout_id=OLD.id;
        IF NEW.layout_schema_version<>2 OR o.status IS DISTINCT FROM 'PUBLISHED'
           OR NEW.validation_hash IS DISTINCT FROM catalog_layout_hash(OLD.id) OR NEW.validated_at IS NULL
           OR s.schema_version IS DISTINCT FROM 2
           OR ROW(s.view_box_x,s.view_box_y,s.view_box_width,s.view_box_height)
              IS DISTINCT FROM ROW(o.view_box_x,o.view_box_y,o.view_box_width,o.view_box_height)
           OR NOT EXISTS (SELECT 1 FROM zone WHERE seat_layout_id=OLD.id)
           OR EXISTS (SELECT 1 FROM zone WHERE seat_layout_id=OLD.id AND overview_id IS DISTINCT FROM NEW.overview_id) THEN
            RAISE EXCEPTION 'CATALOG_VALIDATION_REQUIRED: layout';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER seat_layout_v2_guard BEFORE INSERT OR UPDATE OR DELETE ON seat_layout
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_layout();

CREATE FUNCTION catalog_guard_layout_child() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid; q uuid; l seat_layout%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='zone' THEN
        IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.seat_layout_id) IS DISTINCT FROM ROW(OLD.id,OLD.seat_layout_id) THEN
            RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: zone';
        END IF;
        IF TG_OP='DELETE' THEN p:=OLD.seat_layout_id; ELSE p:=NEW.seat_layout_id; END IF;
    ELSIF TG_TABLE_NAME='seat_layout_svg' THEN
        IF TG_OP='UPDATE' AND NEW.seat_layout_id<>OLD.seat_layout_id THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: svg'; END IF;
        IF TG_OP='DELETE' THEN p:=OLD.seat_layout_id; ELSE p:=NEW.seat_layout_id; END IF;
    ELSIF TG_TABLE_NAME='seat' THEN
        IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.zone_id) IS DISTINCT FROM ROW(OLD.id,OLD.zone_id) THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: seat'; END IF;
        IF TG_OP='DELETE' THEN q:=OLD.zone_id; ELSE q:=NEW.zone_id; END IF;
        SELECT seat_layout_id INTO p FROM zone WHERE id=q;
    ELSE
        IF TG_OP='UPDATE' AND ROW(NEW.seat_a_id,NEW.seat_b_id) IS DISTINCT FROM ROW(OLD.seat_a_id,OLD.seat_b_id) THEN
            RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY: seat pair';
        END IF;
        IF TG_OP='DELETE' THEN q:=OLD.seat_a_id; ELSE q:=NEW.seat_a_id; END IF;
        SELECT z.seat_layout_id INTO p FROM seat s JOIN zone z ON z.id=s.zone_id WHERE s.id=q;
        IF TG_OP<>'DELETE' AND NOT EXISTS (SELECT 1 FROM seat s JOIN zone z ON z.id=s.zone_id WHERE s.id=NEW.seat_b_id AND z.seat_layout_id=p) THEN
            RAISE EXCEPTION 'CATALOG_PAIR_LAYOUT_MISMATCH';
        END IF;
    END IF;
    SELECT * INTO l FROM seat_layout WHERE id=p FOR UPDATE;
    IF l.status IS DISTINCT FROM 'DRAFT' OR EXISTS (SELECT 1 FROM showtime WHERE seat_layout_id=p) THEN
        RAISE EXCEPTION 'CATALOG_IMMUTABLE: layout geometry';
    END IF;
    IF TG_TABLE_NAME='zone' AND TG_OP<>'DELETE' THEN
        IF NEW.overview_id IS NOT NULL AND NEW.overview_id IS DISTINCT FROM l.overview_id THEN RAISE EXCEPTION 'CATALOG_OVERVIEW_MISMATCH'; END IF;
        NEW.overview_id:=l.overview_id;
    END IF;
    UPDATE seat_layout SET validation_hash=NULL,validation_engine=NULL,validated_at=NULL WHERE id=p;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER zone_v2_guard BEFORE INSERT OR UPDATE OR DELETE ON zone FOR EACH ROW EXECUTE FUNCTION catalog_guard_layout_child();
CREATE TRIGGER seat_v2_guard BEFORE INSERT OR UPDATE OR DELETE ON seat FOR EACH ROW EXECUTE FUNCTION catalog_guard_layout_child();
CREATE TRIGGER seat_layout_svg_v2_guard BEFORE INSERT OR UPDATE OR DELETE ON seat_layout_svg FOR EACH ROW EXECUTE FUNCTION catalog_guard_layout_child();
CREATE TRIGGER seat_pair_v2_guard BEFORE INSERT OR UPDATE OR DELETE ON seat_pair FOR EACH ROW EXECUTE FUNCTION catalog_guard_layout_child();

-- Nháp tách khỏi venue để upload trước khi nhập tên/địa chỉ, không tạo venue rỗng.
CREATE TABLE venue_import_session (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_id uuid NOT NULL REFERENCES users(id),
    target_venue_id uuid REFERENCES venue(id),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','COMPLETED','CANCELLED')),
    step text NOT NULL DEFAULT 'OVERVIEW' CHECK (step IN ('OVERVIEW','LAYOUT','PREVIEW','DETAILS','REVIEW')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    draft_data jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(draft_data)='object'),
    completed_venue_id uuid,
    completed_layout_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days',
    completed_at timestamptz,
    CHECK (expires_at>created_at AND updated_at>=created_at),
    CHECK ((status='COMPLETED' AND completed_venue_id IS NOT NULL AND completed_layout_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (status<>'COMPLETED' AND completed_venue_id IS NULL AND completed_layout_id IS NULL AND completed_at IS NULL)),
    FOREIGN KEY (completed_layout_id,completed_venue_id) REFERENCES seat_layout(id,venue_id)
);
CREATE INDEX venue_import_owner_idx ON venue_import_session(owner_id,status,updated_at DESC,id);
CREATE INDEX venue_import_expiry_idx ON venue_import_session(expires_at);

CREATE TABLE venue_import_file (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    session_id uuid NOT NULL REFERENCES venue_import_session(id) ON DELETE CASCADE,
    original_filename text NOT NULL CHECK (length(original_filename) BETWEEN 1 AND 255),
    role text NOT NULL DEFAULT 'UNKNOWN' CHECK (role IN ('UNKNOWN','OVERVIEW','SEAT_LAYOUT','COMBINED_V1')),
    layout_key uuid,
    source_bytes bytea NOT NULL CHECK (octet_length(source_bytes) BETWEEN 1 AND 10485760),
    size_bytes integer GENERATED ALWAYS AS (octet_length(source_bytes)) STORED,
    checksum text GENERATED ALWAYS AS (encode(sha256(source_bytes),'hex')) STORED,
    sanitized_svg text CHECK (octet_length(sanitized_svg) BETWEEN 1 AND 10485760),
    validation_status text NOT NULL DEFAULT 'UPLOADED' CHECK (validation_status IN ('UPLOADED','VALIDATED','INVALID')),
    schema_version smallint CHECK (schema_version IN (1,2)),
    parsed_metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parsed_metadata)='object'),
    issues jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(issues)='array'),
    -- SVG đã an toàn có thể preview kèm lỗi hình học; INVALID không được hoàn tất.
    CHECK (validation_status<>'VALIDATED' OR (sanitized_svg IS NOT NULL AND schema_version IS NOT NULL AND role<>'UNKNOWN')),
    CHECK ((role='OVERVIEW' AND layout_key IS NULL) OR (role='SEAT_LAYOUT' AND layout_key IS NOT NULL) OR role IN ('UNKNOWN','COMBINED_V1')),
    UNIQUE (session_id,checksum)
);

CREATE FUNCTION catalog_guard_import_session() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE l seat_layout%ROWTYPE; o venue_overview%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'DRAFT' OR NEW.revision<>0 OR NEW.expires_at<=statement_timestamp() THEN RAISE EXCEPTION 'IMPORT_INVALID_STATE'; END IF;
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN OLD; END IF;
    IF OLD.status<>'DRAFT' OR OLD.expires_at<=statement_timestamp() THEN RAISE EXCEPTION 'IMPORT_CLOSED_OR_EXPIRED'; END IF;
    IF ROW(NEW.id,NEW.owner_id,NEW.target_venue_id,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.owner_id,OLD.target_venue_id,OLD.created_at) THEN
        RAISE EXCEPTION 'IMPORT_IMMUTABLE_IDENTITY';
    END IF;
    NEW.revision:=OLD.revision+1; NEW.updated_at:=greatest(statement_timestamp(),OLD.updated_at,NEW.updated_at);
    IF NEW.status='COMPLETED' THEN
        SELECT * INTO l FROM seat_layout WHERE id=NEW.completed_layout_id FOR UPDATE;
        SELECT * INTO o FROM venue_overview WHERE id=l.overview_id FOR UPDATE;
        IF l.layout_schema_version IS DISTINCT FROM 2 OR l.venue_id IS DISTINCT FROM NEW.completed_venue_id
           OR (OLD.target_venue_id IS NOT NULL AND OLD.target_venue_id IS DISTINCT FROM l.venue_id)
           OR l.validation_hash IS NULL OR l.validation_hash IS DISTINCT FROM catalog_layout_hash(l.id)
           OR o.validation_hash IS NULL OR o.validation_hash IS DISTINCT FROM catalog_overview_hash(o.id)
           OR (SELECT count(*) FROM venue_import_file WHERE session_id=OLD.id AND role='OVERVIEW' AND validation_status='VALIDATED')<>1
           OR NOT EXISTS (SELECT 1 FROM venue_import_file WHERE session_id=OLD.id AND role='SEAT_LAYOUT' AND validation_status='VALIDATED')
           OR EXISTS (SELECT 1 FROM venue_import_file WHERE session_id=OLD.id AND (validation_status<>'VALIDATED' OR role NOT IN ('OVERVIEW','SEAT_LAYOUT'))) THEN
            RAISE EXCEPTION 'IMPORT_COMPLETION_INVALID';
        END IF;
        NEW.completed_at:=NEW.updated_at; NEW.expires_at:=NEW.updated_at+interval '7 days';
    ELSIF NEW.status='CANCELLED' THEN
        NEW.expires_at:=NEW.updated_at+interval '7 days';
    ELSE
        -- TTL trượt; cập nhật expires_at rõ ràng (để backend cấu hình) vẫn được giữ.
        IF NEW.expires_at=OLD.expires_at THEN NEW.expires_at:=NEW.updated_at+interval '30 days'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER venue_import_session_guard BEFORE INSERT OR UPDATE ON venue_import_session
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_import_session();

CREATE FUNCTION catalog_guard_import_file() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid; state venue_import_session%ROWTYPE; amount bigint; total_count bigint;
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.session_id) IS DISTINCT FROM ROW(OLD.id,OLD.session_id) THEN RAISE EXCEPTION 'IMPORT_IMMUTABLE_IDENTITY: file'; END IF;
    IF TG_OP='DELETE' THEN p:=OLD.session_id; ELSE p:=NEW.session_id; END IF;
    SELECT * INTO state FROM venue_import_session WHERE id=p FOR UPDATE;
    IF NOT FOUND AND TG_OP='DELETE' THEN RETURN OLD; END IF; -- cleanup session CASCADE
    IF state.status IS DISTINCT FROM 'DRAFT' OR state.expires_at<=statement_timestamp() THEN RAISE EXCEPTION 'IMPORT_CLOSED_OR_EXPIRED'; END IF;
    IF TG_OP<>'DELETE' THEN
        SELECT count(*),coalesce(sum(size_bytes),0) INTO total_count,amount FROM venue_import_file WHERE session_id=p AND id<>NEW.id;
        IF total_count+1>32 OR amount+octet_length(NEW.source_bytes)>41943040 THEN RAISE EXCEPTION 'IMPORT_QUOTA_EXCEEDED'; END IF;
        IF NEW.role='OVERVIEW' AND EXISTS (SELECT 1 FROM venue_import_file WHERE session_id=p AND role='OVERVIEW' AND id<>NEW.id) THEN RAISE EXCEPTION 'IMPORT_MULTIPLE_OVERVIEWS'; END IF;
        IF NEW.role='SEAT_LAYOUT' AND EXISTS (SELECT 1 FROM venue_import_file WHERE session_id=p AND role='SEAT_LAYOUT' AND layout_key IS DISTINCT FROM NEW.layout_key AND id<>NEW.id) THEN RAISE EXCEPTION 'IMPORT_MULTIPLE_LAYOUT_GROUPS'; END IF;
        IF TG_OP='UPDATE' AND NEW.source_bytes IS DISTINCT FROM OLD.source_bytes THEN
            NEW.validation_status:='UPLOADED'; NEW.sanitized_svg:=NULL; NEW.schema_version:=NULL;
            NEW.role:='UNKNOWN'; NEW.layout_key:=NULL; NEW.parsed_metadata:='{}'; NEW.issues:='[]';
        END IF;
    END IF;
    UPDATE venue_import_session SET revision=revision+1 WHERE id=p;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER venue_import_file_guard BEFORE INSERT OR UPDATE OR DELETE ON venue_import_file
    FOR EACH ROW EXECUTE FUNCTION catalog_guard_import_file();
