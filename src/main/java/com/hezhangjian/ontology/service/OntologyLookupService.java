package com.hezhangjian.ontology.service;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.hezhangjian.ontology.entity.OntologyEntity;
import com.hezhangjian.ontology.repo.OntologyRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
public class OntologyLookupService {
    public static final UUID DEFAULT_ONTOLOGY_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000a001");

    private final OntologyRepository ontologyRepository;

    @Transactional(readOnly = true)
    public UUID resolve(String apiName) {
        if (apiName == null || apiName.isBlank()) {
            return WorkspaceContext.id();
        }
        return ontologyRepository
                .findByApiName(apiName)
                .map(OntologyEntity::getInternalId)
                .orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public OntologyEntity get(UUID internalId) {
        return ontologyRepository.findById(internalId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(NOT_FOUND, "Ontology not found");
    }
}
