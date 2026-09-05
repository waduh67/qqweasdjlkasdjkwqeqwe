INSERT INTO permission (id, code, module, resource, action, description, platform_only)
VALUES
    ('019942bf-0000-7000-8000-000000000001', 'provisioning.segment.view', 'provisioning', 'segment', 'view', 'Lihat profil segmen VLAN', false),
    ('019942bf-0000-7000-8000-000000000002', 'provisioning.segment.manage', 'provisioning', 'segment', 'manage', 'Kelola profil segmen VLAN', false),
    ('019942bf-0000-7000-8000-000000000003', 'provisioning.plan.view', 'provisioning', 'plan', 'view', 'Lihat rencana provisioning', false),
    ('019942bf-0000-7000-8000-000000000004', 'provisioning.execution.apply', 'provisioning', 'execution', 'apply', 'Terapkan rencana provisioning', false),
    ('019942bf-0000-7000-8000-000000000005', 'provisioning.execution.cancel', 'provisioning', 'execution', 'cancel', 'Batalkan eksekusi provisioning', false),
    ('019942bf-0000-7000-8000-000000000006', 'provisioning.drift.view', 'provisioning', 'drift', 'view', 'Lihat drift perangkat', false),
    ('019942bf-0000-7000-8000-000000000007', 'provisioning.drift.adopt', 'provisioning', 'drift', 'adopt', 'Adopsi drift perangkat', false),
    ('019942bf-0000-7000-8000-000000000008', 'provisioning.certification.manage', 'provisioning', 'certification', 'manage', 'Kelola sertifikasi adapter perangkat', true)
ON CONFLICT (code) DO UPDATE SET
    module = EXCLUDED.module,
    resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    platform_only = EXCLUDED.platform_only,
    active = true,
    updated_at = now();
