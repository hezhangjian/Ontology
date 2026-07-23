package com.hezhangjian.ontology.projection.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

@Component
public class StorageHttpClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper;

    public StorageHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Response exchange(String method, URI uri, JsonNode body) {
        try {
            return exchange(
                    method,
                    uri,
                    body == null ? null : objectMapper.writeValueAsString(body),
                    "application/json");
        } catch (IOException exception) {
            throw unavailable(uri, exception);
        }
    }

    public Response exchangeRaw(String method, URI uri, String body, String contentType) {
        return exchange(method, uri, body, contentType);
    }

    private Response exchange(String method, URI uri, String body, String contentType) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .method(method, publisher)
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] responseBytes = response.body();
            if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
                responseBytes = new GZIPInputStream(new ByteArrayInputStream(responseBytes)).readAllBytes();
            }
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            JsonNode json = responseBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(responseBody);
            return new Response(response.statusCode(), json, responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(uri, exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable(uri, exception);
        }
    }

    public JsonNode requireSuccess(String method, URI uri, JsonNode body) {
        Response response = exchange(method, uri, body);
        return requireSuccess(uri, response);
    }

    public JsonNode requireSuccessRaw(String method, URI uri, String body, String contentType) {
        Response response = exchangeRaw(method, uri, body, contentType);
        return requireSuccess(uri, response);
    }

    private JsonNode requireSuccess(URI uri, Response response) {
        if (response.status() < 200 || response.status() >= 300) {
            String details = response.rawBody() == null ? "" : response.rawBody()
                    .replaceAll("[\\r\\n\\t]+", " ").trim();
            if (details.length() > 1000) {
                details = details.substring(0, 1000) + "…";
            }
            throw new ProjectionException(
                    "STORAGE_HTTP_" + response.status(),
                    "Storage request failed with HTTP " + response.status() + " at " + uri.getPath()
                            + (details.isBlank() ? "" : ": " + details),
                    response.status() == 408 || response.status() == 429 || response.status() >= 500);
        }
        return response.json();
    }

    private ProjectionException unavailable(URI uri, Exception exception) {
        return new ProjectionException(
                "STORAGE_UNAVAILABLE",
                "Storage request failed at " + uri.getPath(),
                true,
                exception);
    }

    public record Response(int status, JsonNode json, String rawBody) {
    }
}
