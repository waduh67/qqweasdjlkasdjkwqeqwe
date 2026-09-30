DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='IF NOT permitted THEN RAISE EXCEPTION ''invalid document lifecycle''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'document lifecycle extension point changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF OLD.kind='RETURN' AND OLD.state='DRAFT' AND NEW.state IN ('RECEIVED_IN_INSPECTION','ACCEPTED')
        AND EXISTS(SELECT FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.id) THEN
        permitted:=true;
    END IF;
    IF NOT permitted THEN RAISE EXCEPTION 'invalid document lifecycle'$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_return(uuid,uuid)'::regprocedure);
    anchor:='CASE WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED'' ELSE ''RECEIVED_IN_INSPECTION'' END';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'return document state extension point changed'; END IF;
    definition:=replace(definition,anchor,
        'CASE WHEN document.revision=0 THEN ''DRAFT'' WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED'' ELSE ''RECEIVED_IN_INSPECTION'' END');
    EXECUTE definition;
END $$;
