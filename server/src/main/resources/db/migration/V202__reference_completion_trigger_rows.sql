CREATE OR REPLACE FUNCTION warehouse_reference_completion_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; document uuid; BEGIN
    IF TG_TABLE_NAME='work_order_reference_command' THEN
        IF NEW.action<>'COMPLETE' THEN RETURN NULL; END IF;
        target:=NEW.resource_id;
    ELSE
        target:=NEW.work_order_id;
    END IF;
    PERFORM warehouse_assert_reference_completion(NEW.tenant_id,target);
    SELECT document_id INTO document FROM work_order_reference_completion WHERE tenant_id=NEW.tenant_id AND work_order_id=target;
    IF document IS NOT NULL THEN PERFORM warehouse_assert_reference_consume(NEW.tenant_id,document); END IF;
    RETURN NULL;
END $$;
