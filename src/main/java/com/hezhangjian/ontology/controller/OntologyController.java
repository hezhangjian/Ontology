package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.OntologyApi;
import com.hezhangjian.ontology.entity.OntologyEntity;
import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.model.UpdateOntologyReq;
import com.hezhangjian.ontology.repo.OntologyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
public class OntologyController implements OntologyApi {
    private final OntologyRepository ontologyRepository;

    @Override
    public ResponseEntity<Ontology> createOntology(CreateOntologyReq createOntologyReq) {
        String ontologyId = createOntologyReq.getId();
        if (ontologyId == null || ontologyId.isBlank()) {
            ontologyId = UUID.randomUUID().toString().replace("-", "");
        }
        if (ontologyRepository.existsById(ontologyId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        OntologyEntity entity = new OntologyEntity();
        entity.setId(ontologyId);
        entity.setName(createOntologyReq.getName());
        entity.setDescription(createOntologyReq.getDescription());

        return ResponseEntity.status(HttpStatus.CREATED).body(toModel(ontologyRepository.save(entity)));
    }

    @Override
    public ResponseEntity<Ontology> getOntology(String ontologyId) {
        return ontologyRepository.findById(ontologyId)
                .map(entity -> ResponseEntity.ok(toModel(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<Ontology>> listOntologies() {
        List<Ontology> ontologies = ontologyRepository.findAll().stream().map(this::toModel).toList();
        return ResponseEntity.ok(ontologies);
    }

    @Override
    public ResponseEntity<Ontology> updateOntology(String ontologyId, UpdateOntologyReq updateOntologyReq) {
        return ontologyRepository.findById(ontologyId)
                .map(entity -> {
                    if (updateOntologyReq.getName() != null) {
                        entity.setName(updateOntologyReq.getName());
                    }
                    if (updateOntologyReq.getDescription() != null) {
                        entity.setDescription(updateOntologyReq.getDescription());
                    }
                    return ResponseEntity.ok(toModel(ontologyRepository.save(entity)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Ontology toModel(OntologyEntity entity) {
        return new Ontology()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());
    }
}
