package com.hezhangjian.ontology.repo;

import com.hezhangjian.ontology.entity.DataSetEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataSetRepository extends JpaRepository<DataSetEntity, String> {
    boolean existsByOntologyIdAndId(String ontologyId, String id);

    Page<DataSetEntity> findByOntologyId(String ontologyId, Pageable pageable);

    Optional<DataSetEntity> findByOntologyIdAndId(String ontologyId, String id);
}
