package com.duluin.ftth.iam.application.service

import com.duluin.ftth.iam.application.port.inbound.AreaView
import com.duluin.ftth.iam.application.port.inbound.PermissionView
import com.duluin.ftth.iam.application.port.inbound.RoleView
import com.duluin.ftth.iam.application.port.inbound.UserView
import com.duluin.ftth.iam.domain.model.Area
import com.duluin.ftth.iam.domain.model.Permission
import com.duluin.ftth.iam.domain.model.Role
import com.duluin.ftth.iam.domain.model.User

/** Pemetaan agregat domain → read model (view) lapisan application. */

internal fun Permission.toView() = PermissionView(
    id = id,
    code = code.value,
    module = code.module,
    resource = code.resource,
    action = code.action,
    description = description,
    platformOnly = platformOnly,
)

internal fun Role.toView() = RoleView(
    id = id,
    name = name,
    description = description,
    systemRole = systemRole,
    permissionIds = permissionIds.toList(),
)

internal fun Area.toView() = AreaView(
    id = id,
    code = code,
    name = name,
    parentId = parentId,
)

internal fun User.toView() = UserView(
    id = id,
    email = email.value,
    name = name,
    status = status.name,
    platformAdmin = platformAdmin,
    roleIds = roleIds.toList(),
    areaIds = areaIds.toList(),
    createdAt = createdAt,
    twoFactorEnabled = twoFactorEnabled,
)
