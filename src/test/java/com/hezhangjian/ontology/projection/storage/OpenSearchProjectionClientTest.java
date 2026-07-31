package com.hezhangjian.ontology.projection.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.OpenSearchProperties;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpenSearchProjectionClientTest {
    @Test
    void deletesObjectsAndRelationsForAnObjectType() {
        ObjectMapper objectMapper = new ObjectMapper();
        StorageHttpClient http = mock(StorageHttpClient.class);
        OpenSearchProjectionClient client = new OpenSearchProjectionClient(
                new OpenSearchProperties("opensearch", 9200),
                objectMapper,
                http);
        when(http.exchange(eq("DELETE"), any(URI.class), eq(null)))
                .thenReturn(new StorageHttpClient.Response(200, null, ""));

        client.deleteObjectType(
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                "employee");

        ArgumentCaptor<JsonNode> bodies = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).exchange(
                eq("DELETE"),
                eq(URI.create("http://opensearch:9200/ontology-object-employee")),
                eq(null));
        verify(http).requireSuccess(eq("POST"), any(), bodies.capture());
        String relationQuery = bodies.getValue().toString();
        assertTrue(relationQuery.contains("\"source_object_type\":\"employee\""));
        assertTrue(relationQuery.contains("\"target_object_type\":\"employee\""));
    }
}
