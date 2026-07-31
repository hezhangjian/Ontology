package com.hezhangjian.ontology.service;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

import com.hezhangjian.ontology.entity.OntologyEntity;
import com.hezhangjian.ontology.model.CreateOntologyReq;
import com.hezhangjian.ontology.model.Ontology;
import com.hezhangjian.ontology.model.UpdateOntologyReq;
import com.hezhangjian.ontology.repo.OntologyRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OntologyService {
    private static final Pattern API_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,159}");

    private final OntologyRepository ontologyRepository;

    public OntologyService(OntologyRepository ontologyRepository) {
        this.ontologyRepository = ontologyRepository;
    }

    @Transactional(readOnly = true)
    public List<Ontology> list() {
        return ontologyRepository.findAllByOrderByDisplayNameAscApiNameAsc().stream()
                .map(this::toModel)
                .toList();
    }

    @Transactional(readOnly = true)
    public Ontology get(String apiName) {
        return toModel(requireVisible(apiName));
    }

    @Transactional
    public Ontology create(CreateOntologyReq request) {
        String apiName = apiName(request.getId());
        String displayName = text(request.getName(), 32, "Ontology name");
        String description = optionalText(request.getDescription(), 1024, "Ontology description");
        if (ontologyRepository.existsByApiName(apiName)) {
            throw new ResponseStatusException(CONFLICT, "Ontology ID is already in use");
        }

        OntologyEntity entity = new OntologyEntity();
        entity.setApiName(apiName);
        entity.setDisplayName(displayName);
        entity.setDescription(description);
        try {
            entity = ontologyRepository.saveAndFlush(entity);
            return toModel(entity);
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(CONFLICT, "Ontology ID is already in use", failure);
        }
    }

    @Transactional
    public Ontology update(String currentApiName, UpdateOntologyReq request, String ifMatch) {
        OntologyEntity entity = requireVisible(currentApiName);
        requireCurrentEtag(entity, ifMatch);
        if (request.getId() != null) {
            String replacement = apiName(request.getId());
            if (!replacement.equals(entity.getApiName())
                    && ontologyRepository.existsByApiName(replacement)) {
                throw new ResponseStatusException(CONFLICT, "Ontology ID is already in use");
            }
            entity.setApiName(replacement);
        }
        if (request.getName() != null) {
            entity.setDisplayName(text(request.getName(), 32, "Ontology name"));
        }
        if (request.getDescription() != null) {
            entity.setDescription(optionalText(
                    request.getDescription(), 1024, "Ontology description"));
        }
        try {
            return toModel(ontologyRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(CONFLICT, "Ontology ID is already in use", failure);
        }
    }

    @Transactional
    public void delete(String apiName) {
        OntologyEntity entity = requireVisible(apiName);
        try {
            ontologyRepository.delete(entity);
            ontologyRepository.flush();
        } catch (DataIntegrityViolationException failure) {
            throw new ResponseStatusException(
                    CONFLICT, "Ontology still has resources that prevent deletion", failure);
        }
    }

    public String etag(Ontology ontology) {
        return '"' + ontology.getUpdatedAt().toInstant().toString() + '"';
    }

    private OntologyEntity requireVisible(String apiName) {
        return ontologyRepository
                .findByApiName(apiName)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ontology not found"));
    }

    private void requireCurrentEtag(OntologyEntity entity, String ifMatch) {
        String normalized = ifMatch == null ? "" : ifMatch.replace("W/", "").replace("\"", "").trim();
        String current = entity.getUpdatedAt().toInstant().toString();
        if (!current.equals(normalized)) {
            throw new ResponseStatusException(CONFLICT, "Ontology has been modified");
        }
    }

    private String apiName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!API_NAME.matcher(normalized).matches()) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY,
                    "Ontology ID must start with a letter and contain only letters, digits, underscores, or hyphens");
        }
        return normalized;
    }

    private String text(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY,
                    field + " must contain between 1 and " + maximumLength + " characters");
        }
        return normalized;
    }

    private String optionalText(String value, int maximumLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY,
                    field + " must not exceed " + maximumLength + " characters");
        }
        return normalized;
    }

    private Ontology toModel(OntologyEntity entity) {
        return new Ontology()
                .id(entity.getApiName())
                .name(entity.getDisplayName())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());
    }
}
