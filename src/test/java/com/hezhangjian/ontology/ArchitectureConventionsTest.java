package com.hezhangjian.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ArchitectureConventionsTest {
    private static final Path CONTRACT =
            Path.of(System.getProperty("user.dir"), "openapi", "ontology.yaml");
    private static final Path JAVA_ROOT =
            Path.of(System.getProperty("user.dir"), "src", "main", "java");
    private static final Path FLINK_ROOT =
            Path.of(System.getProperty("user.dir"), "src", "flink", "java");
    private static final Path MIGRATION_ROOT =
            Path.of(System.getProperty("user.dir"), "src", "main", "resources", "db", "migration");
    private static final Path PACKAGE_ROOT = JAVA_ROOT.resolve(
            Path.of("com", "hezhangjian", "ontology"));
    private static final Set<String> HTTP_METHODS =
            Set.of("delete", "get", "head", "options", "patch", "post", "put", "trace");

    @Test
    void controllersUseGeneratedContractsWithoutLocalMappings() throws IOException {
        List<Path> controllers = javaFiles(PACKAGE_ROOT.resolve("controller"));

        assertThat(controllers).hasSize(12);
        assertThat(controllers).allSatisfy(controller -> {
            String source = read(controller);
            assertThat(source).containsPattern(
                    "public class \\w+Controller implements \\w+Api");
            assertThat(source).doesNotContainPattern(
                    "@(?:Get|Post|Put|Patch|Delete|Request)Mapping");
        });
    }

    @Test
    void legacyPackagesAreAbsent() throws IOException {
        assertThat(javaFiles(PACKAGE_ROOT))
                .map(PACKAGE_ROOT::relativize)
                .map(Path::toString)
                .noneMatch(relative -> relative.startsWith("agent" + java.io.File.separator)
                        || relative.startsWith("core" + java.io.File.separator));
    }

    @Test
    void legacyObjectProjectionPathIsAbsent() throws IOException {
        assertThat(PACKAGE_ROOT.resolve("projection/PipelineObjectMembershipService.java"))
                .doesNotExist();
        assertThat(PACKAGE_ROOT.resolve("projection/ProjectionProcessor.java"))
                .doesNotExist();
        assertThat(PACKAGE_ROOT.resolve("service/InstanceQueryService.java"))
                .doesNotExist();
        assertThat(javaFiles(PACKAGE_ROOT)).allSatisfy(source ->
                assertThat(read(source))
                        .doesNotContain("platform/ingestion/object-events")
                        .doesNotContain("projection_entity_state")
                        .doesNotContain("platform-ontology-objects"));
        assertThat(javaFiles(FLINK_ROOT)).allSatisfy(source ->
                assertThat(read(source))
                        .doesNotContain("objectProducer")
                        .doesNotContain("objectDatasetEvent")
                        .doesNotContain("OBJECT_OUTPUT must be bound to a Pipeline Dataset"));
        assertThat(javaFiles(PACKAGE_ROOT.resolve("service"))).allSatisfy(source ->
                assertThat(read(source))
                        .doesNotContain("bindObjectOutputDataset")
                        .doesNotContain("config.get(\"idField\")"));
        assertThat(files(MIGRATION_ROOT, ".sql")).allSatisfy(source ->
                assertThat(read(source)).doesNotContain("projection_entity_state"));
        assertThat(read(PACKAGE_ROOT.resolve(
                        "projection/RelationProjectionProcessor.java")))
                .contains("Relation projection processor accepts relation events only")
                .doesNotContain("!context.validated().relation()");
    }

    @SuppressWarnings("unchecked")
    @Test
    void removedExplorerWorkflowsAreAbsent() throws IOException {
        String contract = read(CONTRACT);
        assertThat(contract)
                .doesNotContain(
                        "BulkActionJobView:",
                        "CompareRequest:",
                        "ExplorationView:",
                        "ExportJobView:",
                        "ObjectListView:",
                        "RelationGraph:",
                        "SelectionTokenView:",
                        "/bulk-action-jobs",
                        "/explorations",
                        "/export-jobs",
                        "/exports",
                        "/object-lists",
                        "/object-sets/compare",
                        "/object-sets/relation-graph",
                        "/selection-tokens");

        assertThat(files(MIGRATION_ROOT, ".sql")).allSatisfy(source ->
                assertThat(read(source))
                        .doesNotContain(
                                "bulk_action_items",
                                "bulk_action_jobs",
                                "explorer_favorites",
                                "explorer_layouts",
                                "explorer_recent_items",
                                "export_jobs",
                                "object_list_items",
                                "object_lists",
                                "saved_explorations",
                                "selection_token_items",
                                "selection_tokens"));

        Map<String, Object> document = new Yaml().load(contract);
        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");
        assertThat(paths)
                .containsKeys(
                        "/v1/ontologies/{ontologyId}/explorer/home",
                        "/v1/ontologies/{ontologyId}/search/objects");
    }

    @Test
    void springJdbcDependenciesStayInRepositoryLayer() throws IOException {
        assertThat(javaFiles(PACKAGE_ROOT)).allSatisfy(source -> {
            String content = read(source);
            if (content.contains("import org.springframework.jdbc")) {
                assertThat(PACKAGE_ROOT.relativize(source).toString())
                        .startsWith("repo" + java.io.File.separator);
            }
        });
    }

    @Test
    void servicesDoNotDependOnExternalTransportLibraries() throws IOException {
        assertThat(javaFiles(PACKAGE_ROOT.resolve("service"))).allSatisfy(source ->
                assertThat(read(source))
                        .doesNotContain("import io.minio")
                        .doesNotContain("import java.net.http")
                        .doesNotContain("import org.apache.kafka")
                        .doesNotContain("import org.apache.pulsar"));
    }

    @Test
    void openApiDoesNotExposeInternalIdentifiers() throws IOException {
        assertThat(Files.readString(CONTRACT)).doesNotContain("internalId:");
    }

    @SuppressWarnings("unchecked")
    @Test
    void objectTypesHaveOneCompleteCrudContract() {
        Map<String, Object> document = new Yaml().load(read(CONTRACT));
        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");
        assertThat(paths)
                .containsKeys(
                        "/v1/ontologies/{ontologyId}/object-types",
                        "/v1/ontologies/{ontologyId}/object-types/{objectTypeId}")
                .doesNotContainKeys(
                        "/v1/ontologies/{ontologyId}/modeling/object-types",
                        "/v1/ontologies/{ontologyId}/modeling/object-types/{id}");

        Map<String, Map<String, Object>> schemas =
                (Map<String, Map<String, Object>>) ((Map<String, Object>)
                        document.get("components")).get("schemas");
        assertThat(schemas)
                .containsKeys("ResourceDraftRequest", "ResourceIdentityRequest", "ResourceView")
                .doesNotContainKeys("CreateObjectTypeReq", "ObjectType", "UpdateObjectTypeReq");

        Map<String, Object> collection =
                paths.get("/v1/ontologies/{ontologyId}/object-types");
        assertSchemaReference(collection, "post", "requestBody", "ResourceDraftRequest");
        assertResponseSchemaReference(collection, "post", "201", "ResourceView");

        Map<String, Object> item =
                paths.get("/v1/ontologies/{ontologyId}/object-types/{objectTypeId}");
        assertSchemaReference(item, "patch", "requestBody", "ResourceIdentityRequest");
        assertResponseSchemaReference(item, "get", "200", "ResourceView");
    }

    @SuppressWarnings("unchecked")
    @Test
    void objectInstancesHaveOnePostgresBackedContractSurface() {
        Map<String, Object> document = new Yaml().load(read(CONTRACT));
        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");
        String collection =
                "/v1/ontologies/{ontologyId}/object-types/{objectTypeId}/object-instances";
        String item = collection + "/{objectId}";
        assertThat(paths)
                .containsKeys(
                        collection,
                        collection + "/aggregate",
                        collection + "/bulk",
                        collection + "/imports",
                        collection + "/query",
                        collection + "/reconciliations",
                        item,
                        item + "/projection-status")
                .doesNotContainKeys(
                        "/v1/ontologies/{ontologyId}/object-instances/query",
                        "/v1/ontologies/{ontologyId}/object-types/{objectTypeId}/objects");

        assertSchemaReference(
                paths.get(collection), "post", "requestBody", "CreateObjectInstanceReq");
        assertResponseSchemaReference(
                paths.get(collection), "post", "201", "ObjectInstance");
        assertSchemaReference(
                paths.get(item), "patch", "requestBody", "UpdateObjectInstanceReq");
        assertResponseSchemaReference(paths.get(item), "get", "200", "ObjectInstance");

        Map<String, Map<String, Object>> schemas =
                (Map<String, Map<String, Object>>) ((Map<String, Object>)
                        document.get("components")).get("schemas");
        assertThat(schemas)
                .containsKeys(
                        "BulkObjectInstanceReq",
                        "CreateObjectInstanceImportReq",
                        "CreateObjectInstanceReq",
                        "ObjectInstanceAggregateReq",
                        "ObjectInstanceProjectionStatus",
                        "UpdateObjectInstanceReq");
    }

    @SuppressWarnings("unchecked")
    @Test
    void openApiRemainsCuratedAndGeneratedOneWay() {
        Map<String, Object> document = new Yaml().load(read(CONTRACT));
        List<String> documentKeys = new ArrayList<>(document.keySet());
        assertThat(documentKeys).startsWith("openapi", "info", "servers", "tags");
        assertThat(documentKeys.indexOf("paths")).isLessThan(documentKeys.indexOf("components"));

        List<Map<String, Object>> tags = (List<Map<String, Object>>) document.get("tags");
        assertThat(tags.getFirst().get("name")).isEqualTo("Ontology");

        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");
        assertThat(paths.keySet())
                .startsWith("/v1/ontologies", "/v1/ontologies/{ontologyId}");
        paths.forEach((path, pathItem) -> pathItem.forEach((method, value) -> {
            if (!HTTP_METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = (Map<String, Object>) value;
            assertThat(operation.get("summary"))
                    .as("%s %s summary", method.toUpperCase(), path)
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            Map<String, Map<String, Object>> responses =
                    (Map<String, Map<String, Object>>) operation.get("responses");
            responses.forEach((status, response) -> {
                assertThat(response.get("description"))
                        .as("%s %s response %s", method.toUpperCase(), path, status)
                        .isNotEqualTo("OK");
                Map<String, Object> content =
                        (Map<String, Object>) response.getOrDefault("content", Map.of());
                assertThat(content)
                        .as("%s %s response %s media types", method.toUpperCase(), path, status)
                        .doesNotContainKey("*/*");
            });
        }));

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Map<String, Object>> schemas =
                (Map<String, Map<String, Object>>) components.get("schemas");
        assertThat(schemas.keySet())
                .startsWith("Ontology", "CreateOntologyReq", "UpdateOntologyReq");
        assertThat(((Map<String, Object>) schemas.get("Ontology").get("properties")).keySet())
                .containsExactly("id", "name", "description", "createdAt", "updatedAt");

        Path projectRoot = Path.of(System.getProperty("user.dir"));
        assertThat(projectRoot.resolve("scripts")).doesNotExist();
        assertThat(read(projectRoot.resolve("pom.xml")))
                .contains("<inputSpec>${project.basedir}/openapi/ontology.yaml</inputSpec>")
                .doesNotContain("springdoc");
    }

    @Test
    void externalResourceResolversDoNotAcceptInternalUuidAliases() {
        assertThat(List.of(
                        PACKAGE_ROOT.resolve("service/ExplorerService.java"),
                        PACKAGE_ROOT.resolve("service/ModelingService.java"),
                        PACKAGE_ROOT.resolve("service/DatasetService.java"),
                        PACKAGE_ROOT.resolve("service/OntologyLookupService.java"),
                        PACKAGE_ROOT.resolve("security/ResourceServerSecurity.java")))
                .allSatisfy(source -> assertThat(read(source))
                        .doesNotContainPattern(
                                "(?is)(?:api_name|api_id).{0,120}\\bOR\\b.{0,120}\\bid::text")
                        .doesNotContainPattern(
                                "(?is)\\bid::text.{0,120}\\bOR\\b.{0,120}(?:api_name|api_id)"));
        assertThat(read(PACKAGE_ROOT.resolve("repo/ConversationStore.java")))
                .doesNotContain("UUID.fromString(ontologyId)")
                .doesNotContain("ontologies == null");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        return files(root, ".java");
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertSchemaReference(
            Map<String, Object> path,
            String method,
            String bodyKey,
            String schema) {
        Map<String, Object> operation = (Map<String, Object>) path.get(method);
        Map<String, Object> body = (Map<String, Object>) operation.get(bodyKey);
        Map<String, Object> content = (Map<String, Object>) body.get("content");
        Map<String, Object> mediaType =
                (Map<String, Object>) content.get("application/json");
        assertThat((Map<String, Object>) mediaType.get("schema"))
                .containsEntry("$ref", "#/components/schemas/" + schema);
    }

    @SuppressWarnings("unchecked")
    private static void assertResponseSchemaReference(
            Map<String, Object> path,
            String method,
            String status,
            String schema) {
        Map<String, Object> operation = (Map<String, Object>) path.get(method);
        Map<String, Object> responses =
                (Map<String, Object>) operation.get("responses");
        Map<String, Object> response = (Map<String, Object>) responses.get(status);
        Map<String, Object> content = (Map<String, Object>) response.get("content");
        Map<String, Object> mediaType =
                (Map<String, Object>) content.get("application/json");
        assertThat((Map<String, Object>) mediaType.get("schema"))
                .containsEntry("$ref", "#/components/schemas/" + schema);
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Java source " + source, failure);
        }
    }
}
