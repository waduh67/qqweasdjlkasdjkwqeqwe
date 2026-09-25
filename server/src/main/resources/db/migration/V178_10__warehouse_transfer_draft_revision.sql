-- An unposted operational draft may change; every former command snapshot remains
-- immutable. Posted transfer bindings, quantities and discrepancy guards stay intact.
CREATE OR REPLACE FUNCTION warehouse_transfer_binding_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF TG_OP='UPDATE' THEN PERFORM warehouse_assert_deferred_scope(NEW.tenant_id); END IF;
    IF OLD.transfer_receiver_id IS NOT NULL THEN
        IF TG_OP='DELETE' THEN
            RAISE EXCEPTION 'transfer binding is immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.state='DRAFT' AND NEW.state='DRAFT' THEN
            IF NEW.revision<>OLD.revision+1 THEN
                RAISE EXCEPTION 'draft edit requires the next command revision' USING ERRCODE='23514';
            END IF;
            IF num_nonnulls(NEW.transfer_source_location_id,NEW.transfer_destination_location_id,
                NEW.transfer_transit_location_id,NEW.transfer_receiver_id)<>4 THEN
                RAISE EXCEPTION 'an existing transfer must retain its complete binding' USING ERRCODE='23514';
            END IF;
            IF to_jsonb(NEW)-ARRAY['revision','updated_at','reason','transfer_source_location_id',
                'transfer_destination_location_id','transfer_transit_location_id','transfer_receiver_id'] IS DISTINCT FROM
                to_jsonb(OLD)-ARRAY['revision','updated_at','reason','transfer_source_location_id',
                'transfer_destination_location_id','transfer_transit_location_id','transfer_receiver_id'] THEN
                RAISE EXCEPTION 'draft cannot change transfer ownership or history' USING ERRCODE='23514';
            END IF;
        ELSIF to_jsonb(NEW)-ARRAY['state','revision','updated_at','closed_at'] IS DISTINCT FROM
            to_jsonb(OLD)-ARRAY['state','revision','updated_at','closed_at'] THEN
            RAISE EXCEPTION 'posted transfer binding is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

DO $$ DECLARE definition text; old_binding text; new_binding text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_transfer(uuid,uuid)'::regprocedure);
    IF strpos(definition,'''warehouse.transfer.create'',''warehouse.transfer.dispatch''')=0 OR
        strpos(definition,'ELSIF operation.document_revision=0 THEN')=0 OR
        strpos(definition,'IF operation.namespace<>''warehouse.transfer.create'' OR snapshot->>''state''<>''DRAFT'' OR')=0 THEN
        RAISE EXCEPTION 'transfer draft revision anchors missing';
    END IF;
    definition:=replace(definition,'''warehouse.transfer.create'',''warehouse.transfer.dispatch''',
        '''warehouse.transfer.create'',''warehouse.transfer.update'',''warehouse.transfer.dispatch''');
    old_binding:=$old$snapshot->>'sourceLocationId' IS DISTINCT FROM document.transfer_source_location_id::text OR
            snapshot->>'destinationLocationId' IS DISTINCT FROM document.transfer_destination_location_id::text OR
            snapshot->>'transitLocationId' IS DISTINCT FROM document.transfer_transit_location_id::text OR
            snapshot->>'senderId' IS DISTINCT FROM document.actor_id::text OR
            snapshot->>'receiverId' IS DISTINCT FROM document.transfer_receiver_id::text OR$old$;
    new_binding:=$new$snapshot->>'senderId' IS DISTINCT FROM document.actor_id::text OR
            ((operation.document_revision=document.revision OR operation.namespace NOT IN
                ('warehouse.transfer.create','warehouse.transfer.update')) AND
                (snapshot->>'sourceLocationId' IS DISTINCT FROM document.transfer_source_location_id::text OR
                 snapshot->>'destinationLocationId' IS DISTINCT FROM document.transfer_destination_location_id::text OR
                 snapshot->>'transitLocationId' IS DISTINCT FROM document.transfer_transit_location_id::text OR
                 snapshot->>'receiverId' IS DISTINCT FROM document.transfer_receiver_id::text)) OR$new$;
    IF strpos(definition,old_binding)=0 OR
        strpos(definition,'IF snapshot->>''state'' IS DISTINCT FROM document.state OR')=0 THEN
        RAISE EXCEPTION 'transfer prior binding anchor missing';
    END IF;
    definition:=replace(definition,old_binding,new_binding);
    definition:=replace(definition,'IF snapshot->>''state'' IS DISTINCT FROM document.state OR',
        'IF snapshot->>''reason'' IS DISTINCT FROM document.reason OR snapshot->>''state'' IS DISTINCT FROM document.state OR');
    definition:=replace(definition,'ELSIF operation.document_revision=0 THEN',
        'ELSIF operation.namespace IN (''warehouse.transfer.create'',''warehouse.transfer.update'') THEN');
    definition:=replace(definition,
        'IF operation.namespace<>''warehouse.transfer.create'' OR snapshot->>''state''<>''DRAFT'' OR',
        $draft$IF (operation.document_revision=0 AND operation.namespace<>'warehouse.transfer.create') OR
                (operation.document_revision>0 AND operation.namespace<>'warehouse.transfer.update') OR
                EXISTS(SELECT FROM inventory_operation earlier WHERE earlier.tenant_id=scope AND earlier.document_id=target
                    AND earlier.document_revision<operation.document_revision AND earlier.namespace NOT IN
                        ('warehouse.transfer.create','warehouse.transfer.update')) OR
                snapshot->>'state'<>'DRAFT' OR$draft$);
    EXECUTE definition;
END $$;
