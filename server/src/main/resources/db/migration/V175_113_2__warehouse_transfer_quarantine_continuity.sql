DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_transfer(uuid,uuid)'::regprocedure);
    IF strpos(definition,'remaining:=dispatched-received-')=0 THEN RAISE EXCEPTION 'transfer quantity anchor missing'; END IF;
    EXECUTE replace(definition,'remaining:=dispatched-received-',$body$
        IF EXISTS(SELECT FROM inventory_movement_leg incoming JOIN inventory_movement receipt
            ON receipt.tenant_id=incoming.tenant_id AND receipt.id=incoming.movement_id
            WHERE incoming.tenant_id=scope AND receipt.document_id=target AND incoming.document_line_id=line.id
            AND incoming.direction='IN' AND incoming.status='AVAILABLE' AND receipt.operation_namespace='warehouse.transfer.receive')
            AND NOT EXISTS(SELECT FROM inventory_movement_leg outgoing JOIN inventory_movement dispatch
            ON dispatch.tenant_id=outgoing.tenant_id AND dispatch.id=outgoing.movement_id
            WHERE outgoing.tenant_id=scope AND dispatch.document_id=target AND outgoing.document_line_id=line.id
            AND outgoing.direction='OUT' AND outgoing.status='AVAILABLE' AND dispatch.operation_namespace='warehouse.transfer.dispatch') THEN
            RAISE EXCEPTION 'transfer cannot release quarantine' USING ERRCODE='23514'; END IF;
        remaining:=dispatched-received-$body$);
END $$;
