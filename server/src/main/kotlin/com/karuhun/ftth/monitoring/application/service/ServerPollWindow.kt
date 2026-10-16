package com.karuhun.ftth.monitoring.application.service

import java.time.Instant

data class ServerPollWindow(val startedAt: Instant, val completedAt: Instant)
