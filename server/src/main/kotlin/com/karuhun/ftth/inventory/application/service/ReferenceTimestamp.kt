package com.karuhun.ftth.inventory.application.service

import java.time.Instant

internal fun referenceTimestamp(value: Instant = Instant.now()): Instant =
    com.karuhun.ftth.common.infrastructure.time.referenceTimestamp(value)
