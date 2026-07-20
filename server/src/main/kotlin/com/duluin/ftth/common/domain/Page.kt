package com.duluin.ftth.common.domain

/**
 * Permintaan paginasi netral-framework untuk lapisan application/domain,
 * supaya port tidak bergantung pada `org.springframework.data.domain.Pageable`.
 */
data class PageRequest(
    val page: Int = 0,
    val size: Int = 20,
    val sort: String? = null,
    val descending: Boolean = false,
) {
    init {
        require(page >= 0) { "page tidak boleh negatif" }
        require(size in 1..200) { "size harus 1..200" }
    }
}

/** Hasil paginasi netral-framework. */
data class Page<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    val totalPages: Int = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()

    fun <R> map(transform: (T) -> R): Page<R> =
        Page(content.map(transform), page, size, totalElements)
}
