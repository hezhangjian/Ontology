package com.hezhangjian.ontology.projection.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.ProjectionProperties;
import com.hezhangjian.ontology.config.PulsarProperties;
import com.hezhangjian.ontology.instance.ObjectInstanceEvent;
import com.hezhangjian.ontology.projection.ObjectInstanceProjectionProcessor;
import com.hezhangjian.ontology.projection.ObjectInstanceProjectionProcessor.Target;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ontology.messaging",
        name = "consumers-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class ObjectInstanceProjectionConsumers implements SmartLifecycle {
    private static final Logger log =
            LoggerFactory.getLogger(ObjectInstanceProjectionConsumers.class);
    private static final Pattern TOPICS =
            Pattern.compile("persistent://ontology/object-instance/.*");
    private final PulsarProperties pulsar;
    private final ProjectionProperties projection;
    private final ObjectMapper json;
    private final ObjectInstanceProjectionProcessor processor;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            2, Thread.ofPlatform().name("object-instance-projection-", 0).factory());
    private final Map<Target, Consumer<byte[]>> consumers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private PulsarClient client;

    public ObjectInstanceProjectionConsumers(
            PulsarProperties pulsar,
            ProjectionProperties projection,
            ObjectMapper json,
            ObjectInstanceProjectionProcessor processor) {
        this.pulsar = pulsar;
        this.projection = projection;
        this.json = json;
        this.processor = processor;
    }

    @Override
    public void start() {
        try {
            client = PulsarClient.builder()
                    .serviceUrl(pulsar.url().toString())
                    .listenerName(pulsar.listenerName())
                    .build();
            consumers.put(Target.HUGEGRAPH, consumer(
                    "ontology-hugegraph-object-instance-v1"));
            consumers.put(Target.OPENSEARCH, consumer(
                    "ontology-opensearch-object-instance-v1"));
            running = true;
            consumers.forEach((target, consumer) ->
                    executor.submit(() -> consume(target, consumer)));
        } catch (Exception failure) {
            closeResources();
            throw new IllegalStateException(
                    "Cannot start object instance projection consumers", failure);
        }
    }

    private Consumer<byte[]> consumer(String subscription) throws Exception {
        return client.newConsumer(Schema.BYTES)
                .topicsPattern(TOPICS)
                .subscriptionName(subscription)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionType(SubscriptionType.Key_Shared)
                .negativeAckRedeliveryDelay(
                        projection.retryDelay().toMillis(), TimeUnit.MILLISECONDS)
                .subscribe();
    }

    private void consume(Target target, Consumer<byte[]> consumer) {
        while (running) {
            try {
                Message<byte[]> message = consumer.receive(1, TimeUnit.SECONDS);
                if (message != null) {
                    handle(target, consumer, message);
                }
            } catch (Exception failure) {
                if (running) {
                    log.warn("{} object instance projection receive failed", target);
                }
            }
        }
    }

    private void handle(Target target, Consumer<byte[]> consumer, Message<byte[]> message) {
        ObjectInstanceEvent event = null;
        try {
            event = json.readValue(message.getData(), ObjectInstanceEvent.class);
            processor.process(target, event);
            consumer.acknowledge(message);
        } catch (Exception failure) {
            if (message.getRedeliveryCount() >= projection.maxRetries()) {
                if (event != null) {
                    processor.dlq(target, event, failure);
                }
                consumer.acknowledgeAsync(message);
                log.error("{} object instance event moved to DLQ state", target, failure);
            } else {
                consumer.negativeAcknowledge(message);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        closeResources();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void closeResources() {
        consumers.values().forEach(consumer -> {
            try {
                consumer.close();
            } catch (Exception failure) {
                log.warn("Failed to close object instance consumer", failure);
            }
        });
        consumers.clear();
        if (client != null) {
            try {
                client.close();
            } catch (Exception failure) {
                log.warn("Failed to close object instance Pulsar client", failure);
            }
            client = null;
        }
    }
}
