DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE ALL ON work_order_reference_photo FROM warehouse_app;
        GRANT SELECT,INSERT ON work_order_reference_photo TO warehouse_app;
    END IF;
END $$;
