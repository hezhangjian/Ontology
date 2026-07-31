package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.OntologyApi;
import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.model.UpdateOntologyReq;
import com.hezhangjian.ontology.service.OntologyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class OntologyController implements OntologyApi {
    private final OntologyService ontologyService;

    @Override
    public ResponseEntity<Ontology> createOntology(CreateOntologyReq createOntologyReq) {
        Ontology ontology = ontologyService.create(createOntologyReq);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ontologyService.etag(ontology))
                .body(ontology);
    }

    @Override
    public ResponseEntity<Void> deleteOntology(String ontologyId) {
        ontologyService.delete(ontologyId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Ontology> getOntology(String ontologyId) {
        Ontology ontology = ontologyService.get(ontologyId);
        return ResponseEntity.ok().eTag(ontologyService.etag(ontology)).body(ontology);
    }

    @Override
    public ResponseEntity<List<Ontology>> listOntologies() {
        return ResponseEntity.ok(ontologyService.list());
    }

    @Override
    public ResponseEntity<Ontology> updateOntology(
            String ifMatch, String ontologyId, UpdateOntologyReq updateOntologyReq) {
        Ontology ontology = ontologyService.update(ontologyId, updateOntologyReq, ifMatch);
        return ResponseEntity.ok().eTag(ontologyService.etag(ontology)).body(ontology);
    }
}
