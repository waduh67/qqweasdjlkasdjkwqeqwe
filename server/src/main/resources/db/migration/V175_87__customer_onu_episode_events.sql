CREATE TABLE customer_onu_episode_event (
    tenant_id uuid NOT NULL, onu_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
    source_revision bigint, is_baseline boolean NOT NULL DEFAULT false,
    kind text NOT NULL CHECK(kind IN ('OPENED','TOPOLOGY','RETIRED')),
    assignment_id uuid NOT NULL, asset_id uuid NOT NULL, customer_id uuid NOT NULL,
    topology_revision bigint NOT NULL CHECK(topology_revision>=0), retired_at timestamptz, removal_id uuid,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,onu_id,revision), UNIQUE(tenant_id,onu_id,source_revision),
    CHECK((is_baseline AND source_revision IS NULL AND ((kind='OPENED' AND revision=0) OR (kind='RETIRED' AND revision=1)))
        OR (NOT is_baseline AND source_revision>=0 AND revision=source_revision+1 AND kind IN ('TOPOLOGY','RETIRED'))),
    CHECK((kind='RETIRED')=(retired_at IS NOT NULL)), CHECK(removal_id IS NULL OR kind='RETIRED'),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id,topology_revision) REFERENCES onu_topology_history(tenant_id,onu_id,revision),
    FOREIGN KEY(tenant_id,onu_id,source_revision) REFERENCES customer_onu_episode_event(tenant_id,onu_id,revision),
    FOREIGN KEY(tenant_id,removal_id) REFERENCES customer_asset_retirement(tenant_id,removal_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX customer_onu_episode_baseline ON customer_onu_episode_event(tenant_id,onu_id) WHERE is_baseline;
CREATE INDEX customer_onu_episode_assignment ON customer_onu_episode_event(tenant_id,assignment_id);
ALTER TABLE customer_onu_episode_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_onu_episode_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_onu_episode_event USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);

INSERT INTO customer_onu_episode_event(tenant_id,onu_id,revision,is_baseline,kind,assignment_id,asset_id,customer_id,
    topology_revision,retired_at,removal_id)
SELECT episode.tenant_id,episode.id,CASE WHEN episode.retired_at IS NULL THEN 0 ELSE 1 END,true,
    CASE WHEN episode.retired_at IS NULL THEN 'OPENED' ELSE 'RETIRED' END,
    episode.assignment_id,episode.asset_id,episode.customer_id,episode.topology_revision,episode.retired_at,retirement.removal_id
FROM onu episode JOIN inventory_asset_assignment assignment ON assignment.tenant_id=episode.tenant_id AND assignment.id=episode.assignment_id
    AND assignment.asset_id=episode.asset_id AND assignment.customer_id=episode.customer_id
JOIN inventory_serialized_asset asset ON asset.tenant_id=episode.tenant_id AND asset.id=episode.asset_id
JOIN customer ON customer.tenant_id=episode.tenant_id AND customer.id=episode.customer_id
JOIN onu_topology_history topology ON topology.tenant_id=episode.tenant_id AND topology.onu_id=episode.id
    AND topology.revision=episode.topology_revision AND topology.assignment_id=episode.assignment_id
LEFT JOIN customer_asset_retirement retirement ON retirement.tenant_id=episode.tenant_id AND retirement.onu_id=episode.id
WHERE episode.warehouse_admission='VERIFIED';

CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON customer_onu_episode_event FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON customer_onu_episode_event TO warehouse_app;
    END IF;
END $$;
