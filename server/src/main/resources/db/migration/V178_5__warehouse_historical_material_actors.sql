-- QA verifies historical physical transactions; the current team completes the job.
-- Existing owner assertions bind each original actor to the immutable accepted
-- receipt, command, usage or deployment result. New use/install authorization,
-- current QA authority, source revisions, actual installation and proof remain fenced.
-- Reassignment must not require another consumption or deployment of the same goods.

DO $migration$
DECLARE definition text; previous text := $previous$(frozen.usage_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_material_usage use_header JOIN work_order_assignee assigned
                ON assigned.tenant_id=use_header.tenant_id AND assigned.technician_id=use_header.actor_id AND assigned.work_order_id=job.id
                WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id))$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_fulfillment_snapshot(uuid,uuid,boolean)'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique historical actor guard 1 not found';
    END IF;
    definition:=replace(definition,previous,$replacement$(frozen.usage_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_material_usage use_header
                WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id))$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$    IF EXISTS(SELECT FROM jsonb_array_elements(actual) deployment
        WHERE NOT (coalesce(NEW.snapshot::jsonb#>'{workOrder,material,activeAssigneeIds}','[]'::jsonb) ? (deployment->>'actorId'))) THEN
        RAISE EXCEPTION 'fulfillment deployment actor is not an assigned technician' USING ERRCODE='23514';
    END IF;
$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_fulfillment_deployments_guard()'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique historical actor guard 2 not found';
    END IF;
    definition:=replace(definition,previous,$replacement$    -- Exact posted witnesses above retain the original actor even after reassignment.
$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$
            OR EXISTS(SELECT FROM jsonb_array_elements(witnessed) deployment WHERE NOT EXISTS(
                SELECT FROM work_order_assignee WHERE tenant_id=scope AND work_order_id=frozen.work_order_id
                    AND technician_id=(deployment->>'actorId')::uuid))$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_only_settlement(uuid,uuid,boolean)'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique historical actor guard 3 not found';
    END IF;
    definition:=replace(definition,previous,$replacement$$replacement$);
    EXECUTE definition;
END $migration$;
