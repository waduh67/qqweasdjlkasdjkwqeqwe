CREATE OR REPLACE FUNCTION payroll_configuration_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'payroll configuration history is append-only'; END $$;
DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['payroll_compensation','payroll_component','payroll_deduction_rule'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION payroll_configuration_append_only()', table_name || '_append_only', table_name);
  END LOOP;
END $$;
