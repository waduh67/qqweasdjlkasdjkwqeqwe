package com.duluin.ftth.customer

import java.util.UUID

abstract class CustomerAssetAuthorizationTimezoneFixture : CustomerAssetAuthorizationFixture() {
    internal fun EpisodeCase.createTimed(id: UUID, zone: String = "America/New_York", instant: String = "2026-07-20T10:00:00-04:00"): String = stock.transaction {
        sql("SET LOCAL TIME ZONE '$zone'")
        sql(authorization(id).replace("expected_assignment_revision)", "expected_assignment_revision,created_at)")
            .replace(",NULL,NULL\n", ",NULL,NULL,'$instant'::timestamptz\n"))
        scalar("SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$id' AND revision=0")
    }
}
