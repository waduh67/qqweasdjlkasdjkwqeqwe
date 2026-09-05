CREATE TABLE payroll_compensation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid NOT NULL,
    valid_from date NOT NULL, valid_to date, currency char(3) NOT NULL,
    monthly_base_minor bigint NOT NULL, hourly_rate_minor bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT payroll_comp_dates_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT payroll_comp_money_ck CHECK (monthly_base_minor >= 0 AND hourly_rate_minor >= 0)
);
CREATE TABLE payroll_component (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, employee_id uuid NOT NULL,
    code varchar(32) NOT NULL, kind varchar(24) NOT NULL, amount_minor bigint NOT NULL,
    currency char(3) NOT NULL, valid_from date NOT NULL, valid_to date,
    CONSTRAINT payroll_component_dates_ck CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT payroll_component_amount_ck CHECK (amount_minor >= 0),
    CONSTRAINT payroll_component_kind_ck CHECK (kind IN ('BASE_SALARY','OVERTIME','ALLOWANCE','BONUS'))
);
CREATE TABLE payroll_deduction_rule (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, code varchar(32) NOT NULL,
    kind varchar(16) NOT NULL, rate numeric(12,8), fixed_minor bigint, currency char(3),
    CONSTRAINT payroll_deduction_kind_ck CHECK (kind IN ('TAX','LEAVE','OTHER')),
    CONSTRAINT payroll_deduction_shape_ck CHECK ((rate IS NULL) <> (fixed_minor IS NULL)),
    CONSTRAINT payroll_deduction_amount_ck CHECK (rate IS NULL OR rate >= 0 AND rate <= 100)
);
CREATE TABLE payroll_period (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, valid_from date NOT NULL, valid_to date NOT NULL,
    pay_date date NOT NULL, closed_at timestamptz,
    CONSTRAINT payroll_period_dates_ck CHECK (valid_to >= valid_from AND pay_date >= valid_to),
    CONSTRAINT payroll_period_uq UNIQUE (tenant_id, valid_from, valid_to)
);
CREATE TABLE payroll_run (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, period_id uuid NOT NULL,
    operation_key varchar(240) NOT NULL, payload_hash char(64) NOT NULL,
    state varchar(16) NOT NULL, revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT payroll_run_state_ck CHECK (state IN ('DRAFT','CALCULATED','REVIEWED','APPROVED','PAID','VOIDED')),
    CONSTRAINT payroll_run_operation_uq UNIQUE (tenant_id, operation_key),
    CONSTRAINT payroll_run_period_fk FOREIGN KEY (period_id) REFERENCES payroll_period(id)
);
CREATE TABLE payroll_calculation_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, run_id uuid NOT NULL, employee_id uuid NOT NULL,
    compensation_id uuid NOT NULL, period_from date NOT NULL, period_to date NOT NULL,
    hris_session_ids jsonb NOT NULL, gross_minor bigint NOT NULL, deduction_minor bigint NOT NULL,
    tax_minor bigint NOT NULL, net_minor bigint NOT NULL, currency char(3) NOT NULL,
    calculated_at timestamptz NOT NULL,
    CONSTRAINT payroll_snapshot_run_employee_uq UNIQUE (tenant_id, run_id, employee_id),
    CONSTRAINT payroll_snapshot_amounts_ck CHECK (gross_minor >= 0 AND deduction_minor >= 0 AND tax_minor >= 0 AND net_minor >= 0),
    CONSTRAINT payroll_snapshot_run_fk FOREIGN KEY (run_id) REFERENCES payroll_run(id)
);
CREATE TABLE payroll_calculation_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, snapshot_id uuid NOT NULL,
    code varchar(32) NOT NULL, kind varchar(24) NOT NULL, amount_minor bigint NOT NULL,
    currency char(3) NOT NULL, CONSTRAINT payroll_line_amount_ck CHECK (amount_minor >= 0),
    CONSTRAINT payroll_line_snapshot_fk FOREIGN KEY (snapshot_id) REFERENCES payroll_calculation_snapshot(id)
);
CREATE TABLE payroll_approval (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, run_id uuid NOT NULL, tier integer NOT NULL,
    approver_id uuid NOT NULL, decision varchar(16) NOT NULL, reason varchar(500), decided_at timestamptz NOT NULL,
    CONSTRAINT payroll_approval_decision_ck CHECK (decision IN ('APPROVE','REJECT')),
    CONSTRAINT payroll_approval_tier_ck CHECK (tier > 0),
    CONSTRAINT payroll_approval_uq UNIQUE (tenant_id, run_id, tier, approver_id),
    CONSTRAINT payroll_approval_run_fk FOREIGN KEY (run_id) REFERENCES payroll_run(id)
);
CREATE TABLE payroll_payment (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, run_id uuid NOT NULL, operation_key varchar(240) NOT NULL,
    amount_minor bigint NOT NULL, currency char(3) NOT NULL, paid_at timestamptz NOT NULL,
    CONSTRAINT payroll_payment_amount_ck CHECK (amount_minor >= 0),
    CONSTRAINT payroll_payment_operation_uq UNIQUE (tenant_id, operation_key),
    CONSTRAINT payroll_payment_run_uq UNIQUE (tenant_id, run_id),
    CONSTRAINT payroll_payment_run_fk FOREIGN KEY (run_id) REFERENCES payroll_run(id)
);
CREATE TABLE payroll_void (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, run_id uuid NOT NULL, reversal_of uuid NOT NULL,
    actor_id uuid NOT NULL, reason varchar(500) NOT NULL, voided_at timestamptz NOT NULL,
    CONSTRAINT payroll_void_run_uq UNIQUE (tenant_id, run_id),
    CONSTRAINT payroll_void_run_fk FOREIGN KEY (run_id) REFERENCES payroll_run(id)
);
CREATE TABLE payroll_operation_outcome (
    tenant_id uuid NOT NULL, namespace varchar(120) NOT NULL, operation_key varchar(240) NOT NULL,
    payload_hash char(64) NOT NULL, status varchar(24) NOT NULL, outcome jsonb NOT NULL, created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, namespace, operation_key)
);
CREATE TABLE payroll_outbox (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, aggregate_id uuid NOT NULL, event_type varchar(120) NOT NULL,
    sequence bigint NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz,
    CONSTRAINT payroll_outbox_order_uq UNIQUE (tenant_id, aggregate_id, sequence)
);
CREATE TABLE payroll_audit (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, actor_id uuid, action varchar(120) NOT NULL,
    entity_type varchar(120) NOT NULL, entity_id uuid, detail jsonb NOT NULL, occurred_at timestamptz NOT NULL
);
CREATE INDEX payroll_comp_effective_idx ON payroll_compensation (tenant_id, employee_id, valid_from, valid_to);
CREATE INDEX payroll_component_effective_idx ON payroll_component (tenant_id, employee_id, valid_from, valid_to);
CREATE INDEX payroll_run_period_idx ON payroll_run (tenant_id, period_id, state);
CREATE INDEX payroll_audit_time_idx ON payroll_audit (tenant_id, occurred_at);
CREATE INDEX payroll_outbox_pending_idx ON payroll_outbox (tenant_id, published_at, created_at);

