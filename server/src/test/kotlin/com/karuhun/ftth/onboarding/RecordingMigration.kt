package com.karuhun.ftth.onboarding

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.onboarding.application.service.*
import java.util.UUID

internal class RecordingMigration : CustomerMigrationRegistration {
    val commands = mutableListOf<CustomerMigrationCommand>()
    val existing = mutableSetOf<String>()
    override fun register(command: CustomerMigrationCommand): CustomerMigrationView {
        if (command.username in existing) throw ConflictException("Pelanggan sudah terdaftar")
        commands += command
        return CustomerMigrationView(UUID.randomUUID(), UUID.randomUUID(), command.source.name, command.username, command.authType, command.nasId, command.framedIp, command.password != null, null, command.firstInvoiceOn != null, command.firstInvoiceOn, command.billingDay)
    }
}
