package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityScope
import java.util.UUID

/** Workorder owns area membership and holds it stable for the caller's fenced report transaction. */
interface InventoryWorkOrderReadPort {
    fun visibleWorkOrderIds(scope: AuthorityScope): Set<UUID>
}
