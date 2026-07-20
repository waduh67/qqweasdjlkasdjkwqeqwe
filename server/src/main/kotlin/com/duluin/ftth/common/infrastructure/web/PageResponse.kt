package com.duluin.ftth.common.infrastructure.web

import com.duluin.ftth.common.domain.Page

/**
 * Bentuk paginasi yang diserialisasi ke klien. Dibuat dari [Page] domain agar
 * kontrak HTTP tidak membocorkan tipe Spring Data.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> from(page: Page<T>): PageResponse<T> =
            PageResponse(page.content, page.page, page.size, page.totalElements, page.totalPages)
    }
}
