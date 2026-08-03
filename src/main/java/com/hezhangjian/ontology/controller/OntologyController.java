package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.OntologyApi;
import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.model.OntologyPage;
import com.hezhangjian.ontology.model.UpdateOntologyReq;
import com.hezhangjian.ontology.service.OntologyService;
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
        return ontologyService
                .createOntology(createOntologyReq)
                .map(ontology -> ResponseEntity.status(HttpStatus.CREATED).body(ontology))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @Override
    public ResponseEntity<Ontology> getOntology(String ontologyId) {
        return ontologyService
                .getOntology(ontologyId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<Void> deleteOntology(String ontologyId) {
        if (!ontologyService.deleteOntology(ontologyId)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<OntologyPage> listOntologies(Integer limit, Integer offset) {
        return ResponseEntity.ok(ontologyService.listOntologies(limit, offset));
    }

    @Override
    public ResponseEntity<Ontology> updateOntology(String ontologyId, UpdateOntologyReq updateOntologyReq) {
        return ontologyService
                .updateOntology(ontologyId, updateOntologyReq)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
