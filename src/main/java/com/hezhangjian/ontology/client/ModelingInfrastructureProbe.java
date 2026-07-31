package com.hezhangjian.ontology.client;

import com.hezhangjian.ontology.config.HugeGraphProperties;
import com.hezhangjian.ontology.config.OpenSearchProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class ModelingInfrastructureProbe {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI hugeGraphSchema;
    private final URI openSearchAlias;

    public ModelingInfrastructureProbe(
            HugeGraphProperties hugeGraph,
            OpenSearchProperties openSearch) {
        this.hugeGraphSchema = hugeGraph.url().resolve("/graphspaces/DEFAULT/graphs/hugegraph/schema");
        this.openSearchAlias = openSearch.url().resolve("/ontology-object-*");
    }

    public String verifyHugeGraph() {
        requireHealthy(hugeGraphSchema, "HugeGraph schema is unavailable");
        return "typed ontology schema";
    }

    public String verifyOpenSearch() {
        requireHealthy(openSearchAlias, "OpenSearch ontology alias is unavailable");
        return "ontology-object-*";
    }

    private void requireHealthy(URI uri, String message) {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(message + " (HTTP " + response.statusCode() + ")");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException(message, failure);
        }
    }
}
