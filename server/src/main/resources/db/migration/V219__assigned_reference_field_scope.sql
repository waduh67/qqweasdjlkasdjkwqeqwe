DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_wo_v200(uuid,uuid,text)'::regprocedure);
    anchor:='(actor.platform_admin OR EXISTS(SELECT FROM iam_tenant_owner';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference field scope anchor changed'; END IF;
    EXECUTE replace(definition,anchor,$body$(actor.platform_admin OR
        (command.action IN ('PROGRESS','PHOTO','COMPLETE') AND actor.status='ACTIVE' AND
            actor.id=(current->>'technicianId')::uuid AND actor.id=(prior->>'technicianId')::uuid AND
            current->'assignmentGeneration' IS NOT DISTINCT FROM prior->'assignmentGeneration') OR
        EXISTS(SELECT FROM iam_tenant_owner$body$);
END $$;
