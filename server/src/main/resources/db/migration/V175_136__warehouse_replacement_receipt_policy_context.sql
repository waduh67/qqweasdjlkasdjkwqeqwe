-- Cost stays unknown unless the vendor source explicitly supplies it. Null cost
-- does not alter the canonical payload of requests recorded before this version.
DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_capture_replacement_request()'::regprocedure);
    anchor:='input-ARRAY[''expectedRevision'',''externalReference'',''sourceLocationId'',''inspectionLocationId'',''skuId'',''serial'',''evidenceReference'',''mac'']';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement input binding changed'; END IF;
    definition:=replace(definition,anchor,
        'input-ARRAY[''expectedRevision'',''externalReference'',''sourceLocationId'',''inspectionLocationId'',''skuId'',''serial'',''evidenceReference'',''mac'',''cost'']');
    anchor:='    NEW.origin_snapshot:=';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement origin capture changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF coalesce(input->'cost','null'::jsonb)<>'null'::jsonb THEN
        IF jsonb_typeof(input->'cost') IS DISTINCT FROM 'object'
            OR input->'cost'-ARRAY['totalMinor','currency']<>'{}'::jsonb
            OR coalesce(input#>>'{cost,totalMinor}','') !~ '^[0-9]+$'
            OR coalesce(input#>>'{cost,currency}','') !~ '^[A-Z]{3}$' THEN
            RAISE EXCEPTION 'REPLACEMENT_DECLARED_COST_REQUIRED' USING ERRCODE='23514'; END IF;
        IF body#>'{receipt,intake,lines,0,cost}' IS DISTINCT FROM jsonb_build_object(
            'totalMinor',(input#>>'{cost,totalMinor}')::bigint::text,
            'currency',input#>>'{cost,currency}','costBasisQuantityBase','1') THEN
            RAISE EXCEPTION 'REPLACEMENT_DECLARED_COST_BINDING' USING ERRCODE='23514'; END IF;
    ELSIF body#>'{receipt,intake,lines,0,cost}' IS DISTINCT FROM 'null'::jsonb THEN
        RAISE EXCEPTION 'REPLACEMENT_UNKNOWN_COST_PRESERVED' USING ERRCODE='23514';
    END IF;
    NEW.origin_snapshot:=$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_replacement_request(uuid,uuid)'::regprocedure);
    anchor:=$anchor$document.kind<>'RECEIPT' OR NOT (
        document.state='DRAFT' AND document.revision=0 OR
        document.state IN ('RECEIVED_IN_INSPECTION','PUTAWAY','CLOSED') AND document.revision>=1)
        OR document.approval_disposition IS NOT NULL$anchor$;
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement receipt lifecycle changed'; END IF;
    definition:=replace(definition,anchor,$patch$document.kind<>'RECEIPT' OR NOT (
        document.state='DRAFT' AND document.revision=0 AND document.approval_disposition IS NULL OR
        document.state='DRAFT' AND document.revision=1 AND document.approval_disposition='REWORK_REQUIRED' AND EXISTS(
            SELECT FROM inventory_approval WHERE tenant_id=scope AND source_document_id=request.receipt_id
                AND source_document_revision=0 AND status='REWORK_REQUIRED') OR
        document.state IN ('RECEIVED_IN_INSPECTION','PUTAWAY','CLOSED') AND document.revision>=1 AND document.approval_disposition IS NULL)$patch$);
    anchor:='OR line.lot_id IS NOT NULL';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement line binding changed'; END IF;
    definition:=replace(definition,anchor,$patch$OR line.lot_id IS NOT NULL
        OR (line.cost_total_minor,line.cost_basis_quantity_base,line.currency) IS DISTINCT FROM
            ((body#>>'{receipt,intake,lines,0,cost,totalMinor}')::bigint,
                (body#>>'{receipt,intake,lines,0,cost,costBasisQuantityBase}')::bigint,body#>>'{receipt,intake,lines,0,cost,currency}')$patch$);
    EXECUTE definition;
END $migration$;
