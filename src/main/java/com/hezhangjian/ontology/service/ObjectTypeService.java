package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hezhangjian.ontology.entity.ObjectTypeEntity;
import com.hezhangjian.ontology.model.CreateObjectTypeReq;
import com.hezhangjian.ontology.model.ObjectType;
import com.hezhangjian.ontology.model.ObjectTypeProperty;
import com.hezhangjian.ontology.model.UpdateObjectTypeReq;
import com.hezhangjian.ontology.repo.ObjectTypeRepository;
import com.hezhangjian.ontology.repo.OntologyRepository;
import com.hezhangjian.ontology.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Service
public class ObjectTypeService {
    private static final TypeReference<List<ObjectTypeProperty>> OBJECT_TYPE_PROPERTIES = new TypeReference<>() {};

    private final ObjectTypeRepository objectTypeRepository;
    private final OntologyRepository ontologyRepository;

    @Transactional
    public ObjectType createObjectType(String ontologyId, CreateObjectTypeReq request) {
        requireOntology(ontologyId);
        if (objectTypeRepository.existsByOntologyIdAndId(ontologyId, request.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Object Type already exists");
        }
        validateProperties(request);

        ObjectTypeEntity entity = new ObjectTypeEntity();
        entity.setOntologyId(ontologyId);
        entity.setId(request.getId());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setIdField(request.getIdField());
        entity.setNameField(request.getNameField());
        entity.setPropertiesJson(writeProperties(request.getProperties()));
        return toModel(objectTypeRepository.save(entity));
    }

    @Transactional
    public void deleteObjectType(String ontologyId, String objectTypeId) {
        objectTypeRepository.delete(requireObjectType(ontologyId, objectTypeId));
    }

    @Transactional(readOnly = true)
    public ObjectType getObjectType(String ontologyId, String objectTypeId) {
        return toModel(requireObjectType(ontologyId, objectTypeId));
    }

    @Transactional(readOnly = true)
    public List<ObjectType> listObjectTypes(String ontologyId) {
        requireOntology(ontologyId);
        return objectTypeRepository.findByOntologyIdOrderByIdAsc(ontologyId).stream()
                .map(this::toModel)
                .toList();
    }

    @Transactional
    public ObjectType updateObjectType(String ontologyId, String objectTypeId, UpdateObjectTypeReq request) {
        ObjectTypeEntity entity = requireObjectType(ontologyId, objectTypeId);
        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        return toModel(objectTypeRepository.save(entity));
    }

    private ObjectTypeEntity requireObjectType(String ontologyId, String objectTypeId) {
        return objectTypeRepository
                .findByOntologyIdAndId(ontologyId, objectTypeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Object Type not found"));
    }

    private void requireOntology(String ontologyId) {
        if (!ontologyRepository.existsById(ontologyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ontology not found");
        }
    }

    private void validateProperties(CreateObjectTypeReq request) {
        Set<String> propertyNames = new HashSet<>();
        for (ObjectTypeProperty property : request.getProperties()) {
            if (!propertyNames.add(property.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Object Type property names must be unique");
            }
        }
        if (!propertyNames.contains(request.getIdField())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idField must reference an Object Type property");
        }
        if (!propertyNames.contains(request.getNameField())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nameField must reference an Object Type property");
        }
    }

    private ObjectType toModel(ObjectTypeEntity entity) {
        return new ObjectType()
                .id(entity.getId())
                .name(entity.getName())
                .ontologyId(entity.getOntologyId())
                .description(entity.getDescription())
                .idField(entity.getIdField())
                .nameField(entity.getNameField())
                .properties(readProperties(entity.getPropertiesJson()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());
    }

    private List<ObjectTypeProperty> readProperties(String propertiesJson) {
        try {
            return JacksonUtil.toList(propertiesJson, OBJECT_TYPE_PROPERTIES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read Object Type properties", e);
        }
    }

    private String writeProperties(List<ObjectTypeProperty> properties) {
        try {
            return JacksonUtil.toJson(properties);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to write Object Type properties", e);
        }
    }
}
