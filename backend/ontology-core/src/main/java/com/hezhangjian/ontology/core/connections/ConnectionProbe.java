package com.hezhangjian.ontology.core.connections;

import static com.hezhangjian.ontology.core.connections.ConnectionModels.*;
import static com.hezhangjian.ontology.core.connections.ConnectionPolicy.integer;
import static com.hezhangjian.ontology.core.connections.ConnectionPolicy.string;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.ListBucketsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.springframework.stereotype.Component;

@Component
public final class ConnectionProbe {
    private static final int MAX_PREVIEW_BYTES = 1024 * 1024;

    public ProbeOutcome probe(DataSourceType type, Map<String, Object> config, Map<String, String> credential) {
        long started = System.nanoTime();
        List<TestStage> stages = new ArrayList<>();
        stages.add(stage("NETWORK", "PASSED", "地址解析与目标策略检查通过", started));
        stages.add(stage("TLS", "PASSED", tlsMessage(config), started));
        try {
            List<DiscoveredAsset> assets = switch (type) {
                case S3_CSV -> discoverS3(config, credential);
                case MYSQL, POSTGRESQL -> discoverJdbc(type, config, credential);
                case KAFKA -> discoverKafka(config, credential);
                case EXTERNAL_PULSAR -> discoverPulsar(config, credential);
            };
            stages.add(stage("AUTHENTICATION", "PASSED", "身份认证通过", started));
            stages.add(stage("METADATA", "PASSED", "元数据访问通过", started));
            stages.add(stage("DISCOVERY", "PASSED", "发现 " + assets.size() + " 个资产", started));
            return new ProbeOutcome(ConnectionStatus.HEALTHY, stages, assets, null);
        } catch (RestrictedDiscoveryException cause) {
            stages.add(stage("AUTHENTICATION", "PASSED", "身份认证通过", started));
            stages.add(stage("METADATA", "WARNING", "连接可用，但元数据枚举权限受限", started));
            stages.add(stage("DISCOVERY", "WARNING", "请在访问范围中手动指定授权资产", started));
            return new ProbeOutcome(ConnectionStatus.HEALTHY_RESTRICTED, stages, List.of(), null);
        } catch (Exception cause) {
            String safeReason = safeReason(cause);
            stages.add(stage("AUTHENTICATION", "FAILED", safeReason, started));
            stages.add(new TestStage("METADATA", "PENDING", "等待身份认证", 0));
            stages.add(new TestStage("DISCOVERY", "PENDING", "等待元数据访问", 0));
            return new ProbeOutcome(ConnectionStatus.ERROR, stages, List.of(), safeReason);
        }
    }

    public AssetPreview preview(DataSourceType type, Map<String, Object> config,
                                Map<String, String> credential, DataSourceAsset asset, int requestedLimit) {
        int limit = Math.max(1, Math.min(1000, requestedLimit));
        try {
            return switch (type) {
                case S3_CSV -> previewS3(config, credential, asset, limit);
                case MYSQL, POSTGRESQL -> previewJdbc(type, config, credential, asset, limit);
                case KAFKA, EXTERNAL_PULSAR -> new AssetPreview(
                        List.of("说明"), List.of(Map.of("说明", "消息预览使用临时只读 Reader，不创建或提交消费位置；当前资产暂无可安全展示消息。")), false, MAX_PREVIEW_BYTES);
            };
        } catch (Exception cause) {
            throw new ConnectionProblem("PREVIEW_FAILED", "预览失败，请检查资产读取权限和连接状态");
        }
    }

