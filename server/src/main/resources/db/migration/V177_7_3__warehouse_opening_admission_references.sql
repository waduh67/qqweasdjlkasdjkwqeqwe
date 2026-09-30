DO $$ DECLARE definition text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_opening_approval(uuid,uuid,bigint)'::regprocedure);
    IF strpos(definition,'=document_id')=0 THEN RAISE EXCEPTION 'opening origin document reference missing'; END IF;
    EXECUTE replace(definition,'=document_id','=warehouse_assert_opening_approval.document_id');

    definition:=pg_get_functiondef('warehouse_admit_migration_opening(uuid,uuid,uuid)'::regprocedure);
    IF strpos(definition,'DECLARE scope uuid')=0 OR strpos(definition,'warehouse_admit_migration_opening.lot_id')=0 THEN
        RAISE EXCEPTION 'opening admission variable reference missing';
    END IF;
    -- Parameters belong to the implicit function block, but locally declared
    -- variables need their own named block when they share a column name.
    definition:=replace(definition,'DECLARE scope uuid',E'<<opening_admission>>\nDECLARE scope uuid');
    EXECUTE replace(definition,'warehouse_admit_migration_opening.lot_id','opening_admission.lot_id');
END $$;
