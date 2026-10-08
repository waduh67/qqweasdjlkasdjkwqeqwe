CREATE TABLE inventory_reference_activation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), actor_id uuid NOT NULL,
    authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), operation_key varchar(240) NOT NULL,
    canonical_payload text NOT NULL, payload_hash text NOT NULL, review_hash text NOT NULL,
    previous_epoch bigint NOT NULL, resulting_epoch bigint NOT NULL,
    snapshot jsonb NOT NULL, original_body text NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key), UNIQUE(tenant_id,resulting_epoch),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK(resulting_epoch=previous_epoch+1),
    CHECK(payload_hash ~ '^[0-9a-f]{64}$' AND review_hash ~ '^[0-9a-f]{64}$')
);
ALTER TABLE inventory_reference_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_reference_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_reference_activation
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_reference_activation_immutable BEFORE UPDATE OR DELETE ON inventory_reference_activation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_reference_review(scope uuid) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE policy inventory_tenant_cutover; issues jsonb:='[]'; snapshot jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT policy FROM inventory_tenant_cutover WHERE tenant_id=scope FOR UPDATE;
    IF policy.state<>'ENFORCED' THEN issues:=issues||jsonb_build_array('SAFETY_NOT_ENFORCED'); END IF;
    IF policy.workflow_mode<>'DRAINING' THEN issues:=issues||jsonb_build_array('WORKFLOW_NOT_DRAINING'); END IF;
    IF EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND
        warehouse_document_current_state(scope,id,state) NOT IN ('EXPIRED','CANCELLED','CLOSED','PUTAWAY','RECEIVED','POSTED','ACCEPTED','SCRAP','LOST')) THEN
        issues:=issues||jsonb_build_array('OPEN_LEGACY_DOCUMENTS'); END IF;
    IF EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=scope AND state='OPEN'
        AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
        issues:=issues||jsonb_build_array('OPEN_RESERVATIONS'); END IF;
    IF EXISTS(SELECT FROM work_order WHERE tenant_id=scope AND
        (status NOT IN ('DONE','CANCELLED') OR approval_status='PENDING')) THEN
        issues:=issues||jsonb_build_array('OPEN_LEGACY_WORK_ORDERS'); END IF;
    IF EXISTS(SELECT FROM fulfillment_checkpoint WHERE tenant_id=scope AND
        state NOT IN ('APPLIED','MANUAL_RESOLVED')) THEN
        issues:=issues||jsonb_build_array('UNRESOLVED_FULFILLMENT'); END IF;
    IF EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND
        (warehouse_admission<>'VERIFIED' OR quantity_base IS NULL OR base_unit NOT IN ('EA','MM') OR
        quantity_base>0 AND (legal_owner='UNKNOWN' OR custody_owner_id IS NULL OR custody_owner_kind IS NULL))) OR
        EXISTS(SELECT FROM inventory_segment WHERE tenant_id=scope AND warehouse_admission<>'VERIFIED') OR
        EXISTS(SELECT FROM inventory_serialized_asset WHERE tenant_id=scope AND warehouse_admission<>'VERIFIED') OR
        EXISTS(SELECT FROM inventory_lot WHERE tenant_id=scope AND warehouse_admission<>'VERIFIED') OR
        EXISTS(SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND state IN ('LEGACY_RESERVED','CONFLICT')) THEN
        issues:=issues||jsonb_build_array('UNRESOLVED_STOCK_OR_IDENTITIES'); END IF;
    IF EXISTS(SELECT FROM inventory_balance_projection balance LEFT JOIN app_user holder
        ON holder.tenant_id=balance.tenant_id AND holder.id=balance.custody_owner_id
        WHERE balance.tenant_id=scope AND balance.quantity_base>0 AND
        (balance.status='IN_TRANSIT' OR balance.custody_owner_kind='TRANSIT' OR
        balance.custody_owner_kind IN ('TECHNICIAN','VEHICLE') AND holder.id IS NULL)) THEN
        issues:=issues||jsonb_build_array('UNRESOLVED_HOLDERS_OR_TRANSIT'); END IF;
    snapshot:=jsonb_build_object('tenantId',scope,'epoch',policy.epoch,
        'balances',coalesce((SELECT jsonb_agg(to_jsonb(row) ORDER BY id) FROM inventory_balance_projection row WHERE tenant_id=scope),'[]'),
        'segments',coalesce((SELECT jsonb_agg(to_jsonb(row) ORDER BY id) FROM inventory_segment row WHERE tenant_id=scope),'[]'),
        'claims',coalesce((SELECT jsonb_agg(to_jsonb(row) ORDER BY id) FROM inventory_identity_claim row WHERE tenant_id=scope),'[]'),
        'documents',coalesce((SELECT jsonb_agg(jsonb_build_object('id',id,'revision',revision,'kind',kind,'state',
            warehouse_document_current_state(scope,id,state)) ORDER BY id) FROM inventory_document WHERE tenant_id=scope),'[]'),
        'issues',issues);
    RETURN jsonb_build_object('expectedEpoch',policy.epoch,'issues',issues,'snapshot',snapshot,
        'reviewHash',encode(sha256(convert_to(snapshot::text,'UTF8')),'hex'));
