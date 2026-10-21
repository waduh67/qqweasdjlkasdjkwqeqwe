package com.karuhun.ftth.fieldservice.application.service

import java.time.LocalDate

data class B2BOverview(val month: LocalDate, val target: Int, val counted: Int, val remaining: Int,
    val missedWeeks: Int, val clients: List<B2BMonth>)
