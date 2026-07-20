package com.hezhangjian.ontology.core.modeling;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelingInfrastructureProbe {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI hugeGraphSchema;
    private final URI openSearchAlias;

    public ModelingInfrastructureProbe(
            @Value("${modeling.hugegraph-url:http://hugegraph:8080}") URI hugeGraph,
            @Value("${modeling.opensearch-url:http://opensearch:9200}") URI openSearch) {
        this.hugeGraphSchema = hugeGraph.resolve("/graphspaces/DEFAULT/graphs/hugegraph/schema/vertexlabels/ontology_object");
        this.openSearchAlias = openSearch.resolve("/_alias/platform-ontology-objects");
    }

    public String verifyHugeGraph() {
        requireHealthy(hugeGraphSchema, "HugeGraph ontology_object schema is unavailable");
        return "ontology_object/ontology_relation";
    }

    public String verifyOpenSearch() {
        requireHealthy(openSearchAlias, "OpenSearch ontology alias is unavailable");
        return "platform-ontology-objects";
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
