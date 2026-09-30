CREATE FUNCTION warehouse_fulfillment_checkpoint_seal() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF EXISTS (SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=OLD.tenant_id AND namespace=OLD.namespace AND operation_key=OLD.operation_key) THEN
        IF TG_OP='DELETE' THEN RAISE EXCEPTION 'frozen fulfillment checkpoint is permanent' USING ERRCODE='23514'; END IF;
        IF (to_jsonb(NEW)-ARRAY['state','last_effect','attempts','outcome','checkpoint_updated_at','updated_at']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['state','last_effect','attempts','outcome','checkpoint_updated_at','updated_at']) OR
            (OLD.state='APPLIED' AND (NEW.state,NEW.last_effect,NEW.outcome) IS DISTINCT FROM (OLD.state,OLD.last_effect,OLD.outcome)) THEN
            RAISE EXCEPTION 'frozen fulfillment identity and terminal outcome are immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER warehouse_fulfillment_checkpoint_sealed BEFORE UPDATE OR DELETE ON fulfillment_checkpoint
    FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_checkpoint_seal();
