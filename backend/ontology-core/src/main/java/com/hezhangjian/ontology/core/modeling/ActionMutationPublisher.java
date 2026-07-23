package com.hezhangjian.ontology.core.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class ActionMutationPublisher {
    private static final String TOPIC = "persistent://platform/commands/mutation-batches";
    private final ObjectMapper json;
    private final String serviceUrl;
    private PulsarClient client;
    private Producer<byte[]> producer;

    ActionMutationPublisher(ObjectMapper json,
                            @Value("${actions.pulsar-url:pulsar://pulsar:6650}") String serviceUrl) {
        this.json = json;
        this.serviceUrl = serviceUrl;
    }

    synchronized void publish(OntologyMutationBatch batch) {
        try {
            if (producer == null) {
                client = PulsarClient.builder().serviceUrl(serviceUrl).build();
                producer = client.newProducer(Schema.BYTES).topic(TOPIC).create();
            }
            producer.newMessage().key(batch.idempotencyKey()).value(json.writeValueAsBytes(batch))
                    .sendAsync().get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("Action mutation could not be submitted", failure);
        }
    }

    @PreDestroy
    synchronized void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
    }
}