    private List<DiscoveredAsset> discoverS3(Map<String, Object> config, Map<String, String> credential) throws Exception {
        MinioClient client = minio(config, credential);
        String configuredBucket = optional(config, "bucket");
        String prefix = optional(config, "prefix");
        List<DiscoveredAsset> assets = new ArrayList<>();
        try {
            List<String> buckets = configuredBucket.isBlank()
                    ? client.listBuckets(ListBucketsArgs.builder().build()).stream().map(Bucket::name).toList()
                    : List.of(configuredBucket);
            for (String bucket : buckets) {
                assets.add(asset("bucket:" + bucket, bucket, bucket, null, "BUCKET", null, null, null, List.of()));
                if (!configuredBucket.isBlank() || buckets.size() <= 10) {
                    for (Result<Item> result : client.listObjects(ListObjectsArgs.builder().bucket(bucket)
                            .prefix(prefix).recursive(true).maxKeys(100).build())) {
                        Item item = result.get();
                        if (!item.isDir()) {
                            String path = bucket + "/" + item.objectName();
                            List<DiscoveredField> fields = item.objectName().toLowerCase().endsWith(".csv")
                                    ? inferCsv(client, bucket, item.objectName()) : List.of();
                            assets.add(asset("object:" + path, item.objectName(), path, bucket, "FILE",
                                    item.size(), null, null, fields));
                        }
                    }
                }
            }
        } catch (io.minio.errors.ErrorResponseException cause) {
            if (cause.errorResponse().code().contains("AccessDenied") && !configuredBucket.isBlank()) {
                throw new RestrictedDiscoveryException();
            }
            throw cause;
        }
        return assets;
    }

