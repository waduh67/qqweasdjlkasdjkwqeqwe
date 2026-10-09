CREATE FUNCTION warehouse_reference_helpdesk_intake_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE intake work_order_reference_intake;
BEGIN
    FOR intake IN SELECT * FROM work_order_reference_intake
        WHERE source='HELPDESK' AND tenant_id=OLD.tenant_id AND source_id=OLD.id
    LOOP
        PERFORM warehouse_assert_reference_intake(intake.tenant_id,intake.work_order_id);
    END LOOP;
    IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.id) IS DISTINCT FROM (OLD.tenant_id,OLD.id) THEN
        FOR intake IN SELECT * FROM work_order_reference_intake
            WHERE source='HELPDESK' AND tenant_id=NEW.tenant_id AND source_id=NEW.id
        LOOP
            PERFORM warehouse_assert_reference_intake(intake.tenant_id,intake.work_order_id);
        END LOOP;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reference_helpdesk_intake_binding AFTER UPDATE OR DELETE ON helpdesk_ticket
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_helpdesk_intake_binding();
