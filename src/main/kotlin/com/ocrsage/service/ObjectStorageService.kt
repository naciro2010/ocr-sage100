package com.ocrsage.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI

@Service
class ObjectStorageService(
    @Value("\${storage.s3.endpoint:}") private val endpoint: String,
    @Value("\${storage.s3.bucket:}") private val bucket: String,
    @Value("\${storage.s3.access-key:}") private val accessKey: String,
    @Value("\${storage.s3.secret-key:}") private val secretKey: String,
    @Value("\${storage.s3.region:auto}") private val region: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val s3: S3Client? by lazy {
        if (accessKey.isBlank() || endpoint.isBlank()) {
            log.warn("S3 storage not configured, falling back to local filesystem")
            null
        } else {
            S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                )
                .forcePathStyle(true)
                .build()
        }
    }

    fun isEnabled(): Boolean = s3 != null

    fun upload(key: String, data: ByteArray, contentType: String) {
        s3!!.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(data)
        )
    }

    fun download(key: String): InputStream {
        return s3!!.getObject(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()
        )
    }

    fun delete(key: String) {
        s3!!.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()
        )
    }
}