END $$;

CREATE FUNCTION warehouse_activate_reference(p_actor uuid,p_authority bigint,p_key text,p_payload text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; review jsonb;
    input jsonb:=p_payload::jsonb; receipt uuid:=gen_random_uuid(); response jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    review:=warehouse_reference_review(scope);
    IF NOT EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=p_actor) OR
        NOT EXISTS(SELECT FROM app_user WHERE tenant_id=scope AND id=p_actor AND status='ACTIVE') OR
        NOT EXISTS(SELECT FROM iam_authorization_epoch WHERE tenant_id=scope AND epoch=p_authority FOR SHARE) THEN
        RAISE EXCEPTION 'activation requires current tenant owner authority' USING ERRCODE='23514'; END IF;
    IF review->'issues'<>'[]'::jsonb OR review->>'reviewHash' IS DISTINCT FROM input->>'reviewHash'
        OR review->>'expectedEpoch' IS DISTINCT FROM input->>'expectedEpoch' THEN
        RAISE EXCEPTION 'activation requires reconciled current snapshot' USING ERRCODE='23514'; END IF;
    IF p_key IS NULL OR length(p_key) NOT BETWEEN 1 AND 240 OR p_key !~ '^[!-~]+$' OR
        length(btrim(coalesce(input->>'reason',''))) NOT BETWEEN 1 AND 1000 OR input->>'reason' ~ '[[:cntrl:]]' OR
        input IS DISTINCT FROM jsonb_build_object('expectedEpoch',(review->>'expectedEpoch')::bigint,
            'reviewHash',review->>'reviewHash','reason',input->>'reason') THEN
        RAISE EXCEPTION 'activation command must be exact' USING ERRCODE='23514'; END IF;
    response:=jsonb_build_object('id',receipt,'workflow','REFERENCE','epoch',(review->>'expectedEpoch')::bigint+1,
        'reviewHash',review->>'reviewHash','activatedBy',p_actor,'activatedAt',transaction_timestamp());
    INSERT INTO inventory_reference_activation(id,tenant_id,actor_id,authority_epoch,operation_key,canonical_payload,
        payload_hash,review_hash,previous_epoch,resulting_epoch,snapshot,original_body)
        VALUES(receipt,scope,p_actor,p_authority,p_key,p_payload,encode(sha256(convert_to(p_payload,'UTF8')),'hex'),
            review->>'reviewHash',(review->>'expectedEpoch')::bigint,(review->>'expectedEpoch')::bigint+1,review->'snapshot',response::text);
    UPDATE inventory_tenant_cutover SET workflow_mode='REFERENCE',epoch=epoch+1,revision=revision+1,updated_at=clock_timestamp()
        WHERE tenant_id=scope AND epoch=(review->>'expectedEpoch')::bigint AND workflow_mode='DRAINING';
    IF NOT FOUND THEN RAISE EXCEPTION 'activation epoch changed' USING ERRCODE='40001'; END IF;
    RETURN response::text;
END $$;
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION warehouse_activate_reference(uuid,bigint,text,text) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
    REVOKE ALL ON FUNCTION warehouse_activate_reference(uuid,bigint,text,text) FROM PUBLIC;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_reference_activation FROM warehouse_app;
        GRANT SELECT ON inventory_reference_activation TO warehouse_app;
        GRANT EXECUTE ON FUNCTION warehouse_activate_reference(uuid,bigint,text,text) TO warehouse_app;
    END IF;
END $$;

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_workflow_guard()'::regprocedure);
    anchor:='IF (OLD.workflow_mode,NEW.workflow_mode)<>(''LEGACY'',''DRAINING'') THEN';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'workflow guard layout changed'; END IF;
    definition:=replace(definition,anchor,'IF (OLD.workflow_mode,NEW.workflow_mode)=(''DRAINING'',''REFERENCE'') THEN
        IF OLD.state<>''ENFORCED'' OR NEW.draining_from_epoch IS DISTINCT FROM OLD.draining_from_epoch OR NOT EXISTS(
            SELECT FROM inventory_reference_activation WHERE tenant_id=NEW.tenant_id AND previous_epoch=OLD.epoch
                AND resulting_epoch=NEW.epoch AND created_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION ''reference activation receipt required'' USING ERRCODE=''23514''; END IF;
        RETURN NEW;
    END IF;
    IF (OLD.workflow_mode,NEW.workflow_mode)<>(''LEGACY'',''DRAINING'') THEN');
    EXECUTE definition;
END $$;
