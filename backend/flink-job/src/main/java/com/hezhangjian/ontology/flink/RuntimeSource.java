package com.hezhangjian.ontology.flink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.CheckpointListener;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;

final class RuntimeSource extends RichSourceFunction<String> implements CheckpointedFunction, CheckpointListener {
    private final String coreUrl;
    private final int limit;
    private final boolean preview;
    private final UUID runId;
    private volatile boolean running = true;
    private transient RuntimeClient client;
    private transient RuntimeClient.RuntimeSpec spec;
    private transient AutoCloseable openResource;
    private transient KafkaConsumer<String, String> kafkaConsumer;
    private transient Consumer<byte[]> pulsarConsumer;
    private transient List<MessageId> pendingPulsar;
    private transient ListState<Long> readCountState;
    private transient ObjectMapper json;
    private long readCount;

    RuntimeSource(String coreUrl, UUID runId, boolean preview, int limit) {
        this.coreUrl = coreUrl;
        this.runId = runId;
        this.preview = preview;
        this.limit = Math.max(1, limit);
    }

    @Override
    public void open(Configuration parameters) {
        client = new RuntimeClient(coreUrl, runId);
        spec = preview ? client.exchangePreview(runId).runtime() : client.exchange();
        json = new ObjectMapper();
        pendingPulsar = new ArrayList<>();
    }

    @Override
    public void run(SourceContext<String> context) throws Exception {
        if (!preview) client.progress("READING", 0, 0, 0, "开始读取受控源资产");
        switch (spec.sourceType()) {
            case "EXTERNAL_PULSAR" -> readPulsar(context);
            case "KAFKA" -> readKafka(context);
            case "MYSQL", "POSTGRESQL" -> readJdbc(context);
            case "S3_CSV" -> readCsv(context);
            default -> throw new IllegalStateException("Unsupported source type: " + spec.sourceType());
        }
        if (!preview) client.progress("TRANSFORMING", readCount, 0, 0, "源读取完成，等待 Pipeline IR 处理");
    }

