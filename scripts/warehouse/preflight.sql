\set ON_ERROR_STOP on
-- Read-only operator probe. Supply the reviewed tenant, SKU, schema version and
-- operating state with psql -v; never derive tenant context from an HTTP parameter.
BEGIN READ ONLY;
SELECT set_config('app.tenant_id', :'tenant_id', true) AS tenant_context,
       set_config('warehouse.preflight.version', :'expected_version', true) AS expected_version,
       set_config('warehouse.preflight.cutover', :'expected_cutover', true) AS expected_cutover,
       set_config('warehouse.preflight.sku', :'sku_id', true) AS reviewed_sku,
       set_config('warehouse.preflight.unit', :'expected_unit', true) AS expected_unit;

DO $$
DECLARE
    target uuid := current_setting('app.tenant_id')::uuid;
    reviewed_sku uuid := current_setting('warehouse.preflight.sku')::uuid;
    expected_version text := current_setting('warehouse.preflight.version');
    expected_cutover text := current_setting('warehouse.preflight.cutover');
    expected_unit text := current_setting('warehouse.preflight.unit');
    actual_version text;
    actual_cutover text;
    actual_unit text;
    required_table text;
BEGIN
    IF current_setting('transaction_read_only') <> 'on' THEN
        RAISE EXCEPTION 'preflight requires a read-only transaction';
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
        RAISE EXCEPTION 'preflight requires a NOSUPERUSER NOBYPASSRLS application role';
    END IF;
    FOREACH required_table IN ARRAY ARRAY[
        'inventory_tenant_cutover','inventory_sku','inventory_segment',
        'inventory_serialized_asset','inventory_balance_projection',
        'inventory_document','inventory_movement','inventory_movement_leg',
        'inventory_operation','inventory_asset_assignment','inventory_deployment_authorization',
        'inventory_material_usage','inventory_return_case','customer','onu'
    ] LOOP
        IF NOT EXISTS (
            SELECT FROM pg_class relation JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace
            WHERE namespace.nspname=current_schema() AND relation.relname=required_table
              AND relation.relrowsecurity AND relation.relforcerowsecurity
              AND relation.relowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)
        ) THEN
            RAISE EXCEPTION 'missing FORCE RLS or application owns table: %', required_table;
        END IF;
    END LOOP;
    IF EXISTS (SELECT FROM flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'failed migration requires investigation';
    END IF;
    SELECT version INTO actual_version FROM flyway_schema_history
        WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;
    IF actual_version IS DISTINCT FROM expected_version THEN
        RAISE EXCEPTION 'migration mismatch: expected %, observed %', expected_version, actual_version;
    END IF;
    SELECT state INTO actual_cutover FROM inventory_tenant_cutover WHERE tenant_id=target;
    IF expected_cutover NOT IN ('LEGACY','VALIDATING','ENFORCED') OR actual_cutover IS DISTINCT FROM expected_cutover THEN
        RAISE EXCEPTION 'cutover mismatch: expected %, observed %', expected_cutover, actual_cutover;
    END IF;
    SELECT base_unit INTO actual_unit FROM inventory_sku WHERE tenant_id=target AND id=reviewed_sku;
    IF expected_unit NOT IN ('EA','MM') OR actual_unit IS DISTINCT FROM expected_unit THEN
        RAISE EXCEPTION 'SKU unit mismatch: expected %, observed %', expected_unit, actual_unit;
    END IF;
    IF EXISTS (
        SELECT FROM inventory_balance_projection balance
        LEFT JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
        WHERE balance.tenant_id=target AND balance.warehouse_admission='VERIFIED'
          AND (sku.id IS NULL OR balance.base_unit IS DISTINCT FROM sku.base_unit
               OR balance.quantity_base IS NULL OR balance.quantity_base<0
               OR (balance.serialized AND (balance.base_unit<>'EA' OR balance.quantity_base NOT IN (0,1))))
    ) THEN
        RAISE EXCEPTION 'verified stock has missing or inconsistent units/quantities';
    END IF;
    IF EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id<>target)
       OR EXISTS (SELECT FROM inventory_asset_assignment WHERE tenant_id<>target) THEN
        RAISE EXCEPTION 'application role can see another tenant';
    END IF;
END $$;

SELECT json_build_object(
    'database',current_database(),'schema',current_schema(),'applicationRole',current_user,
    'version',current_setting('warehouse.preflight.version'),
    'cutover',(SELECT state FROM inventory_tenant_cutover WHERE tenant_id=current_setting('app.tenant_id')::uuid),
    'customers',(SELECT count(*) FROM customer),
    'onus',(SELECT count(*) FROM onu),
    'positions',(SELECT count(*) FROM inventory_balance_projection),
    'movements',(SELECT count(*) FROM inventory_movement),
    'verifiedQuantityByUnit',(SELECT coalesce(json_object_agg(base_unit,quantity_base),'{}'::json) FROM (
        SELECT base_unit,sum(quantity_base)::text quantity_base FROM inventory_balance_projection
        WHERE warehouse_admission='VERIFIED' GROUP BY base_unit
    ) totals)
) AS warehouse_preflight;
ROLLBACK;
