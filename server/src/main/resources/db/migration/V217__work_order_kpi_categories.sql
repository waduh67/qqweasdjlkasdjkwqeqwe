DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY[
        'work_order_reference_type', 'work_order_reference', 'work_order_reference_command'
    ] LOOP
        EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', relation);
        EXECUTE format('ALTER TABLE %I DISABLE TRIGGER USER', relation);
    END LOOP;
END $$;

UPDATE work_order_reference_type SET snapshot=snapshot||jsonb_build_object('kpiCategory',
    CASE WHEN snapshot->>'workType'='PSB' THEN 'PSB' ELSE 'NONE' END)
    WHERE NOT snapshot ? 'kpiCategory';
UPDATE work_order_reference SET snapshot=jsonb_set(snapshot,'{type,kpiCategory}',to_jsonb(
    CASE WHEN snapshot->'type'->>'workType'='PSB' THEN 'PSB' ELSE 'NONE' END))
    WHERE NOT (snapshot->'type') ? 'kpiCategory';
UPDATE work_order_reference_command SET snapshot=CASE WHEN resource_kind='TYPE'
    THEN snapshot||jsonb_build_object('kpiCategory',CASE WHEN snapshot->>'workType'='PSB' THEN 'PSB' ELSE 'NONE' END)
    ELSE jsonb_set(snapshot,'{type,kpiCategory}',to_jsonb(CASE WHEN snapshot->'type'->>'workType'='PSB' THEN 'PSB' ELSE 'NONE' END)) END
    WHERE (resource_kind='TYPE' AND NOT snapshot ? 'kpiCategory') OR
        (resource_kind='WO' AND NOT (snapshot->'type') ? 'kpiCategory');

DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY[
        'work_order_reference_type', 'work_order_reference', 'work_order_reference_command'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE TRIGGER USER', relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', relation);
    END LOOP;
END $$;
ALTER TABLE work_order_reference_type ADD CONSTRAINT work_order_type_kpi_category
    CHECK(coalesce(snapshot->>'kpiCategory','') IN ('NONE','PSB','PROJECT'));
ALTER TABLE work_order_reference ADD CONSTRAINT work_order_frozen_kpi_category
    CHECK(coalesce(snapshot->'type'->>'kpiCategory','') IN ('NONE','PSB','PROJECT'));
ALTER TABLE work_order_reference_command ADD CONSTRAINT work_order_command_kpi_category
    CHECK(coalesce(CASE WHEN resource_kind='TYPE' THEN snapshot->>'kpiCategory'
        ELSE snapshot->'type'->>'kpiCategory' END,'') IN ('NONE','PSB','PROJECT'));

CREATE OR REPLACE FUNCTION warehouse_seed_work_order_types(scope uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE item jsonb; target uuid; body text; snapshot jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|reference-workorder-types',0));
    FOR item IN SELECT value FROM jsonb_array_elements('[
        {"name":"Pasang Baru","workType":"PSB","kpiCategory":"PSB","materialRequired":true,"photoSlots":["Bukti Pasang","Bukti Kedatangan"]},
        {"name":"Maintenance","workType":"REPAIR","kpiCategory":"NONE","materialRequired":false,"photoSlots":["Bukti"]}
    ]'::jsonb) LOOP
        IF EXISTS(SELECT FROM work_order_reference_type WHERE tenant_id=scope AND lower(name)=lower(item->>'name')) THEN CONTINUE; END IF;
        target:=gen_random_uuid();
        snapshot:=item||jsonb_build_object('id',target,'revision',0,'active',true,'deleted',false);
        body:=jsonb_build_object('input',item,'id',NULL)::text;
        INSERT INTO work_order_reference_type(id,tenant_id,name,revision,snapshot) VALUES(target,scope,item->>'name',0,snapshot);
        INSERT INTO work_order_reference_command(id,tenant_id,resource_id,resource_kind,action,operation_key,revision,actor_id,authority_epoch,
            cutover_epoch,canonical_payload,payload_hash,snapshot,notes,permission_code,original_status)
            VALUES(gen_random_uuid(),scope,target,'TYPE','SEED',target::text,0,NULL,0,0,body,
                encode(sha256(convert_to(body,'UTF8')),'hex'),snapshot,'Jenis bawaan','SYSTEM',201);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_reference_wo_seed_category() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.action='SEED' AND NEW.snapshot->>'kpiCategory' IS DISTINCT FROM
        (CASE WHEN NEW.snapshot->>'workType'='PSB' THEN 'PSB' ELSE 'NONE' END) THEN
        RAISE EXCEPTION 'default work order category differs' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_order_reference_seed_category BEFORE INSERT ON work_order_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_seed_category();
