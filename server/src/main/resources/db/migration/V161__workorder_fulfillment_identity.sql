ALTER TABLE work_order ADD COLUMN IF NOT EXISTS order_id uuid;
CREATE INDEX IF NOT EXISTS work_order_tenant_order_idx ON work_order (tenant_id, order_id) WHERE order_id IS NOT NULL;

ALTER TABLE fulfillment_checkpoint
    ADD COLUMN IF NOT EXISTS order_id uuid,
    ADD COLUMN IF NOT EXISTS approval_actor_id uuid;
