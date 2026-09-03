package com.duluin.ftth.notification.application.service

import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppProvider

/** Aturan tunggal untuk pemetaan trigger ke template pada seluruh jalur pengiriman. */
internal object TemplateAssignmentEligibility {
    fun blockedReason(provider: WhatsAppProvider, template: NotificationMessageTemplate): String? = when {
        template.status != TemplateStatus.APPROVED -> "Status template belum disetujui."
        template.bodyParamCount != 1 -> "Template harus memiliki tepat satu parameter BODY."
        provider == WhatsAppProvider.QONTAK && template.remoteId.isNullOrBlank() ->
            "Template Qontak belum memiliki ID template dari penyedia."
        else -> null
    }
}
