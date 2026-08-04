package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.entity.OntologyEntity;
import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.model.OntologyPage;
import com.hezhangjian.ontology.model.UpdateOntologyReq;
import com.hezhangjian.ontology.module.OffsetPageRequest;
import com.hezhangjian.ontology.repo.OntologyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class OntologyService {
    private final OntologyRepository ontologyRepository;

    @Transactional
    public Optional<Ontology> createOntology(CreateOntologyReq createOntologyReq) {
        String ontologyId = createOntologyReq.getId();
        if (ontologyId == null || ontologyId.isBlank()) {
            ontologyId = UUID.randomUUID().toString().replace("-", "");
        }
        if (ontologyRepository.existsById(ontologyId)) {
            return Optional.empty();
        }

        OntologyEntity entity = new OntologyEntity();
        entity.setId(ontologyId);
        entity.setName(createOntologyReq.getName());
        entity.setDescription(createOntologyReq.getDescription());

        return Optional.of(toModel(ontologyRepository.save(entity)));
    }

    @Transactional
    public boolean deleteOntology(String ontologyId) {
        if (!ontologyRepository.existsById(ontologyId)) {
            return false;
        }

        ontologyRepository.deleteById(ontologyId);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Ontology> getOntology(String ontologyId) {
        return ontologyRepository.findById(ontologyId).map(this::toModel);
    }

    @Transactional(readOnly = true)
    public OntologyPage listOntologies(Integer limit, Integer offset) {
        Page<OntologyEntity> page = ontologyRepository.findAll(OffsetPageRequest.of(limit, offset, Sort.by("id")));
        return new OntologyPage()
                .items(page.getContent().stream().map(this::toModel).toList())
                .total(page.getTotalElements())
                .limit(limit)
                .offset(offset);
    }

    @Transactional
    public Optional<Ontology> updateOntology(String ontologyId, UpdateOntologyReq updateOntologyReq) {
        return ontologyRepository.findById(ontologyId).map(entity -> {
            if (updateOntologyReq.getName() != null) {
                entity.setName(updateOntologyReq.getName());
            }
            if (updateOntologyReq.getDescription() != null) {
                entity.setDescription(updateOntologyReq.getDescription());
            }
            return toModel(ontologyRepository.save(entity));
        });
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
