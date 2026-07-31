package com.hezhangjian.ontology.client;

import com.hezhangjian.ontology.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public final class LocalCsvStorageClient {
    private static final Logger log = LoggerFactory.getLogger(LocalCsvStorageClient.class);

    private final String bucket;
    private final MinioClient minio;

    public LocalCsvStorageClient(MinioProperties properties) {
        bucket = properties.importBucket();
        minio = MinioClient.builder()
                .endpoint(properties.url().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    public void upload(String objectName, MultipartFile file) {
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType("text/csv")
                    .build());
        } catch (IOException | GeneralSecurityException | io.minio.errors.MinioException failure) {
            throw new IllegalStateException("Local CSV object could not be uploaded", failure);
        }
    }

    public void deleteAll(List<String> objectNames) {
        objectNames.forEach(objectName -> {
            try {
                minio.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .build());
            } catch (IOException | GeneralSecurityException
                    | io.minio.errors.MinioException cleanupFailure) {
                log.warn("Failed to remove uploaded CSV object during cleanup", cleanupFailure);
            }
        });
    }

    private void ensureBucket()
            throws IOException, GeneralSecurityException, io.minio.errors.MinioException {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
