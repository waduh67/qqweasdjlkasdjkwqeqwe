DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_consume(uuid,uuid)'::regprocedure);
    anchor:='EXISTS(SELECT FROM inventory_movement_leg leg LEFT JOIN inventory_document_line line ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id
            WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND line.document_id IS DISTINCT FROM target)';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference consumed line binding anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'EXISTS(SELECT FROM inventory_movement_leg leg LEFT JOIN inventory_document_line document_line
            ON document_line.tenant_id=leg.tenant_id AND document_line.id=leg.document_line_id
            WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND document_line.document_id IS DISTINCT FROM target)');
END $$;
