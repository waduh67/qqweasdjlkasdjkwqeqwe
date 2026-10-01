package com.duluin.ftth.cpe.application.port.outbound

import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings

interface AcsSettingsRepository {
    fun find(): AcsConnectionSettings?
    fun save(settings: AcsConnectionSettings): AcsConnectionSettings
}

fun interface AcsSettingsResolver {
    fun current(): AcsConnectionSettings
}

fun interface AcsConnectionProbe {
    fun test(settings: AcsConnectionSettings): AcsProbe
}
