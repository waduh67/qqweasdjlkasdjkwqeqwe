-- A pre125 to_jsonb(execution) seal has no RMA columns. Compare only these
-- absent/null extension fields equivalently; retain every stored seal byte and
-- never strip a non-null RMA source or any original receipt/plan field.
CREATE FUNCTION warehouse_stable_acceptance_origin(value jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN jsonb_typeof(value->'execution')='object'
        AND coalesce(value#>'{execution,rma_handover_id}','null'::jsonb)='null'::jsonb
        AND coalesce(value#>'{execution,rma_origin_snapshot}','null'::jsonb)='null'::jsonb
        THEN value #- '{execution,rma_handover_id}' #- '{execution,rma_origin_snapshot}' ELSE value END;
$$;

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_asset_handover(uuid,uuid)'::regprocedure);
    anchor:='seal.origin_snapshot IS DISTINCT FROM origin';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'acceptance origin comparison changed'; END IF;
    EXECUTE replace(definition,anchor,'warehouse_stable_acceptance_origin(seal.origin_snapshot) IS DISTINCT FROM warehouse_stable_acceptance_origin(origin)');

    definition:=pg_get_functiondef('warehouse_capture_acceptance_origin()'::regprocedure);
    anchor:='''titleRevision'',CASE handover.ownership_mode WHEN ''SALE'' THEN 1 ELSE 0 END';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'acceptance title revision entry changed'; END IF;
    definition:=replace(definition,anchor,'''titleRevision'',CASE WHEN permit.purpose=''RETURN_CUSTOMER_RMA'' THEN 0 WHEN handover.ownership_mode=''SALE'' THEN 1 ELSE 0 END');
    anchor:='asset.legal_owner<>''ISP''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'acceptance source owner entry changed'; END IF;
    definition:=replace(definition,anchor,'asset.legal_owner<>(CASE permit.purpose WHEN ''RETURN_CUSTOMER_RMA'' THEN ''CUSTOMER'' ELSE ''ISP'' END)');
    anchor:='PERFORM warehouse_assert_asset_intent(NEW.tenant_id,asset.id,handover.ownership_mode);';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'acceptance source seal entry changed'; END IF;
    definition:=replace(definition,anchor,anchor||$patch$
    IF permit.purpose='RETURN_CUSTOMER_RMA' THEN PERFORM warehouse_assert_rma_execution(NEW.tenant_id,permit.id); END IF;
    $patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_asset_handover_v69(uuid,uuid)'::regprocedure);
    anchor:='original->>''legal_owner'' IS DISTINCT FROM ''ISP''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'handover initial owner entry changed'; END IF;
    definition:=replace(definition,anchor,'original->>''legal_owner'' IS DISTINCT FROM (CASE assignment.purpose WHEN ''RETURN_CUSTOMER_RMA'' THEN ''CUSTOMER'' ELSE ''ISP'' END)');
    anchor:='IF handover.ownership_mode=''LOAN'' THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'handover disposition entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF assignment.purpose='RETURN_CUSTOMER_RMA' THEN
        PERFORM warehouse_assert_rma_execution(scope,acceptance.authorization_id);
        IF handover.ownership_mode<>'SALE' OR acceptance.source_title_revision<>0
            OR snapshot#>>'{assignment,titleRevision}' IS DISTINCT FROM '0'
            OR EXISTS(SELECT FROM inventory_asset_recovery_obligation WHERE tenant_id=scope AND assignment_id=assignment.id)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=acceptance.operation_id)
            OR EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND document_id=acceptance.operation_id) THEN
            RAISE EXCEPTION 'RMA_ACCEPTANCE_MUST_PRESERVE_CUSTOMER_TITLE_AND_STOCK' USING ERRCODE='23514'; END IF;
    ELSIF handover.ownership_mode='LOAN' THEN$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assignment_history_guard()'::regprocedure);
    anchor:='AND OLD.legal_owner=''ISP'' AND NEW.legal_owner=warehouse_asset_expected_owner(NEW.tenant_id,NEW.id)';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'accepted assignment owner entry changed'; END IF;
    definition:=replace(definition,anchor,
        'AND (OLD.legal_owner=''ISP'' OR (OLD.purpose=''RETURN_CUSTOMER_RMA'' AND OLD.legal_owner=''CUSTOMER'')) AND NEW.legal_owner=warehouse_asset_expected_owner(NEW.tenant_id,NEW.id)');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_current_asset_title(uuid,uuid)'::regprocedure);
    anchor:='IF handover.ownership_mode=''SALE'' THEN owner:=''CUSTOMER''; title_revision:=1; END IF;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current title acceptance entry changed'; END IF;
    definition:=replace(definition,anchor,'IF handover.ownership_mode=''SALE'' AND assignment.purpose<>''RETURN_CUSTOMER_RMA'' THEN owner:=''CUSTOMER''; title_revision:=1; END IF;');
    EXECUTE definition;
END $$;
