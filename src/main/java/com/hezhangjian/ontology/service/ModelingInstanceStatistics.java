package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ModelingInstanceStatistics {
    private final ObjectInstanceAuthorityReader objects;
    private final SqlClientRepository jdbc;

    public ModelingInstanceStatistics(
            ObjectInstanceAuthorityReader objects, SqlClientRepository jdbc) {
        this.objects = objects;
        this.jdbc = jdbc;
    }

    public Map<String, Long> objectCounts(UUID ontologyId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id,physical_key
                FROM control.ontology_resources
                WHERE ontology_id=:ontology AND kind='OBJECT_TYPE'
                ORDER BY physical_key
                """).param("ontology", ontologyId)
                .query((row, number) -> new TypeBinding(
                        row.getObject("id", UUID.class),
                        row.getString("physical_key")))
                .list()
                .forEach(type -> result.put(
                        type.id().toString(),
                        objects.count(ontologyId, type.physicalKey())));
        return Map.copyOf(result);
    }

    public Map<String, Long> relationCounts(UUID ontologyId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT r.id,count(latest.entity_key) instance_count
                FROM control.ontology_resources r
                LEFT JOIN LATERAL (
                  SELECT DISTINCT ON (ledger.entity_key)
                         ledger.entity_key,ledger.event_type
                  FROM control.projection_ledger ledger
                  WHERE ledger.ontology_id=r.ontology_id
                    AND ledger.entity_key LIKE
                      (r.ontology_id::text || ':relation:' || r.physical_key || ':%')
                    AND ledger.status IN ('PROJECTED','STALE')
                  ORDER BY ledger.entity_key,ledger.projection_sequence DESC
                ) latest ON latest.event_type<>'relation.delete'
                WHERE r.ontology_id=:ontology AND r.kind='LINK_TYPE'
                GROUP BY r.id,r.physical_key
                ORDER BY r.physical_key
                """).param("ontology", ontologyId)
                .query((row, number) -> Map.entry(
                        row.getObject("id", UUID.class).toString(),
                        row.getLong("instance_count")))
                .list()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private record TypeBinding(UUID id, String physicalKey) {}
}
