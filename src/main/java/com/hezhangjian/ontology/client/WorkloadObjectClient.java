package com.hezhangjian.ontology.client;

import com.hezhangjian.ontology.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import org.springframework.stereotype.Component;

@Component
public final class WorkloadObjectClient {
    private final String bucket;
    private final MinioClient minio;

    public WorkloadObjectClient(MinioProperties properties) {
        bucket = properties.workloadBucket();
        minio = MinioClient.builder()
                .endpoint(properties.url().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    public void put(String objectKey, byte[] payload) {
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(payload), payload.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (IOException | GeneralSecurityException | io.minio.errors.MinioException failure) {
            throw new IllegalStateException("Flink workload object could not be stored", failure);
        }
    }

    public void remove(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (IOException | GeneralSecurityException | io.minio.errors.MinioException failure) {
            throw new IllegalStateException("Flink workload object could not be removed", failure);
        }
    }

    private void ensureBucket()
            throws IOException, GeneralSecurityException, io.minio.errors.MinioException {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
