ALTER TABLE inventory_deployment_authorization ADD CONSTRAINT warehouse_authorization_actor_tenant_fk
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id) NOT VALID;
CREATE INDEX warehouse_authorization_asset_idx ON inventory_deployment_authorization(tenant_id,asset_id) WHERE NOT consumed;
CREATE INDEX warehouse_authorization_issue_idx ON inventory_deployment_authorization(tenant_id,issue_line_id) WHERE NOT consumed;
CREATE INDEX warehouse_authorization_previous_idx ON inventory_deployment_authorization(tenant_id,previous_assignment_id) WHERE NOT consumed;

CREATE FUNCTION warehouse_assert_deployment_authorization(scope uuid, authorization_id uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization; physical inventory_serialized_asset;
    previous inventory_asset_assignment; snapshot jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=authorization_id;
    IF NOT FOUND OR permit.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'authorization is missing or not VERIFIED' USING ERRCODE='23514';
    END IF;
    SELECT * INTO physical FROM inventory_serialized_asset WHERE tenant_id=scope AND id=permit.asset_id;
    IF NOT FOUND OR physical.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'authorization requires VERIFIED physical asset' USING ERRCODE='23514';
    END IF;
    IF physical.status IN ('DISPOSED','LOST') THEN
        RAISE EXCEPTION 'authorization cannot use retired physical asset' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_assert_verified_segment(scope,physical.id,true);
    IF NOT EXISTS (SELECT FROM app_user WHERE tenant_id=scope AND id=permit.actor_id)
        OR NOT EXISTS (SELECT FROM customer WHERE tenant_id=scope AND id=permit.customer_id)
        OR NOT EXISTS (SELECT FROM work_order WHERE tenant_id=scope AND id=permit.work_order_id) THEN
        RAISE EXCEPTION 'authorization owner references require matching tenant' USING ERRCODE='23514';
    END IF;
    CASE permit.purpose
        WHEN 'INSTALL' THEN
            IF permit.previous_assignment_id IS NOT NULL OR permit.expected_assignment_revision IS NOT NULL THEN
                RAISE EXCEPTION 'INSTALL cannot claim a previous assignment' USING ERRCODE='23514';
            END IF;
        WHEN 'REPLACE', 'REMOVE', 'RETURN_CUSTOMER_RMA' THEN
            SELECT * INTO previous FROM inventory_asset_assignment
                WHERE tenant_id=scope AND id=permit.previous_assignment_id AND warehouse_admission='VERIFIED';
            SELECT history.snapshot INTO snapshot FROM inventory_asset_assignment_history history
                WHERE history.tenant_id=scope AND history.assignment_id=permit.previous_assignment_id
                    AND history.revision=permit.expected_assignment_revision;
            IF previous.id IS NULL OR snapshot IS NULL OR previous.customer_id<>permit.customer_id
                OR (snapshot->>'customer_id')::uuid IS DISTINCT FROM permit.customer_id
                OR (snapshot->>'asset_id')::uuid IS DISTINCT FROM previous.asset_id
                OR snapshot->>'warehouse_admission' IS DISTINCT FROM 'VERIFIED' THEN
                RAISE EXCEPTION 'authorization requires verified prior customer assignment snapshot' USING ERRCODE='23514';
            END IF;
            CASE permit.purpose
                WHEN 'REPLACE' THEN
                    IF previous.asset_id=permit.asset_id THEN
                        RAISE EXCEPTION 'REPLACE requires a different issued physical asset' USING ERRCODE='23514';
                    END IF;
                WHEN 'REMOVE', 'RETURN_CUSTOMER_RMA' THEN
                    IF previous.asset_id<>permit.asset_id OR previous.ownership_mode<>permit.ownership_mode THEN
                        RAISE EXCEPTION 'recovery authorization requires the same physical asset and title mode' USING ERRCODE='23514';
                    END IF;
                ELSE RAISE EXCEPTION 'unsupported authorization purpose' USING ERRCODE='23514';
            END CASE;
            IF NOT permit.consumed AND permit.purpose IN ('REPLACE','REMOVE')
                AND (previous.ended_at IS NOT NULL OR previous.revision<>permit.expected_assignment_revision) THEN
                RAISE EXCEPTION 'authorization prior assignment is no longer current' USING ERRCODE='23514';
            END IF;
            IF permit.purpose='RETURN_CUSTOMER_RMA' AND
                (physical.legal_owner<>'CUSTOMER' OR permit.ownership_mode<>'SALE') THEN
                RAISE EXCEPTION 'RMA return requires verified customer-owned asset' USING ERRCODE='23514';
            END IF;
        ELSE RAISE EXCEPTION 'unsupported authorization purpose' USING ERRCODE='23514';
    END CASE;
    CASE permit.purpose
        WHEN 'INSTALL', 'REPLACE', 'RETURN_CUSTOMER_RMA' THEN
            IF permit.issue_line_id IS NULL OR permit.expected_issue_revision IS NULL OR NOT EXISTS (
                SELECT FROM inventory_issue_line line JOIN inventory_document document
                    ON document.tenant_id=line.tenant_id AND document.id=line.issue_id
                WHERE line.tenant_id=scope AND line.id=permit.issue_line_id AND line.stock_identity_id=permit.asset_id
                    AND document.work_order_id=permit.work_order_id AND document.kind='ISSUE') THEN
                RAISE EXCEPTION 'authorization issue identity must match physical asset and work order' USING ERRCODE='23514';
            END IF;
            IF physical.condition<>'SERVICEABLE' OR NOT EXISTS (
                SELECT FROM inventory_material_receipt_line line JOIN inventory_material_receipt receipt
                    ON receipt.tenant_id=line.tenant_id AND receipt.id=line.receipt_id
                WHERE line.tenant_id=scope AND line.issue_line_id=permit.issue_line_id
                    AND line.accepted_identity_id=permit.asset_id AND line.accepted_base=1 AND line.base_unit='EA'
                    AND receipt.issue_revision<=permit.expected_issue_revision
                    AND EXISTS (SELECT FROM inventory_material_receipt revision
                        WHERE revision.tenant_id=scope AND revision.issue_id=line.issue_id
                            AND revision.issue_revision=permit.expected_issue_revision)) THEN
                RAISE EXCEPTION 'authorization requires serviceable acknowledged issue revision' USING ERRCODE='23514';
            END IF;
        WHEN 'REMOVE' THEN
            IF permit.issue_line_id IS NOT NULL OR permit.expected_issue_revision IS NOT NULL THEN
                RAISE EXCEPTION 'REMOVE binds its previous assignment instead of an issue' USING ERRCODE='23514';
            END IF;
        ELSE RAISE EXCEPTION 'unsupported authorization purpose' USING ERRCODE='23514';
    END CASE;
    IF NOT EXISTS (SELECT FROM inventory_deployment_authorization_history history
        WHERE history.tenant_id=scope AND history.authorization_id=permit.id AND history.revision=permit.revision
            AND history.snapshot=to_jsonb(permit)) THEN
        RAISE EXCEPTION 'authorization requires its exact immutable history revision' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_read_deployment_authorization(scope uuid, authorization_id uuid)
RETURNS inventory_deployment_authorization LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM warehouse_assert_deployment_authorization(scope,authorization_id);
    SELECT * INTO STRICT permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=authorization_id;
    RETURN permit;
END $$;
