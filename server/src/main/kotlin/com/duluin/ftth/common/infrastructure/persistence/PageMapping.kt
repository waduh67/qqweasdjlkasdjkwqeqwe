package com.duluin.ftth.common.infrastructure.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.domain.Page as SpringPage

/**
 * Jembatan antara tipe paginasi domain (netral-framework) dan Spring Data.
 * Diletakkan di lapisan infrastructure agar port/domain tidak menyentuh Spring Data.
 */
fun PageRequest.toPageable(): Pageable {
    val sort = this.sort
        ?.let { Sort.by(if (descending) Sort.Direction.DESC else Sort.Direction.ASC, it) }
        ?: Sort.unsorted()
    return SpringPageRequest.of(page, size, sort)
}

fun <T : Any> SpringPage<T>.toDomainPage(): Page<T> =
    Page(content = content, page = number, size = size, totalElements = totalElements)
