package com.hezhangjian.ontology.repo;

import com.hezhangjian.ontology.entity.OntologyEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OntologyRepository extends JpaRepository<OntologyEntity, UUID> {
    boolean existsByApiName(String apiName);

    Optional<OntologyEntity> findByApiName(String apiName);

    List<OntologyEntity> findAllByOrderByDisplayNameAscApiNameAsc();
}
