CREATE TABLE hris_employee (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    custodian_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT hris_employee_status_ck CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT hris_employee_user_uq UNIQUE (tenant_id, user_id)
);
CREATE TABLE hris_employment (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid NOT NULL,
    valid_from date NOT NULL, valid_to date, title varchar(160) NOT NULL,
    CONSTRAINT hris_employment_dates_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT hris_employment_employee_fk FOREIGN KEY (employee_id) REFERENCES hris_employee(id)
);
CREATE TABLE hris_shift (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, name varchar(120) NOT NULL,
    start_time time NOT NULL, end_time time NOT NULL,
    CONSTRAINT hris_shift_name_uq UNIQUE (tenant_id, name)
);
CREATE TABLE hris_roster (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid NOT NULL, shift_id uuid NOT NULL,
    valid_from date NOT NULL, valid_to date,
    CONSTRAINT hris_roster_dates_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT hris_roster_employee_fk FOREIGN KEY (employee_id) REFERENCES hris_employee(id),
    CONSTRAINT hris_roster_shift_fk FOREIGN KEY (shift_id) REFERENCES hris_shift(id)
);
CREATE INDEX hris_roster_effective_idx ON hris_roster (tenant_id, employee_id, valid_from, valid_to);
CREATE TABLE hris_exception (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid, exception_date date NOT NULL,
    kind varchar(16) NOT NULL, reason varchar(500) NOT NULL,
    CONSTRAINT hris_exception_kind_ck CHECK (kind IN ('LEAVE','HOLIDAY'))
);
CREATE TABLE hris_attendance_session (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid NOT NULL, work_date date NOT NULL,
    check_in_at timestamptz NOT NULL, check_out_at timestamptz, decision varchar(24) NOT NULL,
    gps_evidence boolean NOT NULL DEFAULT false,
    CONSTRAINT hris_attendance_decision_ck CHECK (decision IN ('ACCEPTED','REVIEW_REQUIRED','REJECTED','EXCUSED')),
    CONSTRAINT hris_attendance_times_ck CHECK (check_out_at IS NULL OR check_out_at >= check_in_at),
    CONSTRAINT hris_attendance_employee_fk FOREIGN KEY (employee_id) REFERENCES hris_employee(id),
    CONSTRAINT hris_attendance_day_uq UNIQUE (tenant_id, employee_id, work_date)
);
CREATE TABLE hris_attendance_correction (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, session_id uuid NOT NULL, requester_id uuid NOT NULL,
    custodian_id uuid, requested_check_in timestamptz, requested_check_out timestamptz,
    reason varchar(500) NOT NULL, state varchar(16) NOT NULL, approver_id uuid, decided_at timestamptz,
    CONSTRAINT hris_correction_state_ck CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT hris_correction_session_fk FOREIGN KEY (session_id) REFERENCES hris_attendance_session(id)
);
CREATE TABLE hris_attendance_revision (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, session_id uuid NOT NULL, revision bigint NOT NULL,
    check_in_at timestamptz, check_out_at timestamptz, correction_id uuid NOT NULL, approved_at timestamptz NOT NULL,
    CONSTRAINT hris_attendance_revision_uq UNIQUE (tenant_id, session_id, revision),
    CONSTRAINT hris_revision_session_fk FOREIGN KEY (session_id) REFERENCES hris_attendance_session(id)
);
CREATE TABLE hris_attendance_period (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, valid_from date NOT NULL, valid_to date NOT NULL,
    closed_at timestamptz, reopened_at timestamptz,
    CONSTRAINT hris_period_dates_ck CHECK (valid_to >= valid_from),
    CONSTRAINT hris_period_uq UNIQUE (tenant_id, valid_from, valid_to)
);
CREATE TABLE hris_idempotency_outcome (
    tenant_id uuid NOT NULL, namespace varchar(120) NOT NULL, operation_key varchar(240) NOT NULL,
    payload_hash char(64) NOT NULL, outcome jsonb NOT NULL, created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, namespace, operation_key)
);
CREATE TABLE hris_audit (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, actor_id uuid, action varchar(120) NOT NULL,
    entity_type varchar(120) NOT NULL, entity_id uuid, detail jsonb NOT NULL, occurred_at timestamptz NOT NULL
);
CREATE TABLE hris_outbox (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, aggregate_id uuid NOT NULL, event_type varchar(120) NOT NULL,
    sequence bigint NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz,
    CONSTRAINT hris_outbox_order_uq UNIQUE (tenant_id, aggregate_id, sequence)
);
CREATE INDEX hris_attendance_tenant_date_idx ON hris_attendance_session (tenant_id, work_date);
CREATE INDEX hris_audit_tenant_time_idx ON hris_audit (tenant_id, occurred_at);
CREATE INDEX hris_outbox_pending_idx ON hris_outbox (tenant_id, published_at, created_at);

CREATE OR REPLACE FUNCTION hris_reject_overlapping_employment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hris_employment e WHERE e.tenant_id = NEW.tenant_id AND e.employee_id = NEW.employee_id AND e.id <> NEW.id
        AND daterange(e.valid_from, COALESCE(e.valid_to + 1, 'infinity'::date), '[)') && daterange(NEW.valid_from, COALESCE(NEW.valid_to + 1, 'infinity'::date), '[)'))
    THEN RAISE EXCEPTION 'overlapping employment records'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hris_employment_no_overlap BEFORE INSERT OR UPDATE ON hris_employment FOR EACH ROW EXECUTE FUNCTION hris_reject_overlapping_employment();
CREATE OR REPLACE FUNCTION hris_reject_overlapping_roster() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hris_roster r WHERE r.tenant_id = NEW.tenant_id AND r.employee_id = NEW.employee_id AND r.id <> NEW.id
        AND daterange(r.valid_from, COALESCE(r.valid_to + 1, 'infinity'::date), '[)') && daterange(NEW.valid_from, COALESCE(NEW.valid_to + 1, 'infinity'::date), '[)'))
    THEN RAISE EXCEPTION 'overlapping roster records'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER hris_roster_no_overlap BEFORE INSERT OR UPDATE ON hris_roster FOR EACH ROW EXECUTE FUNCTION hris_reject_overlapping_roster();

CREATE OR REPLACE FUNCTION hris_tenant_id_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id THEN RAISE EXCEPTION 'tenant_id is immutable'; END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['hris_employee','hris_employment','hris_shift','hris_roster','hris_exception','hris_attendance_session','hris_attendance_correction','hris_attendance_revision','hris_attendance_period','hris_idempotency_outcome','hris_audit','hris_outbox'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OF tenant_id ON %I FOR EACH ROW EXECUTE FUNCTION hris_tenant_id_immutable()', table_name || '_tenant_immutable', table_name);
  END LOOP;
END $$;

DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['hris_employee','hris_employment','hris_shift','hris_roster','hris_exception','hris_attendance_session','hris_attendance_correction','hris_attendance_revision','hris_attendance_period','hris_idempotency_outcome','hris_audit','hris_outbox'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name || '_tenant_policy', table_name);
  END LOOP;
END $$;
