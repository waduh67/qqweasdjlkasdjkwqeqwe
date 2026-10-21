ALTER TABLE fieldservice_visit ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE fieldservice_visit ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'FULFILLMENT'
    CHECK (purpose IN ('FULFILLMENT','WORK_ORDER'));
ALTER TABLE fieldservice_visit ADD COLUMN assignment_generation bigint;
ALTER TABLE fieldservice_visit ADD CONSTRAINT ordinary_visit_generation
    CHECK (purpose <> 'WORK_ORDER' OR assignment_generation >= 0 AND assignment_generation IS NOT NULL);
CREATE UNIQUE INDEX ordinary_visit_assignment ON fieldservice_visit(tenant_id,work_order_id,assignment_generation)
    WHERE purpose='WORK_ORDER';

CREATE FUNCTION fieldservice_sync_work_order_visit() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE generation bigint; open_assignment boolean; planned_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    generation := (NEW.snapshot->>'assignmentGeneration')::bigint;
    open_assignment := NEW.snapshot->>'state' IN ('PENDING','BLOCKED');
    UPDATE fieldservice_visit SET assignment_active=false,updated_at=clock_timestamp()
        WHERE tenant_id=NEW.tenant_id AND work_order_id=NEW.id AND purpose='WORK_ORDER' AND assignment_active
        AND (NOT open_assignment OR assignment_generation<>generation OR technician_id<>NEW.technician_id);
    IF open_assignment THEN
        INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active,purpose,assignment_generation)
            SELECT gen_random_uuid(),NEW.tenant_id,job.order_id,NEW.id,NEW.technician_id,'PLANNED',0,true,'WORK_ORDER',generation
            FROM work_order job WHERE job.tenant_id=NEW.tenant_id AND job.id=NEW.id
            ON CONFLICT (tenant_id,work_order_id,assignment_generation) WHERE purpose='WORK_ORDER' DO NOTHING
            RETURNING id INTO planned_id;
        IF planned_id IS NOT NULL THEN
            INSERT INTO fieldservice_work_session(id,tenant_id,visit_id,work_order_id,technician_id)
                VALUES(gen_random_uuid(),NEW.tenant_id,planned_id,NEW.id,NEW.technician_id);
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fieldservice_sync_work_order_visit AFTER INSERT OR UPDATE ON work_order_reference
    FOR EACH ROW EXECUTE FUNCTION fieldservice_sync_work_order_visit();

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition := pg_get_functiondef('warehouse_assert_fulfillment_owner_sources(uuid,uuid)'::regprocedure);
    anchor := 'IF NOT FOUND OR visited.work_order_id<>job.id';
    IF position(anchor in definition)=0 THEN RAISE EXCEPTION 'Missing fulfillment visit purpose anchor'; END IF;
    definition := replace(definition,anchor,'IF NOT FOUND OR visited.purpose<>''FULFILLMENT'' OR visited.work_order_id<>job.id');
    anchor := 'EXISTS(SELECT FROM fieldservice_visit WHERE tenant_id=target_tenant AND work_order_id=job.id)';
    IF position(anchor in definition)=0 THEN RAISE EXCEPTION 'Missing fulfillment visit list anchor'; END IF;
    EXECUTE replace(definition,anchor,'EXISTS(SELECT FROM fieldservice_visit WHERE tenant_id=target_tenant AND work_order_id=job.id AND purpose=''FULFILLMENT'')');
END $$;

DO $$ DECLARE target uuid; prior_scope text := current_setting('app.tenant_id',true);
BEGIN
    FOR target IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id',target::text,true);
        INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active,purpose,assignment_generation)
            SELECT gen_random_uuid(),target,job.order_id,ref.id,ref.technician_id,'PLANNED',0,true,'WORK_ORDER',
                (ref.snapshot->>'assignmentGeneration')::bigint
            FROM work_order_reference ref JOIN work_order job ON job.tenant_id=ref.tenant_id AND job.id=ref.id
            WHERE ref.tenant_id=target AND ref.snapshot->>'state' IN ('PENDING','BLOCKED')
            ON CONFLICT (tenant_id,work_order_id,assignment_generation) WHERE purpose='WORK_ORDER' DO NOTHING;
        INSERT INTO fieldservice_work_session(id,tenant_id,visit_id,work_order_id,technician_id)
            SELECT gen_random_uuid(),target,id,work_order_id,technician_id FROM fieldservice_visit
            WHERE tenant_id=target AND purpose='WORK_ORDER'
            ON CONFLICT (tenant_id,visit_id) DO NOTHING;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(prior_scope,''),true);
END $$;
