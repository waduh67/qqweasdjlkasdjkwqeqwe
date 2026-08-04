package com.duluin.ftth.common.infrastructure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Konfigurasi object storage (S3/MinIO) tempat berkas biner disimpan (bukti pengerjaan
 * work-order, gambar QRIS billing). MinIO S3-compatible, jadi klien AWS S3 cukup diarahkan
 * lewat [endpoint] + [pathStyleAccess] (MinIO tak mendukung virtual-host style secara default).
 */
@ConfigurationProperties(prefix = "ftth.storage")
data class StorageProperties(
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val bucket: String = "ftth-evidence",
    val accessKey: String = "ftth",
    val secretKey: String = "ftthminio",
    val pathStyleAccess: Boolean = true,
)
