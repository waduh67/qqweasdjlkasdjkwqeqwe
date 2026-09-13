CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_customer_tenant_id ON customer(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_subscription_tenant_id ON subscription(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_access_tenant_id ON subscriber_access(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_visit_tenant_id ON fieldservice_visit(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_visit_operation_tenant_id ON fieldservice_visit_operation(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS fulfillment_order_tenant_id ON order_record(tenant_id,id);
ALTER TABLE order_operation ADD COLUMN fulfillment_xid xid8;

CREATE TABLE customer_fulfillment_receipt (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    customer_id uuid NOT NULL, subscription_id uuid NOT NULL, actor_id uuid NOT NULL,
    namespace varchar(120) NOT NULL, operation_key varchar(240) NOT NULL, payload_hash varchar(64) NOT NULL,
    action varchar(16) NOT NULL CHECK(action IN ('ACTIVATE','TERMINATE')),
    source_state varchar(32) NOT NULL, result_state varchar(32) NOT NULL,
    source_hash varchar(64) NOT NULL, result_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,namespace,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,subscription_id) REFERENCES subscription(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK((action='ACTIVATE' AND result_state='ACTIVE') OR (action='TERMINATE' AND result_state='TERMINATED')),
    CHECK(created_xid=pg_current_xact_id())
);
CREATE INDEX customer_fulfillment_receipt_target ON customer_fulfillment_receipt(tenant_id,subscription_id);
CREATE INDEX customer_fulfillment_receipt_customer ON customer_fulfillment_receipt(tenant_id,customer_id);
CREATE INDEX customer_fulfillment_receipt_actor ON customer_fulfillment_receipt(tenant_id,actor_id);

CREATE TABLE bng_fulfillment_receipt (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    access_id uuid NOT NULL, customer_id uuid NOT NULL, subscription_id uuid NOT NULL, actor_id uuid NOT NULL,
    namespace varchar(120) NOT NULL, operation_key varchar(240) NOT NULL, payload_hash varchar(64) NOT NULL,
    action varchar(16) NOT NULL CHECK(action IN ('ACTIVATE','TERMINATE')),
    source_state varchar(32) NOT NULL, result_state varchar(32) NOT NULL,
    source_hash varchar(64) NOT NULL, result_hash varchar(64) NOT NULL, action_ids uuid[] NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,namespace,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,access_id) REFERENCES subscriber_access(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,subscription_id) REFERENCES subscription(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK((action='ACTIVATE' AND result_state='ACTIVE') OR (action='TERMINATE' AND result_state='TERMINATED')),
    CHECK(created_xid=pg_current_xact_id())
);
CREATE INDEX bng_fulfillment_receipt_access ON bng_fulfillment_receipt(tenant_id,access_id);
CREATE INDEX bng_fulfillment_receipt_subscription ON bng_fulfillment_receipt(tenant_id,subscription_id);
CREATE INDEX bng_fulfillment_receipt_customer ON bng_fulfillment_receipt(tenant_id,customer_id);
CREATE INDEX bng_fulfillment_receipt_actor ON bng_fulfillment_receipt(tenant_id,actor_id);

CREATE TABLE fieldservice_fulfillment_receipt (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), visit_id uuid NOT NULL, operation_id uuid NOT NULL,
    actor_id uuid NOT NULL, namespace varchar(120) NOT NULL, operation_key varchar(240) NOT NULL, payload_hash varchar(64) NOT NULL,
    source_revision bigint NOT NULL CHECK(source_revision>=0), result_revision bigint NOT NULL,
    source_state varchar(32) NOT NULL CHECK(source_state='CHECKED_OUT'), result_state varchar(32) NOT NULL CHECK(result_state='SUBMITTED'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,namespace,operation_key), UNIQUE(tenant_id,operation_id),
    FOREIGN KEY(tenant_id,id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,visit_id) REFERENCES fieldservice_visit(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES fieldservice_visit_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK(result_revision=source_revision+1), CHECK(created_xid=pg_current_xact_id())
);
CREATE INDEX fieldservice_fulfillment_receipt_visit ON fieldservice_fulfillment_receipt(tenant_id,visit_id);
CREATE INDEX fieldservice_fulfillment_receipt_actor ON fieldservice_fulfillment_receipt(tenant_id,actor_id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['customer_fulfillment_receipt','bng_fulfillment_receipt','fieldservice_fulfillment_receipt'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_fulfillment_transition_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF pg_trigger_depth()<2 OR NEW.transaction_id<>pg_current_xact_id() THEN
        RAISE EXCEPTION 'FULFILLMENT_TRANSITION_MUST_BE_CAPTURED' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

DO $$ DECLARE family text; owner_table text; transition_table text;
BEGIN
    FOREACH family IN ARRAY ARRAY['customer','bng','fieldservice','order'] LOOP
        owner_table:=CASE family WHEN 'customer' THEN 'subscription' WHEN 'bng' THEN 'subscriber_access' WHEN 'order' THEN 'order_record' ELSE 'fieldservice_visit' END;
        transition_table:=family||'_fulfillment_transition';
        EXECUTE format('CREATE TABLE %I (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES tenant(id),approval_id uuid NOT NULL,owner_id uuid NOT NULL,
            source_state text NOT NULL,result_state text NOT NULL,source_hash varchar(64) NOT NULL,result_hash varchar(64) NOT NULL,
            source_revision bigint,result_revision bigint,transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
            created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(tenant_id,id),
            FOREIGN KEY(tenant_id,approval_id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
            FOREIGN KEY(tenant_id,owner_id) REFERENCES %I(tenant_id,id))',transition_table,owner_table);
        EXECUTE format('CREATE INDEX %I ON %I(tenant_id,approval_id,transaction_id)',transition_table||'_approval_idx',transition_table);
        EXECUTE format('CREATE INDEX %I ON %I(tenant_id,owner_id)',transition_table||'_owner_idx',transition_table);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',transition_table);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',transition_table);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',transition_table);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',transition_table);
        EXECUTE format('CREATE TRIGGER warehouse_fulfillment_transition_insert BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_transition_insert_guard()',transition_table);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_fulfillment_transition_capture() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE approval uuid; frozen jsonb; family text; matches boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.tenant_id<>OLD.tenant_id THEN RAISE EXCEPTION 'FULFILLMENT_OWNER_TENANT_IMMUTABLE' USING ERRCODE='23514'; END IF;
    approval:=NULLIF(current_setting('app.fulfillment_approval_id',true),'')::uuid;
    IF approval IS NULL THEN RETURN NEW; END IF;
    SELECT snapshot::jsonb INTO frozen FROM fulfillment_approval_snapshot WHERE tenant_id=NEW.tenant_id AND id=approval;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_TRANSITION_APPROVAL_MISSING' USING ERRCODE='23514'; END IF;
    family:=CASE TG_TABLE_NAME WHEN 'subscription' THEN 'customer' WHEN 'subscriber_access' THEN 'bng' WHEN 'order_record' THEN 'order' ELSE 'fieldservice' END;
    matches:=CASE family WHEN 'customer' THEN frozen->'workOrder'->'material'->>'subscriptionId'=NEW.id::text
        WHEN 'bng' THEN frozen->>'bngAccessId'=NEW.id::text WHEN 'order' THEN frozen->'workOrder'->'material'->>'orderId'=NEW.id::text
        ELSE frozen->'visit'->>'id'=NEW.id::text END;
    IF matches THEN
        EXECUTE format('INSERT INTO %I(tenant_id,approval_id,owner_id,source_state,result_state,source_hash,result_hash,source_revision,result_revision)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)',family||'_fulfillment_transition')
            USING NEW.tenant_id,approval,NEW.id,coalesce(to_jsonb(OLD)->>'status',to_jsonb(OLD)->>'state'),
                coalesce(to_jsonb(NEW)->>'status',to_jsonb(NEW)->>'state'),encode(sha256(convert_to(to_jsonb(OLD)::text,'UTF8')),'hex'),
                encode(sha256(convert_to(to_jsonb(NEW)::text,'UTF8')),'hex'),(to_jsonb(OLD)->>'revision')::bigint,(to_jsonb(NEW)->>'revision')::bigint;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_fulfillment_transition_capture AFTER UPDATE ON subscription FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_transition_capture();
CREATE TRIGGER warehouse_fulfillment_transition_capture AFTER UPDATE ON subscriber_access FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_transition_capture();
CREATE TRIGGER warehouse_fulfillment_transition_capture AFTER UPDATE ON fieldservice_visit FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_transition_capture();
CREATE TRIGGER warehouse_fulfillment_transition_capture AFTER UPDATE ON order_record FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_transition_capture();

CREATE FUNCTION warehouse_order_fulfillment_stamp() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' THEN NEW.fulfillment_xid:=pg_current_xact_id();
    ELSIF NEW.fulfillment_xid IS DISTINCT FROM OLD.fulfillment_xid THEN
        RAISE EXCEPTION 'FULFILLMENT_ORDER_OPERATION_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_order_fulfillment_stamp BEFORE INSERT OR UPDATE ON order_operation FOR EACH ROW EXECUTE FUNCTION warehouse_order_fulfillment_stamp();
