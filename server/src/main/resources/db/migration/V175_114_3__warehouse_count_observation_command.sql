CREATE FUNCTION warehouse_count_observation_command_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.document_id IS NULL THEN RETURN NEW; END IF;
    IF NOT EXISTS (
        SELECT FROM inventory_operation operation JOIN inventory_command_identity identity
            ON identity.tenant_id=operation.tenant_id AND identity.id=operation.id
        WHERE operation.tenant_id=NEW.tenant_id AND operation.namespace='warehouse.count.observe'
            AND operation.operation_key=NEW.operation_key AND operation.actor_id=NEW.counter_id
            AND operation.resource_id=NEW.document_id AND operation.document_id=NEW.document_id
            AND operation.business_action='COUNT_OBSERVE' AND operation.original_status=200
            AND identity.canonical_payload::jsonb->>'id'=NEW.document_id::text
            AND identity.canonical_payload::jsonb#>>'{input,balanceId}'=NEW.balance_id::text
            AND (identity.canonical_payload::jsonb#>>'{input,expectedRevision}')::bigint+1=operation.document_revision
            AND (identity.canonical_payload::jsonb#>>'{input,quantityBase}')::numeric=NEW.observed_quantity_base
            AND identity.canonical_payload::jsonb#>>'{input,reason}'=NEW.reason
            AND identity.canonical_payload::jsonb#>>'{input,documentReference}'=NEW.evidence_reference
    ) THEN RAISE EXCEPTION 'count observation command is missing or does not match its evidence' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_count_observation_command AFTER INSERT ON inventory_cycle_count
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_observation_command_guard();
