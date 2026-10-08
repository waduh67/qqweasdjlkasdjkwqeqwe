CREATE OR REPLACE FUNCTION warehouse_reference_count_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; BEGIN
    IF TG_TABLE_NAME='inventory_reference_count_movement' THEN
        target:=NEW.count_id;
    ELSE
        target:=NEW.id;
    END IF;
    PERFORM warehouse_assert_reference_count(NEW.tenant_id,target);
    RETURN NULL;
END $$;
