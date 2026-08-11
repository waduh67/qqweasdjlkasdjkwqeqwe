package com.duluin.ftth.network.adapter.inbound.web

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.application.port.inbound.SurveyCapacityUseCase
import com.duluin.ftth.network.application.port.inbound.SurveyCapacityView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Cek kapasitas untuk survey: satu titik masuk, satu jawaban keluar.
 *
 * GET dengan koordinat di query string, bukan POST — supaya tautannya bisa
 * disalin ke chat tim ("cek yang ini deh") dan dibuka apa adanya oleh orang
 * berikutnya. Izinnya `network.odp.view`: yang ditampilkan sisa tempat di kotak,
 * dan orang yang boleh melihat daftar ODP sudah boleh tahu isinya berapa.
 */
@RestController
@RequestMapping("/api/network/survey")
@Tag(name = "Network — Kapasitas survey")
@SecurityRequirement(name = "bearer-jwt")
class SurveyCapacityController(
    private val survey: SurveyCapacityUseCase,
) {
    @GetMapping("/capacity")
    @PreAuthorize("@authz.can('network.odp.view')")
    fun capacity(
        @RequestParam longitude: Double,
        @RequestParam latitude: Double,
        @RequestParam(required = false, defaultValue = "300") radiusMeters: Double,
        @RequestParam(required = false, defaultValue = "5") limit: Int,
    ): SurveyCapacityView = survey.nearby(Coordinate(longitude, latitude), radiusMeters, limit)
}
