package com.hezhangjian.ontology.core.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class DatasetStorageClient {
    private final ObjectMapper json;
    private final MinioClient minio;
    private final URI searchBase;
    private final HttpClient http = HttpClient.newHttpClient();

    DatasetStorageClient(ObjectMapper json, ConnectionProperties connections,
                         @Value("${modeling.opensearch-url:http://opensearch:9200}") URI searchBase) {
        this.json = json;
        this.searchBase = searchBase;
        this.minio = MinioClient.builder().endpoint(connections.localCsvMinioUrl().toString())
                .credentials(connections.localCsvAccessKey(), connections.localCsvSecretKey()).build();
    }

    String store(UUID datasetId, List<Map<String, Object>> rows) {
        String objectKey = "datasets/" + datasetId + "/" + Instant.now().toEpochMilli() + "/part-00000.jsonl";
        try {
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> row : rows) body.append(json.writeValueAsString(row)).append('\n');
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            minio.putObject(PutObjectArgs.builder().bucket("warehouse").object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("application/x-ndjson").build());
            String index = index(datasetId);
            request("DELETE", "/" + index, null, true);
            request("PUT", "/" + index, Map.of("settings", Map.of("index", Map.of("number_of_shards", 1, "number_of_replicas", 0))), false);
            StringBuilder bulk = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(i + 1).append("\"}}\n");
                bulk.append(json.writeValueAsString(rows.get(i))).append('\n');
            }
            requestRaw("POST", "/_bulk?refresh=true", bulk.toString(), false);
            return "warehouse/" + objectKey;
        } catch (Exception cause) {
            throw new IllegalStateException("Dataset 正文或查询副本写入失败", cause);
        }
    }

    List<Map<String, Object>> rows(UUID datasetId) {
        try {
            String response = request("POST", "/" + index(datasetId) + "/_search",
                    Map.of("size", 10000, "sort", List.of("_doc"), "query", Map.of("match_all", Map.of())), true);
            if (response == null) return List.of();
            Map<String, Object> root = json.readValue(response, new TypeReference<>() { });
            Object hitsValue = root.get("hits");
            if (!(hitsValue instanceof Map<?, ?> hits) || !(hits.get("hits") instanceof List<?> values)) return List.of();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object value : values) if (value instanceof Map<?, ?> hit && hit.get("_source") instanceof Map<?, ?> source) {
                Map<String, Object> row = new LinkedHashMap<>();
                source.forEach((key, item) -> row.put(String.valueOf(key), item));
                rows.add(row);
            }
            return List.copyOf(rows);
        } catch (Exception cause) {
            throw new IllegalStateException("Dataset OpenSearch 查询副本不可用", cause);
        }
    }

    void delete(UUID datasetId, String objectKey) {
        try {
            request("DELETE", "/" + index(datasetId), null, true);
            if (objectKey != null && objectKey.startsWith("warehouse/") && objectKey.length() > "warehouse/".length()) {
                minio.removeObject(RemoveObjectArgs.builder().bucket("warehouse")
                        .object(objectKey.substring("warehouse/".length())).build());
            }
        } catch (Exception cause) {
            throw new IllegalStateException("Dataset 正文或查询副本删除失败", cause);
        }
    }

    private String request(String method, String path, Object body, boolean allowNotFound) throws Exception {
        return requestRaw(method, path, body == null ? null : json.writeValueAsString(body), allowNotFound);
    }

    private String requestRaw(String method, String path, String body, boolean allowNotFound) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(searchBase.resolve(path)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (allowNotFound && response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("OpenSearch HTTP " + response.statusCode());
        return response.body();
    }

    private String index(UUID datasetId) { return "platform-dataset-" + datasetId.toString().replace("-", ""); }
}
