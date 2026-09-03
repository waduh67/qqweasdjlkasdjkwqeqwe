CREATE OR REPLACE FUNCTION provisioning_plan_attribute_valid(attribute_key text, attribute_value text) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    identifier_pattern constant text := '^[A-Za-z0-9._:/-]{1,160}$';
    list_pattern constant text := '^$|^[A-Za-z0-9._:/-]{1,160}(,[A-Za-z0-9._:/-]{1,160})*$';
BEGIN
    IF char_length(attribute_value) > 500
        OR attribute_value ~ '[[:cntrl:]]'
        OR lower(attribute_value) ~ '(password|secret|credential|token|privatekey|rawcli|command|script|-----begin|/interface |configure terminal)'
    THEN
        RETURN false;
    END IF;

    RETURN CASE attribute_key
        WHEN 'intentId' THEN attribute_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        WHEN 'vlanId' THEN CASE
            WHEN attribute_value ~ '^[0-9]{1,4}$' THEN attribute_value::integer BETWEEN 2 AND 4094
            ELSE false
        END
        WHEN 'expectedPreconditionHash' THEN attribute_value ~ '^[a-f0-9]{64}$'
        WHEN 'planPreconditionHash' THEN attribute_value ~ '^[a-f0-9]{64}$'
        WHEN 'interface' THEN attribute_value ~ identifier_pattern
        WHEN 'safety.vendor' THEN attribute_value ~ identifier_pattern
        WHEN 'safety.model' THEN attribute_value ~ identifier_pattern
        WHEN 'safety.firmware' THEN attribute_value ~ identifier_pattern
        WHEN 'safety.transport' THEN attribute_value ~ identifier_pattern
        WHEN 'safety.managementComplete' THEN attribute_value = 'true'
        WHEN 'safety.managementSourceId' THEN attribute_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        WHEN 'safety.managementSourceType' THEN attribute_value IN ('TOPOLOGY_OBSERVATION', 'DEVICE_OBSERVATION')
        WHEN 'safety.interfaceRoles' THEN attribute_value ~ list_pattern
        WHEN 'safety.ipAddresses' THEN attribute_value ~ list_pattern
        WHEN 'safety.vrfs' THEN attribute_value ~ list_pattern
        WHEN 'safety.collectorPaths' THEN attribute_value ~ list_pattern
        WHEN 'safety.requiredOobRoutes' THEN attribute_value ~ list_pattern
        WHEN 'safety.changedOobRoutes' THEN attribute_value ~ list_pattern
        WHEN 'safety.availableOobRoutes' THEN attribute_value ~ list_pattern
        ELSE false
    END;
END;
$$;
