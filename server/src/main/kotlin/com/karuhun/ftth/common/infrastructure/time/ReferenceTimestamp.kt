package com.karuhun.ftth.common.infrastructure.time

import java.time.Instant
import java.time.temporal.ChronoUnit

fun referenceTimestamp(value: Instant = Instant.now()): Instant = value.truncatedTo(ChronoUnit.MICROS)
