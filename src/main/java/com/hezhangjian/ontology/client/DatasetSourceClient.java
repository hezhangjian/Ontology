package com.hezhangjian.ontology.client;

import com.hezhangjian.ontology.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class DatasetSourceClient {
    private final String accessKey;
    private final String secretKey;

    public DatasetSourceClient(MinioProperties properties) {
        accessKey = properties.accessKey();
        secretKey = properties.secretKey();
    }

    public List<String> readCsvLines(Map<String, Object> config, String fullPath) {
        int slash = fullPath.indexOf('/');
        if (slash < 1) {
            throw new IllegalArgumentException("CSV source path must contain a bucket");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                MinioClient.builder()
                        .endpoint(String.valueOf(config.get("endpoint")))
                        .credentials(accessKey, secretKey)
                        .build()
                        .getObject(GetObjectArgs.builder()
                                .bucket(fullPath.substring(0, slash))
                                .object(fullPath.substring(slash + 1))
                                .build()),
                StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                lines.add(line);
            }
            return List.copyOf(lines);
        } catch (IOException | GeneralSecurityException | io.minio.errors.MinioException failure) {
            throw new IllegalStateException("Dataset CSV source could not be read", failure);
        }
    }
}
