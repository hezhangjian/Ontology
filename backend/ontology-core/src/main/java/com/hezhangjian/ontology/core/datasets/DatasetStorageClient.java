package com.hezhangjian.ontology.core.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

@Component
final class DatasetStorageClient {
    private static final int BULK_ROWS = 500;
    private static final int PARQUET_ROWS = 10_000;
    private static final Schema ROW_SCHEMA = new Schema.Parser().parse("""
            {"type":"record","name":"DatasetRow","namespace":"com.hezhangjian.ontology.dataset",
             "fields":[{"name":"row_json","type":"string"}]}
            """);
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
        return store(datasetId, (Iterable<Map<String, Object>>) rows, rows.size()).objectKey();
    }

    StoreResult store(UUID datasetId, Iterable<Map<String, Object>> rows, long expectedRows) {
        String objectPrefix = "datasets/" + datasetId + "/" + Instant.now().toEpochMilli() + "/";
        try {
            String index = index(datasetId);
            request("DELETE", "/" + index, null, true);
            request("PUT", "/" + index, Map.of("settings", Map.of("index", Map.of("number_of_shards", 1, "number_of_replicas", 0))), false);
            Iterator<Map<String, Object>> iterator = rows.iterator();
            long rowCount = 0;
            int part = 0;
            do {
                List<Map<String, Object>> chunk = new ArrayList<>(PARQUET_ROWS);
                while (iterator.hasNext() && chunk.size() < PARQUET_ROWS) chunk.add(iterator.next());
                Path parquet = Files.createTempFile("ontology-dataset-", ".parquet");
                try {
                    try (var writer = AvroParquetWriter.<GenericData.Record>builder(new LocalOutputFile(parquet))
                            .withSchema(ROW_SCHEMA)
                            .withCompressionCodec(CompressionCodecName.SNAPPY)
                            .build()) {
                        for (Map<String, Object> row : chunk) {
                            GenericData.Record record = new GenericData.Record(ROW_SCHEMA);
                            record.put("row_json", json.writeValueAsString(row));
                            writer.write(record);
                        }
                    }
                    long size = Files.size(parquet);
                    String objectKey = objectPrefix + "part-%05d.parquet".formatted(part);
                    minio.putObject(PutObjectArgs.builder().bucket("warehouse").object(objectKey)
                            .stream(Files.newInputStream(parquet), size, -1)
                            .contentType("application/vnd.apache.parquet").build());
                } finally {
                    Files.deleteIfExists(parquet);
                }
                for (int start = 0; start < chunk.size(); start += BULK_ROWS) {
                    int end = Math.min(chunk.size(), start + BULK_ROWS);
                    StringBuilder bulk = new StringBuilder();
                    for (int rowIndex = start; rowIndex < end; rowIndex++) {
                        bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"")
                                .append(rowCount + rowIndex + 1).append("\"}}\n");
                        bulk.append(json.writeValueAsString(chunk.get(rowIndex))).append('\n');
                    }
                    requestRaw("POST", "/_bulk?refresh=false", bulk.toString(), false);
                }
                rowCount += chunk.size();
                part++;
            } while (iterator.hasNext() || part == 0);
            if (expectedRows >= 0 && rowCount != expectedRows) {
                throw new IllegalStateException("Dataset expected " + expectedRows + " rows but stored " + rowCount);
            }
            request("POST", "/" + index + "/_refresh", null, false);
            return new StoreResult("warehouse/" + objectPrefix, rowCount);
        } catch (Exception cause) {
            throw new IllegalStateException("Dataset 正文或查询副本写入失败", cause);
        }
    }

    List<Map<String, Object>> rows(UUID datasetId) {
        String scrollId = null;
        try {
            String response = request("POST", "/" + index(datasetId) + "/_search?scroll=1m",
                    Map.of("size", 1000, "sort", List.of("_doc"), "query", Map.of("match_all", Map.of())), true);
            if (response == null) return List.of();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (response != null) {
                Map<String, Object> root = json.readValue(response, new TypeReference<>() { });
                scrollId = root.get("_scroll_id") == null ? scrollId : String.valueOf(root.get("_scroll_id"));
                Object hitsValue = root.get("hits");
                if (!(hitsValue instanceof Map<?, ?> hits) || !(hits.get("hits") instanceof List<?> values)
                        || values.isEmpty()) break;
                for (Object value : values) if (value instanceof Map<?, ?> hit && hit.get("_source") instanceof Map<?, ?> source) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    source.forEach((key, item) -> row.put(String.valueOf(key), item));
                    rows.add(row);
                }
                if (scrollId == null) break;
                response = request("POST", "/_search/scroll", Map.of("scroll", "1m", "scroll_id", scrollId), false);
            }
            return List.copyOf(rows);
        } catch (Exception cause) {
            throw new IllegalStateException("Dataset OpenSearch 查询副本不可用", cause);
        } finally {
            if (scrollId != null) try {
                request("DELETE", "/_search/scroll", Map.of("scroll_id", List.of(scrollId)), true);
            } catch (Exception ignored) { }
        }
    }

    void delete(UUID datasetId, String objectKey) {
        try {
            request("DELETE", "/" + index(datasetId), null, true);
            if (objectKey != null && objectKey.startsWith("warehouse/") && objectKey.length() > "warehouse/".length()) {
                String prefix = objectKey.substring("warehouse/".length());
                for (var result : minio.listObjects(ListObjectsArgs.builder().bucket("warehouse")
                        .prefix(prefix).recursive(true).build())) {
                    minio.removeObject(RemoveObjectArgs.builder().bucket("warehouse")
                            .object(result.get().objectName()).build());
                }
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

    record StoreResult(String objectKey, long rowCount) { }

    private record LocalOutputFile(Path path) implements OutputFile {
        @Override
        public PositionOutputStream create(long blockSizeHint) throws IOException {
            return stream();
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
            return stream();
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0;
        }

        private PositionOutputStream stream() throws IOException {
            OutputStream output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return new PositionOutputStream() {
                private long position;

                @Override public long getPos() { return position; }
                @Override public void write(int value) throws IOException { output.write(value); position++; }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    output.write(bytes, offset, length);
                    position += length;
                }
                @Override public void flush() throws IOException { output.flush(); }
                @Override public void close() throws IOException { output.close(); }
            };
        }
    }
}
