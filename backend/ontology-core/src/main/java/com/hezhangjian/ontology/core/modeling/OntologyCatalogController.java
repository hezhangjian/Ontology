package com.hezhangjian.ontology.core.modeling;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    List<OntologyCatalogService.OntologyView> list() { return service.list(); }

    @PostMapping
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<OntologyCatalogService.OntologyView> create(@RequestBody OntologyCatalogService.CreateOntologyRequest request) {
        OntologyCatalogService.OntologyView created = service.create(request);
        return ResponseEntity.created(URI.create("/v1/ontologies/" + created.id())).body(created);
    }
}
