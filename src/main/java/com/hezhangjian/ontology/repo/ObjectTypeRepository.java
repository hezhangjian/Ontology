package com.hezhangjian.ontology.repo;

import com.hezhangjian.ontology.entity.ObjectTypeEntity;
import com.hezhangjian.ontology.entity.ObjectTypeEntityId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObjectTypeRepository extends JpaRepository<ObjectTypeEntity, ObjectTypeEntityId> {
    boolean existsByOntologyIdAndId(String ontologyId, String id);

    List<ObjectTypeEntity> findByOntologyIdOrderByIdAsc(String ontologyId);

    Optional<ObjectTypeEntity> findByOntologyIdAndId(String ontologyId, String id);
}
