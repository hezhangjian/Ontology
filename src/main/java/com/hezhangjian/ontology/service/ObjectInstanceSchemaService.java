package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.instance.ObjectInstanceService;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ObjectInstanceSchemaService {
    private final OntologyLookupService catalogs;
    private final ObjectInstanceRepository repository;
    private final ObjectInstanceService instances;

    public ObjectInstanceSchemaService(
            OntologyLookupService catalogs,
            ObjectInstanceRepository repository,
            ObjectInstanceService instances) {
        this.catalogs = catalogs;
        this.repository = repository;
        this.instances = instances;
    }

    public void provision(String ontologyApiName, String objectTypeApiName) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        WorkspaceContext.run(ontologyId, () -> {
            repository.ensureTable(repository.schema(ontologyId, objectTypeApiName));
            instances.reconcileSchema(ontologyId, ontologyApiName, objectTypeApiName);
        });
    }

    public void tombstone(String ontologyApiName, String objectTypeApiName) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        WorkspaceContext.run(ontologyId, () ->
                instances.tombstoneAll(ontologyId, ontologyApiName, objectTypeApiName));
    }
}
