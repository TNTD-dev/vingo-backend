ALTER TABLE venue ALTER COLUMN city DROP NOT NULL;
ALTER TABLE venue ADD COLUMN location jsonb;

CREATE TABLE parking_locations (
 id uuid PRIMARY KEY DEFAULT uuidv7(),
 venue_id uuid NOT NULL REFERENCES venue(id),
 setup_session_id uuid REFERENCES venue_import_session(id) ON DELETE SET NULL,
 ordinal integer NOT NULL,
 name text NOT NULL,
 location jsonb NOT NULL,
 is_primary boolean NOT NULL,
 price_status text NOT NULL CHECK(price_status IN ('VERIFIED','UNVERIFIED')),
 price_vnd bigint CHECK(price_vnd>=0),
 price_unit text CHECK(price_unit IN ('PER_ENTRY','PER_HOUR','PER_DAY')),
 data jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 deleted_at timestamptz,
 UNIQUE(setup_session_id,ordinal),
 CHECK ((price_status='VERIFIED' AND price_vnd IS NOT NULL AND price_unit IS NOT NULL)
     OR (price_status='UNVERIFIED' AND price_vnd IS NULL AND price_unit IS NULL))
);
CREATE UNIQUE INDEX parking_primary ON parking_locations(venue_id) WHERE is_primary AND deleted_at IS NULL;

CREATE TABLE venue_rules_documents (
 id uuid PRIMARY KEY,
 session_id uuid REFERENCES venue_import_session(id) ON DELETE SET NULL,
 venue_id uuid REFERENCES venue(id),
 uploaded_by uuid NOT NULL REFERENCES users(id),
 metadata jsonb NOT NULL,
 uploaded_at timestamptz NOT NULL,
 deleted_at timestamptz
);
CREATE UNIQUE INDEX rules_current_session ON venue_rules_documents(session_id) WHERE deleted_at IS NULL;
CREATE INDEX rules_by_venue ON venue_rules_documents(venue_id) WHERE deleted_at IS NULL;
