ALTER TABLE monitoring_discovery_receipt ADD COLUMN request_payload text;
CREATE FUNCTION monitoring_assert_discovery_receipt(scope uuid, target uuid, require_context boolean DEFAULT true)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE receipt monitoring_discovery_receipt; discovery discovered_onu;
    permit inventory_deployment_authorization; result inventory_deployment_result;
    command jsonb; identity jsonb; installation jsonb; expected_topology jsonb; expected_response jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO receipt FROM monitoring_discovery_receipt WHERE tenant_id=scope AND discovery_id=target;
    IF NOT FOUND THEN RAISE EXCEPTION 'discovery receipt missing' USING ERRCODE='23514'; END IF;
    SELECT * INTO discovery FROM discovered_onu WHERE tenant_id=scope AND id=target;
    SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=receipt.authorization_id;
    SELECT * INTO result FROM inventory_deployment_result WHERE tenant_id=scope AND authorization_id=receipt.authorization_id;
    IF permit.id IS NULL OR NOT permit.consumed OR permit.purpose<>'INSTALL' OR discovery.id IS NULL OR discovery.state<>'PROVISIONED'
        OR result.authorization_id IS NULL OR result.operation_id IS DISTINCT FROM permit.operation_id
        OR result.consume_key IS DISTINCT FROM receipt.operation_key
        OR NOT EXISTS(SELECT FROM customer_asset_installation installed JOIN onu episode ON episode.tenant_id=installed.tenant_id AND episode.id=installed.onu_id
            WHERE installed.tenant_id=scope AND installed.operation_id=result.operation_id AND installed.assignment_id=result.assignment_id
                AND installed.customer_id=permit.customer_id AND installed.asset_id=permit.asset_id
                AND warehouse_canonical_serial(episode.serial_number)=warehouse_canonical_serial(discovery.serial_number)) THEN
        RAISE EXCEPTION 'discovery receipt requires its completed deployment' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_assert_deployment_result(scope,permit.id);
    SELECT canonical_payload::jsonb INTO identity FROM inventory_command_identity WHERE tenant_id=scope AND id=result.operation_id;
    IF require_context OR receipt.request_payload IS NOT NULL THEN
        IF receipt.request_payload IS NULL OR identity#>>'{observation,observationId}' IS DISTINCT FROM target::text
            OR identity#>>'{observation,requestPayload}' IS DISTINCT FROM receipt.request_payload
            OR receipt.request_hash IS DISTINCT FROM encode(sha256(convert_to(receipt.request_payload,'UTF8')),'hex') THEN
            RAISE EXCEPTION 'discovery command hash and operation context must agree' USING ERRCODE='23514';
        END IF;
        command:=receipt.request_payload::jsonb;
        installation:=(identity->>'installationPayload')::jsonb;
        expected_topology:=CASE WHEN command->>'odpId' IS NULL THEN 'null'::jsonb ELSE
            jsonb_build_object('odpId',command->'odpId','portNumber',command->'portNumber','installRxPowerDbm',command->'installRxPowerDbm') END;
        IF command->>'authorizationId' IS DISTINCT FROM permit.id::text OR command->>'customerId' IS DISTINCT FROM permit.customer_id::text
            OR command->>'operationKey' IS DISTINCT FROM result.consume_key OR command->>'expectedRevision' IS DISTINCT FROM '0'
            OR identity->>'authorizationId' IS DISTINCT FROM permit.id::text OR identity->>'customerId' IS DISTINCT FROM permit.customer_id::text
            OR installation->>'authorizationId' IS DISTINCT FROM permit.id::text OR installation->>'expectedRevision' IS DISTINCT FROM '0'
            OR (command->>'odpId' IS NULL) IS DISTINCT FROM (command->>'portNumber' IS NULL)
            OR installation->'topology' IS DISTINCT FROM expected_topology
            OR (command-ARRAY['customerId','odpId','portNumber','installRxPowerDbm','authorizationId','expectedRevision','operationKey'])<>'{}'::jsonb THEN
            RAISE EXCEPTION 'discovery command must bind the authorized customer and operation' USING ERRCODE='23514';
        END IF;
    END IF;
    expected_response:=jsonb_build_object('id',discovery.id,'serialNumber',discovery.serial_number,'oltId',discovery.olt_id,
        'oltCode',discovery.olt_code,'ponPortLabel',discovery.pon_port_label,'lastStatus',discovery.last_status,
        'lastRxPowerDbm',discovery.last_rx_power_dbm,'seenCount',discovery.seen_count,'state',discovery.state,'suggestion',NULL);
    IF receipt.response-ARRAY['firstSeenAt','lastSeenAt'] IS DISTINCT FROM expected_response
        OR (receipt.response->>'firstSeenAt')::timestamptz IS DISTINCT FROM discovery.first_seen_at
        OR (receipt.response->>'lastSeenAt')::timestamptz IS DISTINCT FROM discovery.last_seen_at THEN
        RAISE EXCEPTION 'discovery response must preserve the resolved observation' USING ERRCODE='23514';
    END IF;
EXCEPTION WHEN invalid_text_representation OR invalid_datetime_format OR datetime_field_overflow OR invalid_time_zone_displacement_value THEN
    RAISE EXCEPTION 'invalid discovery receipt binding' USING ERRCODE='23514';
END $$;
CREATE FUNCTION monitoring_discovery_receipt_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM monitoring_assert_discovery_receipt(NEW.tenant_id,NEW.discovery_id,true);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER monitoring_discovery_receipt_final AFTER INSERT ON monitoring_discovery_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_discovery_receipt_final_guard();
