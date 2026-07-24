package com.duluin.ftth.workorder.adapter.outbound.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * Merakit klien S3 sinkron yang menunjuk ke MinIO/S3. Dinonaktifkan di profil
 * `test` (di sana [ObjectStorage] dipenuhi implementasi in-memory) supaya uji
 * tidak menuntut MinIO hidup.
 */
@Configuration
@Profile("!test")
class S3StorageConfig {

    @Bean(destroyMethod = "close")
    fun s3Client(properties: StorageProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
                ),
            )
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess).build(),
            )
            .build()
}