    private void readCsv(SourceContext<String> context) throws Exception {
        Map<String, Object> config = spec.sourceConfig();
        String endpoint = text(config, "endpoint");
        MinioClient minio = MinioClient.builder().endpoint(endpoint)
                .credentials(spec.credential().get("accessKey"), spec.credential().get("secretKey")).build();
        String path = text(config, "assetPath");
        int slash = path.indexOf('/');
        if (slash < 1) throw new IllegalStateException("CSV asset path is invalid");
        var stream = minio.getObject(GetObjectArgs.builder().bucket(path.substring(0, slash)).object(path.substring(slash + 1)).build());
        openResource = stream;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            if (headerLine.startsWith("\uFEFF")) headerLine = headerLine.substring(1);
            List<String> headers = java.util.Arrays.stream(headerLine.split(",", -1)).map(String::trim).toList();
            String line;
            while (running && (line = reader.readLine()) != null && readCount < limit) {
                String[] values = line.split(",", -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) row.put(headers.get(index), index < values.length ? values[index] : null);
                collect(context, row);
            }
        }
    }

    private void readJdbc(SourceContext<String> context) throws Exception {
        Map<String, Object> config = spec.sourceConfig();
        String path = text(config, "assetPath");
        String[] parts = path.split("/", 3);
        if (parts.length < 3) throw new IllegalStateException("JDBC asset path is invalid");
        String host = text(config, "host");
        int port = integer(config.get("port"), "MYSQL".equals(spec.sourceType()) ? 3306 : 5432);
        String database = text(config, "database");
        String url = "MYSQL".equals(spec.sourceType())
                ? "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                : "jdbc:postgresql://" + host + ":" + port + "/" + database;
        Properties credentials = new Properties();
        credentials.setProperty("user", spec.credential().getOrDefault("username", ""));
        credentials.setProperty("password", spec.credential().getOrDefault("password", ""));
        Connection connection = DriverManager.getConnection(url, credentials);
        openResource = connection;
        String identifierQuote = "MYSQL".equals(spec.sourceType()) ? "`" : "\"";
        String query = "SELECT * FROM " + identifierQuote + parts[1].replace(identifierQuote, "") + identifierQuote + "."
                + identifierQuote + parts[2].replace(identifierQuote, "") + identifierQuote;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(500);
            try (ResultSet result = statement.executeQuery(query)) {
                ResultSetMetaData metadata = result.getMetaData();
                while (running && result.next() && readCount < limit) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) row.put(metadata.getColumnLabel(index), result.getObject(index));
                    collect(context, row);
                }
            }
        }
    }

    private void readKafka(SourceContext<String> context) {
        Map<String, Object> config = spec.sourceConfig();
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, text(config, "bootstrapServers"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, textOr(config, "consumerGroup", "ontology-" + runId));
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, textOr(config, "offsetPolicy", "earliest").toLowerCase(java.util.Locale.ROOT));
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        if (!spec.credential().getOrDefault("username", "").isBlank()) {
            properties.put("security.protocol", textOr(config, "securityProtocol", "SASL_PLAINTEXT"));
            properties.put("sasl.mechanism", textOr(config, "saslMechanism", "PLAIN"));
            properties.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                    + clean(spec.credential().get("username")) + "\" password=\"" + clean(spec.credential().get("password")) + "\";");
        }
        kafkaConsumer = new KafkaConsumer<>(properties);
        openResource = kafkaConsumer;
        kafkaConsumer.subscribe(List.of(text(config, "assetPath")));
        while (running && readCount < limit) {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                if (readCount >= limit) break;
                synchronized (context.getCheckpointLock()) { context.collect(record.value()); readCount++; }
            }
            progress();
        }
    }

    private void readPulsar(SourceContext<String> context) throws Exception {
        Map<String, Object> config = spec.sourceConfig();
        ClientBuilder builder = PulsarClient.builder().serviceUrl(text(config, "serviceUrl"));
        if (!spec.credential().getOrDefault("token", "").isBlank()) builder.authentication(org.apache.pulsar.client.impl.auth.AuthenticationToken.class.getName(), spec.credential().get("token"));
        PulsarClient pulsar = builder.build();
        pulsarConsumer = pulsar.newConsumer(Schema.BYTES).topic(text(config, "assetPath"))
                .subscriptionName(textOr(config, "subscription", "ontology-" + runId))
                .subscriptionInitialPosition("LATEST".equalsIgnoreCase(textOr(config, "offsetPolicy", "EARLIEST"))
                        ? SubscriptionInitialPosition.Latest : SubscriptionInitialPosition.Earliest)
                .subscribe();
        openResource = () -> { pulsarConsumer.close(); pulsar.close(); };
        while (running && readCount < limit) {
            Message<byte[]> message = pulsarConsumer.receive(1, java.util.concurrent.TimeUnit.SECONDS);
            if (message == null) continue;
            synchronized (context.getCheckpointLock()) { context.collect(new String(message.getValue(), StandardCharsets.UTF_8)); readCount++; }
            if (preview) pulsarConsumer.acknowledge(message);
            else pendingPulsar.add(message.getMessageId());
            progress();
        }
    }

    private void collect(SourceContext<String> context, Map<String, Object> row) throws Exception {
        synchronized (context.getCheckpointLock()) { context.collect(json.writeValueAsString(row)); readCount++; }
        progress();
    }

    private void progress() {
        if (!preview && readCount > 0 && readCount % 100 == 0) client.progress("READING", readCount, 0, 0, "正在读取源资产");
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        readCountState.clear();
        readCountState.add(readCount);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        readCountState = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("read-count", Long.class));
        if (context.isRestored()) {
            for (Long value : readCountState.get()) readCount = Math.max(readCount, value);
        }
    }

    @Override
    public synchronized void notifyCheckpointComplete(long checkpointId) throws Exception {
        if (preview) return;
        if (kafkaConsumer != null) kafkaConsumer.commitSync();
        if (pulsarConsumer != null && pendingPulsar != null) {
            for (MessageId messageId : pendingPulsar) pulsarConsumer.acknowledge(messageId);
            pendingPulsar.clear();
        }
    }

    @Override
    public void cancel() {
        running = false;
        if (openResource != null) try { openResource.close(); } catch (Exception ignored) { }
    }

    @Override
    public void close() throws Exception {
        if (openResource != null) openResource.close();
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalStateException("Required source config is missing: " + key);
        return String.valueOf(value);
    }

    private String textOr(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int integer(Object value, int fallback) { return value instanceof Number number ? number.intValue() : fallback; }
    private String clean(String value) { return value == null ? "" : value.replace("\"", "").replace("\\", ""); }
}