CREATE OR REPLACE FUNCTION payroll_tenant_id_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.tenant_id <> OLD.tenant_id THEN RAISE EXCEPTION 'tenant_id is immutable'; END IF; RETURN NEW; END $$;
CREATE OR REPLACE FUNCTION payroll_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'payroll history is append-only'; END $$;
DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['payroll_compensation','payroll_component','payroll_deduction_rule','payroll_period','payroll_run','payroll_calculation_snapshot','payroll_calculation_line','payroll_approval','payroll_payment','payroll_void','payroll_audit','payroll_operation_outcome','payroll_outbox'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OF tenant_id ON %I FOR EACH ROW EXECUTE FUNCTION payroll_tenant_id_immutable()', table_name || '_tenant_immutable', table_name);
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name || '_tenant_policy', table_name);
  END LOOP;
  FOREACH table_name IN ARRAY ARRAY['payroll_calculation_snapshot','payroll_calculation_line','payroll_approval','payroll_payment','payroll_void','payroll_audit'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION payroll_append_only()', table_name || '_append_only', table_name);
  END LOOP;
END $$;

CREATE OR REPLACE FUNCTION payroll_reject_overlapping_compensation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM payroll_compensation c WHERE c.tenant_id = NEW.tenant_id AND c.employee_id = NEW.employee_id AND c.id <> NEW.id
    AND daterange(c.valid_from, COALESCE(c.valid_to + 1, 'infinity'::date), '[)') && daterange(NEW.valid_from, COALESCE(NEW.valid_to + 1, 'infinity'::date), '[)'))
  THEN RAISE EXCEPTION 'overlapping compensation records'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER payroll_compensation_no_overlap BEFORE INSERT OR UPDATE ON payroll_compensation FOR EACH ROW EXECUTE FUNCTION payroll_reject_overlapping_compensation();
