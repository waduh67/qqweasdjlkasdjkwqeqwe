DO $$
DECLARE signature text; definition text; anchor text;
BEGIN
    anchor:='(actor.platform_admin OR';
    FOREACH signature IN ARRAY ARRAY[
        'warehouse_assert_reference_wo_v200(uuid,uuid,text)',
        'warehouse_assert_reference_wo(uuid,uuid,text)'
    ] LOOP
        definition:=pg_get_functiondef(signature::regprocedure);
        IF (length(definition)-length(replace(definition,anchor,'')))/length(anchor)<>1 THEN
            RAISE EXCEPTION 'reference work order area scope anchor changed: %',signature;
        END IF;
        EXECUTE replace(definition,anchor,$body$(actor.platform_admin OR
            (actor.status='ACTIVE' AND EXISTS(SELECT FROM user_role assignment
                JOIN role ON role.id=assignment.role_id AND role.tenant_id=scope
                WHERE assignment.user_id=actor.id AND role.default_key='ADMIN') AND
                NOT EXISTS(SELECT FROM user_area assignment
                    JOIN area ON area.id=assignment.area_id AND area.tenant_id=scope
                    WHERE assignment.user_id=actor.id)) OR$body$);
    END LOOP;
END $$;
