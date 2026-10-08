ALTER FUNCTION warehouse_assert_reference_post(uuid,uuid) RENAME TO warehouse_assert_reference_post_legs;

CREATE FUNCTION warehouse_assert_reference_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding inventory_reference_post; document inventory_document; operation inventory_operation;
    movement inventory_movement; canonical text; request jsonb; body jsonb; intake jsonb; item jsonb;
    frozen jsonb; line inventory_document_line; expected_count bigint;
BEGIN
    PERFORM warehouse_assert_reference_post_legs(scope,target);
    SELECT * INTO binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target;
    IF binding.id IS NULL THEN RETURN; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT * INTO STRICT movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=binding.id;
    SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id;
    IF canonical IS NULL OR operation.payload_hash IS DISTINCT FROM encode(sha256(convert_to(canonical,'UTF8')),'hex')
        OR operation.actor_id IS DISTINCT FROM document.actor_id OR movement.actor_id IS DISTINCT FROM operation.actor_id
        OR movement.payload_hash IS DISTINCT FROM operation.payload_hash
        OR movement.operation_key IS DISTINCT FROM operation.operation_key
        OR movement.cutover_epoch IS DISTINCT FROM operation.cutover_epoch
        OR movement.authority_epoch IS DISTINCT FROM operation.authority_epoch
        OR operation.authority_epoch IS DISTINCT FROM document.authority_epoch
        OR operation.resource_scope IS DISTINCT FROM 'reference:'||target
        OR operation.original_status IS DISTINCT FROM 201
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=target)<>1
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=binding.id
            AND document_id=target AND document_revision=1) THEN
        RAISE EXCEPTION 'reference posting requires exact canonical command and actor' USING ERRCODE='23514';
    END IF;
    request:=canonical::jsonb; body:=operation.original_body::jsonb;
    IF jsonb_typeof(request) IS DISTINCT FROM 'object' OR jsonb_typeof(request->'lines') IS DISTINCT FROM 'array'
        OR jsonb_array_length(request->'lines') NOT BETWEEN 1 AND 100
        OR request->>'warehouseId' IS DISTINCT FROM binding.destination_location_id::text
        OR body->>'id' IS DISTINCT FROM target::text OR body->>'operationId' IS DISTINCT FROM binding.id::text
        OR body->>'revision' IS DISTINCT FROM '1' OR body->>'kind' IS DISTINCT FROM binding.action
        OR body->>'state' IS DISTINCT FROM document.state
        OR body->>'warehouseId' IS DISTINCT FROM request->>'warehouseId'
        OR body->>'notes' IS DISTINCT FROM request->>'notes'
        OR body->>'sourceWarehouseId' IS DISTINCT FROM request->>'sourceWarehouseId'
        OR length(coalesce(request->>'notes',''))>1000
        OR EXISTS(SELECT FROM inventory_movement_leg leg LEFT JOIN inventory_document_line row
            ON row.tenant_id=leg.tenant_id AND row.id=leg.document_line_id
            WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND
                (row.id IS NULL OR row.document_id IS DISTINCT FROM target OR leg.sku_id IS DISTINCT FROM row.sku_id
                    OR leg.base_unit IS DISTINCT FROM row.base_unit OR leg.lot_id IS DISTINCT FROM row.lot_id)) THEN
        RAISE EXCEPTION 'reference request response or foreign line mismatch' USING ERRCODE='23514';
    END IF;
    IF binding.action='TRANSFER' THEN
        IF request IS DISTINCT FROM jsonb_build_object('sourceWarehouseId',binding.source_location_id,
            'warehouseId',binding.destination_location_id,'lines',request->'lines','notes',request->'notes')
            OR jsonb_array_length(request->'lines')<>(SELECT count(*) FROM inventory_document_line
                WHERE tenant_id=scope AND document_id=target) THEN
            RAISE EXCEPTION 'reference transfer request mismatch' USING ERRCODE='23514';
        END IF;
        FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
            item:=request->'lines'->(line.line_number-1);
            IF item IS DISTINCT FROM jsonb_build_object('stockIdentityId',line.stock_identity_id,'quantityBase',line.quantity_base::text)
                OR line.condition IS DISTINCT FROM 'SERVICEABLE' OR line.legal_owner IS DISTINCT FROM 'ISP'
                OR line.custodian_kind IS DISTINCT FROM 'WAREHOUSE' OR line.location_id IS DISTINCT FROM binding.source_location_id
                OR line.custodian_id IS DISTINCT FROM binding.source_location_id
                OR line.destination_location_id IS DISTINCT FROM binding.destination_location_id
                OR line.document_revision IS DISTINCT FROM 0
                OR NOT EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id
                    AND document_line_id=line.id AND direction='OUT' AND stock_identity_id=line.stock_identity_id) THEN
                RAISE EXCEPTION 'reference transfer line differs from requested stock' USING ERRCODE='23514';
            END IF;
        END LOOP;
    ELSE
        SELECT snapshot::jsonb INTO STRICT intake FROM inventory_receipt_intake WHERE tenant_id=scope AND id=target;
        IF request IS DISTINCT FROM jsonb_build_object('warehouseId',binding.destination_location_id,'lines',request->'lines',
            'notes',request->'notes','supplierId',request->'supplierId','reference',request->'reference')
            OR intake#>>'{source,id}' IS DISTINCT FROM binding.source_location_id::text
            OR intake#>>'{inspection,id}' IS DISTINCT FROM binding.destination_location_id::text
            OR intake#>>'{supplier,id}' IS DISTINCT FROM document.supplier_id::text
            OR intake->>'externalReference' IS DISTINCT FROM document.source_reference
            OR request->>'supplierId' IS NOT NULL AND request->>'supplierId' IS DISTINCT FROM document.supplier_id::text
            OR request->>'reference' IS NOT NULL AND request->>'reference' IS DISTINCT FROM document.source_reference THEN
            RAISE EXCEPTION 'reference receipt request or intake mismatch' USING ERRCODE='23514';
        END IF;
        SELECT sum(CASE WHEN jsonb_array_length(value->'serials')>0 THEN jsonb_array_length(value->'serials') ELSE 1 END)
            INTO expected_count FROM jsonb_array_elements(request->'lines');
        IF expected_count IS DISTINCT FROM (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)
            OR expected_count IS DISTINCT FROM jsonb_array_length(intake->'lines')::bigint THEN
            RAISE EXCEPTION 'reference receipt line count mismatch' USING ERRCODE='23514';
        END IF;
        FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
            SELECT value INTO frozen FROM jsonb_array_elements(intake->'lines') WHERE value->>'id'=line.id::text;
            item:=request->'lines'->((frozen->>'inputLineNumber')::int-1);
            IF frozen IS NULL OR item IS NULL OR item->>'skuId' IS DISTINCT FROM line.sku_id::text
                OR frozen#>>'{sku,id}' IS DISTINCT FROM line.sku_id::text
                OR frozen->>'quantityBase' IS DISTINCT FROM line.quantity_base::text
                OR frozen->'conversion' IS DISTINCT FROM item->'conversion'
                OR frozen->'cost' IS DISTINCT FROM (CASE WHEN item->'cost'='null'::jsonb THEN 'null'::jsonb
                    ELSE item->'cost'||jsonb_build_object('costBasisQuantityBase',item->>'quantityBase') END)
                OR line.location_id IS DISTINCT FROM binding.destination_location_id
                OR line.destination_location_id IS DISTINCT FROM binding.source_location_id
                OR line.custodian_id IS DISTINCT FROM binding.destination_location_id
                OR line.custodian_kind IS DISTINCT FROM 'WAREHOUSE' OR line.condition IS DISTINCT FROM 'SERVICEABLE'
                OR line.legal_owner IS DISTINCT FROM 'ISP' OR line.document_revision IS DISTINCT FROM 0 THEN
                RAISE EXCEPTION 'reference receipt line differs from requested SKU' USING ERRCODE='23514';
            END IF;
            IF line.tracking='SERIAL' THEN
                IF jsonb_array_length(item->'serials')::text IS DISTINCT FROM item->>'quantityBase'
                    OR NOT EXISTS(SELECT FROM jsonb_array_elements(item->'serials') value
                        JOIN inventory_segment segment ON segment.tenant_id=scope AND segment.id=line.stock_identity_id
                        JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
                        WHERE value=jsonb_build_object('serial',frozen->>'serial','mac',frozen->>'mac')
                            AND asset.serial_number IS NOT DISTINCT FROM frozen->>'serial'
                            AND asset.mac_address IS NOT DISTINCT FROM frozen->>'mac'
                            AND asset.origin_document_line_id=line.id) THEN
                    RAISE EXCEPTION 'reference receipt serial differs from requested identity' USING ERRCODE='23514';
                END IF;
            ELSE
                IF item->>'quantityBase' IS DISTINCT FROM line.quantity_base::text OR item->'serials' IS DISTINCT FROM '[]'::jsonb
                    OR NOT EXISTS(SELECT FROM inventory_lot WHERE tenant_id=scope AND id=line.lot_id
                        AND sku_id=line.sku_id AND origin_document_line_id=line.id
                        AND code=coalesce(item->>'lotCode','RCV-'||target||'-'||(frozen->>'inputLineNumber'))) THEN
                    RAISE EXCEPTION 'reference receipt lot differs from requested stock' USING ERRCODE='23514';
                END IF;
            END IF;
        END LOOP;
    END IF;
END $$;

CREATE FUNCTION warehouse_reference_identity_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    SELECT document_id INTO target FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_reference_post(NEW.tenant_id,target); END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_reference_identity_final AFTER INSERT ON inventory_command_identity
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_identity_final_guard();
