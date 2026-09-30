ALTER TABLE fieldservice_visit ADD CONSTRAINT warehouse_visit_work_order_tenant_fk
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id) NOT VALID;
