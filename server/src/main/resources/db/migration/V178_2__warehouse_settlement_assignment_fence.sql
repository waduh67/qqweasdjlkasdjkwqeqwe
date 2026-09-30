-- Keep a reviewed live installation stable through the local QA commit.
-- Use assignment ID order consistently when one job installed several devices.
DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_material_deployment_sources(uuid,uuid,uuid[])'::regprocedure);
    IF strpos(definition,'ORDER BY permit.id')=0 THEN
        RAISE EXCEPTION 'expected deployment witness ordering missing';
    END IF;
    EXECUTE replace(definition,'ORDER BY permit.id','ORDER BY assignment.id FOR SHARE OF assignment');
END $$;
