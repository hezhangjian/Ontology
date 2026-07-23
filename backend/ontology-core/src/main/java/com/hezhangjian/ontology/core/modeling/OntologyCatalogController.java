package com.hezhangjian.ontology.core.modeling;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ontologies")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class OntologyCatalogController {
    private final OntologyCatalogService service;

    public OntologyCatalogController(OntologyCatalogService service) {
        this.service = service;
    }

    @GetMapping
    List<OntologySummary> list() { return service.list().stream().map(this::summary).toList(); }

    @PostMapping
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<OntologyDetail> create(@RequestBody OntologyCatalogService.CreateOntologyRequest request) {
        OntologyCatalogService.OntologyView created = service.create(request);
        return ResponseEntity.created(URI.create("/v1/ontologies/" + created.id()))
                .eTag(etag(created)).body(detail(created));
    }

    @GetMapping("/{ontologyId}")
    ResponseEntity<OntologyDetail> get(@PathVariable UUID ontologyId) {
        OntologyCatalogService.OntologyView ontology = service.get(ontologyId);
        return ResponseEntity.ok().eTag(etag(ontology)).body(detail(ontology));
    }

    @PatchMapping("/{ontologyId}")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<OntologyDetail> update(@PathVariable UUID ontologyId,
                                           @RequestHeader("If-Match") String ifMatch,
                                           @RequestBody OntologyCatalogService.UpdateOntologyRequest request) {
        OntologyCatalogService.OntologyView ontology = service.update(ontologyId, request, parseEtag(ifMatch));
        return ResponseEntity.ok().eTag(etag(ontology)).body(detail(ontology));
    }

    @GetMapping("/{ontologyId}/members")
    List<OntologyCatalogService.OntologyMember> members(@PathVariable UUID ontologyId) {
        return service.members(ontologyId);
    }

    @PostMapping("/{ontologyId}/members")
    ResponseEntity<OntologyCatalogService.OntologyMember> putMember(
            @PathVariable UUID ontologyId,
            @RequestBody OntologyCatalogService.PutOntologyMemberRequest request) {
        return ResponseEntity.ok(service.putMember(ontologyId, request));
    }

    @DeleteMapping("/{ontologyId}/members/{memberId}")
    ResponseEntity<Void> removeMember(@PathVariable UUID ontologyId, @PathVariable String memberId) {
        service.removeMember(ontologyId, memberId);
        return ResponseEntity.noContent().build();
    }

    private OntologySummary summary(OntologyCatalogService.OntologyView ontology) {
        return new OntologySummary(ontology.id(), ontology.apiName(), ontology.displayName(),
                service.activeRevision(ontology.id()), "OWNER", "HEALTHY");
    }

    private OntologyDetail detail(OntologyCatalogService.OntologyView ontology) {
        OntologySummary summary = summary(ontology);
        return new OntologyDetail(summary.id(), summary.apiName(), summary.displayName(), summary.activeRevision(),
                summary.role(), summary.health(), ontology.description(), ontology.createdAt(), ontology.updatedAt(),
                String.valueOf(ontology.updatedAt().toEpochMilli()));
    }

    private String etag(OntologyCatalogService.OntologyView ontology) {
        return String.valueOf(ontology.updatedAt().toEpochMilli());
    }

    private long parseEtag(String value) {
        try { return Long.parseLong(value.replace("W/", "").replace("\"", "").trim()); }
        catch (NumberFormatException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match 必须包含有效 ETag");
        }
    }

    record OntologySummary(UUID id, String apiName, String displayName, long activeRevision,
                           String role, String health) { }

    record OntologyDetail(UUID id, String apiName, String displayName, long activeRevision,
                          String role, String health, String description, Instant createdAt,
                          Instant updatedAt, String etag) { }
}
