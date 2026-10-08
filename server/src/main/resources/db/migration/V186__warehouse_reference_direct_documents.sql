CREATE TABLE inventory_reference_post (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), document_id uuid NOT NULL,
    action varchar(16) NOT NULL CHECK(action IN ('RECEIPT','TRANSFER')),
    source_location_id uuid NOT NULL, destination_location_id uuid NOT NULL,
    expected_legs jsonb NOT NULL CHECK(jsonb_typeof(expected_legs)='array' AND jsonb_array_length(expected_legs)>0),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,document_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,source_location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY(tenant_id,destination_location_id) REFERENCES inventory_location(tenant_id,id),
    CHECK(source_location_id<>destination_location_id)
);
ALTER TABLE inventory_reference_post ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_reference_post FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_reference_post
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_reference_post_immutable BEFORE UPDATE OR DELETE ON inventory_reference_post
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT ON inventory_reference_post TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_reference_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding inventory_reference_post; document inventory_document; operation inventory_operation;
    movement inventory_movement; actual jsonb; expected jsonb; line inventory_document_line;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target;
    IF binding.id IS NULL THEN
        IF EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target
            AND starts_with(operation_namespace,'warehouse.reference.')) THEN
            RAISE EXCEPTION 'reference stock posting requires bound document' USING ERRCODE='23514'; END IF;
        RETURN;
    END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=binding.id;
    IF document.kind<>binding.action OR document.state<>(CASE binding.action WHEN 'RECEIPT' THEN 'PUTAWAY' ELSE 'RECEIVED' END)
        OR document.revision<>1 OR operation.document_id IS DISTINCT FROM target OR operation.document_revision<>1
        OR operation.namespace IS DISTINCT FROM 'warehouse.reference.'||lower(binding.action)
        OR operation.resource_id IS DISTINCT FROM target OR operation.business_action IS DISTINCT FROM binding.action
        OR movement.document_id IS DISTINCT FROM target OR movement.document_revision IS DISTINCT FROM 1
        OR movement.kind IS DISTINCT FROM (CASE binding.action WHEN 'RECEIPT' THEN 'RECEIVE' ELSE 'TRANSFER' END)
        OR movement.operation_namespace IS DISTINCT FROM operation.namespace OR movement.state IS DISTINCT FROM 'APPLIED'
        OR operation.cutover_epoch<>document.cutover_epoch
        OR document.work_order_id IS NOT NULL OR document.source_document_id IS NOT NULL
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=target)<>1 THEN
        RAISE EXCEPTION 'reference document and operation mismatch' USING ERRCODE='23514'; END IF;
    SELECT jsonb_agg(item ORDER BY item::text) INTO expected FROM jsonb_array_elements(binding.expected_legs) item;
    SELECT jsonb_agg(item ORDER BY item::text) INTO actual FROM (
        SELECT jsonb_build_object('lineId',document_line_id,'direction',direction,'identityId',stock_identity_id,
            'skuId',sku_id,'lotId',lot_id,'locationId',location_id,'custodianId',custody_owner_id,
            'custodianKind',custody_owner_kind,'condition',condition,'legalOwner',legal_owner,
            'status',status,'quantityBase',quantity_base::text,'baseUnit',base_unit) item
        FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id) rows;
    IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'reference movement differs from frozen legs' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id
        AND kind IN ('WAREHOUSE','BIN') AND state='ACTIVE' AND issue_eligible) THEN
        RAISE EXCEPTION 'direct destination must be available warehouse' USING ERRCODE='23514'; END IF;
    IF binding.action='RECEIPT' AND NOT EXISTS(SELECT FROM inventory_receipt_intake
        WHERE tenant_id=scope AND id=target AND source_location_id=binding.source_location_id
            AND inspection_location_id=binding.destination_location_id) THEN
        RAISE EXCEPTION 'direct receipt intake mismatch' USING ERRCODE='23514'; END IF;
    FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
        IF line.condition<>'SERVICEABLE' OR line.legal_owner<>'ISP' OR line.custodian_kind<>'WAREHOUSE'
            OR line.location_id<>(CASE binding.action WHEN 'RECEIPT' THEN binding.destination_location_id ELSE binding.source_location_id END)
            OR line.custodian_id<>line.location_id OR line.destination_location_id<>
                (CASE binding.action WHEN 'RECEIPT' THEN binding.source_location_id ELSE binding.destination_location_id END)
            OR (SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_movement_leg
                WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id
                    AND direction='IN' AND location_id=binding.destination_location_id)<>line.quantity_base
            OR EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id
                AND document_line_id=line.id AND (condition<>'SERVICEABLE' OR legal_owner<>'ISP' OR
                direction='IN' AND (status<>'AVAILABLE' OR custody_owner_kind<>'WAREHOUSE' OR custody_owner_id<>location_id) OR
                direction='OUT' AND (location_id<>binding.source_location_id OR
                    status<>(CASE binding.action WHEN 'RECEIPT' THEN 'RECEIPT_SOURCE' ELSE 'AVAILABLE' END)) OR
                location_id NOT IN (binding.source_location_id,binding.destination_location_id))) THEN
            RAISE EXCEPTION 'direct stock dimensions or quantity mismatch' USING ERRCODE='23514'; END IF;
    END LOOP;
END $$;
CREATE FUNCTION warehouse_reference_post_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END; target uuid;
BEGIN
    target:=CASE TG_TABLE_NAME WHEN 'inventory_document' THEN (data->>'id')::uuid
        WHEN 'inventory_movement_leg' THEN (SELECT document_id FROM inventory_movement
            WHERE tenant_id=(data->>'tenant_id')::uuid AND id=(data->>'movement_id')::uuid)
        ELSE (data->>'document_id')::uuid END;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_reference_post((data->>'tenant_id')::uuid,target); END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE relation text; definition text; anchor text:='IF NOT permitted THEN'; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_post','inventory_document','inventory_document_line',
        'inventory_operation','inventory_movement','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_reference_post_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_post_final_guard()',relation);
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'document guard layout changed'; END IF;
    definition:=replace(definition,anchor,'IF OLD.state=''DRAFT'' AND
        (OLD.kind,NEW.state) IN ((''RECEIPT'',''PUTAWAY''),(''TRANSFER'',''RECEIVED'')) AND EXISTS(
            SELECT FROM inventory_reference_post binding JOIN inventory_operation operation
                ON operation.tenant_id=binding.tenant_id AND operation.id=binding.id
            JOIN inventory_tenant_cutover policy ON policy.tenant_id=binding.tenant_id
            WHERE binding.tenant_id=NEW.tenant_id AND binding.document_id=NEW.id AND binding.action=NEW.kind
                AND binding.created_xid=pg_current_xact_id() AND operation.document_revision=NEW.revision
                AND operation.namespace=''warehouse.reference.''||lower(NEW.kind)
                AND policy.state=''ENFORCED'' AND policy.workflow_mode=''REFERENCE'' AND policy.epoch=NEW.cutover_epoch) THEN
        permitted:=true;
    END IF;
    IF NOT permitted THEN');
    EXECUTE definition;
END $$;
