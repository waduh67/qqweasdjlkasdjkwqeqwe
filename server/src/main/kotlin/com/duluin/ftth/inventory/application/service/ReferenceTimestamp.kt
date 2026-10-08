package com.duluin.ftth.inventory.application.service

import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun referenceTimestamp(value: Instant = Instant.now()): Instant = value.truncatedTo(ChronoUnit.MICROS)