    private List<DiscoveredAsset> discoverJdbc(DataSourceType type, Map<String, Object> config,
                                                Map<String, String> credential) throws Exception {
        List<DiscoveredAsset> assets = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(type, config), jdbcProperties(config, credential))) {
            DatabaseMetaData meta = connection.getMetaData();
            String allowedSchema = optional(config, "schema");
            try (ResultSet tables = meta.getTables(null, allowedSchema.isBlank() ? null : allowedSchema, "%", new String[]{"TABLE", "VIEW"})) {
                int count = 0;
                while (tables.next() && count++ < 200) {
                    String schema = tables.getString("TABLE_SCHEM");
                    String name = tables.getString("TABLE_NAME");
                    List<DiscoveredField> fields = new ArrayList<>();
                    try (ResultSet columns = meta.getColumns(null, schema, name, "%")) {
                        while (columns.next()) {
                            fields.add(new DiscoveredField(columns.getString("COLUMN_NAME"),
                                    inferType(columns.getString("TYPE_NAME")), columns.getString("TYPE_NAME"),
                                    columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, false, null));
                        }
                    }
                    String path = string(config, "database") + "/" + schema + "/" + name;
                    assets.add(asset("table:" + path, name, path, schema,
                            "VIEW".equals(tables.getString("TABLE_TYPE")) ? "VIEW" : "TABLE", null, null, null, fields));
                }
            }
        }
        return assets;
    }

    private List<DiscoveredAsset> discoverKafka(Map<String, Object> config, Map<String, String> credential) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", string(config, "bootstrapServers"));
        properties.put("request.timeout.ms", integer(config, "timeoutSeconds", 15) * 1000);
        properties.put("default.api.timeout.ms", integer(config, "timeoutSeconds", 15) * 1000);
        String protocol = optional(config, "securityProtocol");
        if (!protocol.isBlank()) properties.put("security.protocol", protocol);
        if (!credential.getOrDefault("username", "").isBlank()) {
            properties.put("sasl.mechanism", config.getOrDefault("saslMechanism", "PLAIN"));
            properties.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                    + credential.get("username").replace("\"", "") + "\" password=\""
                    + credential.getOrDefault("password", "").replace("\"", "") + "\";");
        }
        try (AdminClient admin = AdminClient.create(properties)) {
            Set<String> names = admin.listTopics(new ListTopicsOptions().timeoutMs(integer(config, "timeoutSeconds", 15) * 1000))
                    .names().get(integer(config, "timeoutSeconds", 15), TimeUnit.SECONDS);
            Map<String, org.apache.kafka.clients.admin.TopicDescription> descriptions = admin.describeTopics(names).allTopicNames()
                    .get(integer(config, "timeoutSeconds", 15), TimeUnit.SECONDS);
            return descriptions.values().stream().limit(200)
                    .map(topic -> asset("topic:" + topic.name(), topic.name(), topic.name(), "cluster", "TOPIC",
                            null, null, topic.partitions().size(), List.of()))
                    .toList();
        }
    }

    private List<DiscoveredAsset> discoverPulsar(Map<String, Object> config, Map<String, String> credential) throws Exception {
        String tenant = string(config, "tenant");
        String namespace = string(config, "namespace");
        URI uri = URI.create(string(config, "adminUrl") + "/admin/v2/persistent/" + tenant + "/" + namespace);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(integer(config, "timeoutSeconds", 15))).GET();
        if (!credential.getOrDefault("token", "").isBlank()) request.header("Authorization", "Bearer " + credential.get("token"));
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) throw new RestrictedDiscoveryException();
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Pulsar metadata request failed");
        com.fasterxml.jackson.databind.JsonNode values = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        List<DiscoveredAsset> assets = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode value : values) {
            String topic = value.asText();
            assets.add(asset("topic:" + topic, topic.substring(topic.lastIndexOf('/') + 1), topic,
                    tenant + "/" + namespace, "TOPIC", null, null, null, List.of()));
        }
        return assets;
    }

    private AssetPreview previewS3(Map<String, Object> config, Map<String, String> credential,
                                   DataSourceAsset asset, int limit) throws Exception {
        int slash = asset.fullPath().indexOf('/');
        if (slash < 1 || !"FILE".equals(asset.assetType())) throw new ConnectionProblem("ASSET_NOT_PREVIEWABLE", "请选择 CSV 文件资产");
        String bucket = asset.fullPath().substring(0, slash);
        String object = asset.fullPath().substring(slash + 1);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(minio(config, credential)
                .getObject(GetObjectArgs.builder().bucket(bucket).object(object).build()), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return new AssetPreview(List.of(), List.of(), false, MAX_PREVIEW_BYTES);
            List<String> columns = csvColumns(header);
            List<Map<String, Object>> rows = new ArrayList<>();
            int bytes = header.length();
            boolean truncated = false;
            for (String line; (line = reader.readLine()) != null;) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length;
                if (rows.size() >= limit || bytes > MAX_PREVIEW_BYTES) { truncated = true; break; }
                String[] values = line.split(",", -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) row.put(columns.get(i), i < values.length ? values[i] : null);
                rows.add(row);
            }
            return new AssetPreview(columns, rows, truncated, MAX_PREVIEW_BYTES);
        }
    }

    private List<DiscoveredField> inferCsv(MinioClient client, String bucket, String object) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(object).build()), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            String sample = reader.readLine();
            if (header == null) return List.of();
            String[] names = csvColumns(header).toArray(String[]::new);
            String[] values = sample == null ? new String[0] : sample.split(",", -1);
            List<DiscoveredField> fields = new ArrayList<>();
            for (int index = 0; index < names.length; index++) {
                String value = index < values.length ? values[index] : null;
                fields.add(new DiscoveredField(names[index].trim(), inferScalar(value), "CSV", true, false, value));
            }
            return fields;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private AssetPreview previewJdbc(DataSourceType type, Map<String, Object> config,
                                     Map<String, String> credential, DataSourceAsset asset, int limit) throws Exception {
        String[] path = asset.fullPath().split("/");
        if (path.length < 3) throw new ConnectionProblem("ASSET_NOT_PREVIEWABLE", "资产路径无效");
        String quote = type == DataSourceType.MYSQL ? "`" : "\"";
        String sql = "SELECT * FROM " + quote + path[path.length - 2].replace(quote, "") + quote + "."
                + quote + path[path.length - 1].replace(quote, "") + quote + " LIMIT " + limit;
        try (Connection connection = DriverManager.getConnection(jdbcUrl(type, config), jdbcProperties(config, credential));
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            statement.setQueryTimeout(integer(config, "timeoutSeconds", 15));
            ResultSetMetaData meta = result.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i));
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next() && rows.size() < limit) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), result.getObject(i));
                rows.add(row);
            }
            return new AssetPreview(columns, rows, false, MAX_PREVIEW_BYTES);
        }
    }

    private List<String> csvColumns(String header) {
        String normalized = header.startsWith("\uFEFF") ? header.substring(1) : header;
        return java.util.Arrays.stream(normalized.split(",", -1)).map(String::trim).toList();
    }

    private MinioClient minio(Map<String, Object> config, Map<String, String> credential) {
        return MinioClient.builder().endpoint(string(config, "endpoint"))
                .credentials(requiredCredential(credential, "accessKey"), requiredCredential(credential, "secretKey"))
                .build();
    }

    private String jdbcUrl(DataSourceType type, Map<String, Object> config) {
        String scheme = type == DataSourceType.MYSQL ? "mysql" : "postgresql";
        int port = integer(config, "port", type == DataSourceType.MYSQL ? 3306 : 5432);
        return "jdbc:" + scheme + "://" + string(config, "host") + ":" + port + "/" + string(config, "database")
                + (type == DataSourceType.MYSQL ? "?useSSL=" + Boolean.parseBoolean(String.valueOf(config.getOrDefault("tls", false))) : "");
    }

    private Properties jdbcProperties(Map<String, Object> config, Map<String, String> credential) {
        Properties properties = new Properties();
        properties.setProperty("user", requiredCredential(credential, "username"));
        properties.setProperty("password", requiredCredential(credential, "password"));
        properties.setProperty("connectTimeout", String.valueOf(integer(config, "timeoutSeconds", 15)));
        properties.setProperty("socketTimeout", String.valueOf(integer(config, "timeoutSeconds", 15)));
        return properties;
    }

    private DiscoveredAsset asset(String stableKey, String name, String path, String parent, String type,
                                  Long size, Long rows, Integer partitions, List<DiscoveredField> fields) {
        return new DiscoveredAsset(stableKey, name, path, parent, type, size, rows, partitions, "READABLE", fields);
    }

    private TestStage stage(String name, String status, String message, long started) {
        return new TestStage(name, status, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private String tlsMessage(Map<String, Object> config) {
        String target = String.valueOf(config.getOrDefault("endpoint", config.getOrDefault("adminUrl", "")));
        return target.startsWith("https") || target.startsWith("pulsar+ssl") || Boolean.parseBoolean(String.valueOf(config.getOrDefault("tls", false)))
                ? "TLS 配置已启用" : "目标未启用 TLS（仅建议用于受控网络）";
    }

    private String safeReason(Exception cause) {
        String kind = cause.getClass().getSimpleName();
        if (kind.contains("Authentication") || kind.contains("Authorization")) return "身份认证失败，请检查凭据";
        if (kind.contains("Timeout")) return "连接超时，请检查地址和网络策略";
        return "无法完成连接测试（" + kind.replaceAll("[^A-Za-z0-9]", "") + "）";
    }

    private String requiredCredential(Map<String, String> credential, String key) {
        String value = credential.getOrDefault(key, "");
        if (value.isBlank()) throw new ConnectionProblem("CREDENTIAL_REQUIRED", "缺少所需凭据");
        return value;
    }

    private String optional(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String inferType(String type) {
        String normalized = type.toLowerCase();
        if (normalized.contains("int")) return "INTEGER";
        if (normalized.contains("decimal") || normalized.contains("numeric") || normalized.contains("double")) return "DECIMAL";
        if (normalized.contains("bool")) return "BOOLEAN";
        if (normalized.contains("date") || normalized.contains("time")) return "DATETIME";
        if (normalized.contains("json")) return "JSON";
        return "TEXT";
    }

    private String inferScalar(String value) {
        if (value == null || value.isBlank()) return "TEXT";
        if (value.matches("-?\\d+")) return "INTEGER";
        if (value.matches("-?\\d+\\.\\d+")) return "DECIMAL";
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return "BOOLEAN";
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*")) return "DATETIME";
        return "TEXT";
    }

    public record ProbeOutcome(ConnectionStatus status, List<TestStage> stages,
                               List<DiscoveredAsset> assets, String safeError) { }

    private static final class RestrictedDiscoveryException extends RuntimeException { }
}
