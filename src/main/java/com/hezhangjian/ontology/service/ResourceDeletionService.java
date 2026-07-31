package com.hezhangjian.ontology.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hezhangjian.ontology.repo.SqlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceDeletionService {
    private final SqlRepository jdbc;

    public ResourceDeletionService(SqlRepository jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deleteDataSource(UUID id) {
        List<UUID> pipelineIds = jdbc.query(
                "SELECT id FROM control.pipelines WHERE data_source_id=?",
                (row, index) -> row.getObject(1, UUID.class), id);
        pipelineIds.forEach(this::deletePipelineRecords);

        UUID secretId = jdbc.query(
                "SELECT secret_ref FROM control.data_sources WHERE id=?",
                (row, index) -> row.getObject(1, UUID.class), id).stream().findFirst().orElse(null);
        jdbc.update("DELETE FROM control.data_sources WHERE id=?", id);
        if (secretId != null) {
            jdbc.update("""
                    DELETE FROM control.connection_secrets c
                    WHERE c.id=? AND NOT EXISTS (
                        SELECT 1 FROM control.data_sources d WHERE d.secret_ref=c.id
                    )
                    """, secretId);
        }
    }

    @Transactional
    public void deletePipeline(UUID id) {
        deletePipelineRecords(id);
    }

    @Transactional
    public void deleteDashboard(UUID id) {
        jdbc.update("UPDATE control.dashboards SET current_version_id=NULL,active_draft_id=NULL WHERE id=?", id);
        jdbc.update("DELETE FROM control.dashboard_query_runs WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_query_plans WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_health_issues WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_dependencies WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_filter_bindings WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_filter_variables WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_widgets WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_data_sources WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_pages WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_edit_locks WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_favorites WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_permissions WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_drafts WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboard_versions WHERE dashboard_id=?", id);
        jdbc.update("DELETE FROM control.dashboards WHERE id=?", id);
    }

    @Transactional
    public void deleteOntologyResource(UUID id) {
        deleteOntologyResourceRecords(id, new LinkedHashSet<>());
    }

    private void deletePipelineRecords(UUID id) {
        List<UUID> runIds = jdbc.query(
                "SELECT id FROM control.pipeline_runs WHERE pipeline_id=?",
                (row, index) -> row.getObject(1, UUID.class), id);
        if (!runIds.isEmpty()) {
            jdbc.update("""
                    UPDATE control.pipeline_runs SET retry_of=NULL
                    WHERE retry_of IN (SELECT id FROM control.pipeline_runs WHERE pipeline_id=?)
                    """, id);
        }
        jdbc.update("""
                UPDATE control.ontology_resource_versions resource_version
                SET definition=resource_version.definition-'primaryPipelineId'
                FROM control.object_type_versions object_version
                WHERE object_version.version_id=resource_version.id
                  AND object_version.primary_pipeline_id=?
                """, id);
        jdbc.update("UPDATE control.object_type_versions SET primary_pipeline_id=NULL WHERE primary_pipeline_id=?", id);
        jdbc.update("UPDATE control.link_type_versions SET pipeline_id=NULL WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.ontology_mappings WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipeline_preview_runs WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipeline_runs WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipeline_dependencies WHERE pipeline_version_id IN (SELECT id FROM control.pipeline_versions WHERE pipeline_id=?)", id);
        jdbc.update("DELETE FROM control.pipeline_schedules WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipeline_drafts WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipeline_versions WHERE pipeline_id=?", id);
        jdbc.update("DELETE FROM control.pipelines WHERE id=?", id);
    }

    private void deleteOntologyResourceRecords(UUID id, Set<UUID> visited) {
        if (!visited.add(id)) return;

        Set<UUID> dependents = new LinkedHashSet<>();
        dependents.addAll(jdbc.query("""
                SELECT DISTINCT resource_id FROM control.link_type_versions
                WHERE left_object_type_id=? OR right_object_type_id=?
                """, (row, index) -> row.getObject(1, UUID.class), id, id));
        dependents.addAll(jdbc.query("""
                SELECT DISTINCT lv.resource_id
                FROM control.link_type_versions lv
                JOIN control.properties p ON p.id=lv.source_property_id
                WHERE p.object_type_id=?
                """, (row, index) -> row.getObject(1, UUID.class), id));
        dependents.addAll(jdbc.query("""
                SELECT DISTINCT iv.resource_id
                FROM control.interface_implementations ii
                JOIN control.interface_versions iv ON iv.version_id=ii.interface_version_id
                WHERE ii.object_type_id=?
                """, (row, index) -> row.getObject(1, UUID.class), id));
        dependents.addAll(jdbc.query(
                "SELECT resource_id FROM control.action_types WHERE target_object_type_id=?",
                (row, index) -> row.getObject(1, UUID.class), id));
        dependents.addAll(jdbc.query("""
                SELECT DISTINCT resource_id FROM control.function_type_versions
                WHERE ?=ANY(dependency_ids)
                """, (row, index) -> row.getObject(1, UUID.class), id));
        dependents.remove(id);
        dependents.forEach(dependent -> deleteOntologyResourceRecords(dependent, visited));

        List<UUID> dashboardIds = jdbc.query("""
                SELECT DISTINCT dashboard_id FROM control.dashboard_data_sources
                WHERE object_type_id=? OR reference_id=?
                UNION
                SELECT DISTINCT dashboard_id FROM control.dashboard_dependencies WHERE resource_id=?
                """, (row, index) -> row.getObject(1, UUID.class), id, id, id);
        dashboardIds.forEach(this::deleteDashboard);

        jdbc.update("DELETE FROM control.pipeline_dependencies WHERE resource_id=?", id.toString());
        jdbc.update("DELETE FROM control.dashboard_filter_bindings WHERE property_id IN (SELECT id FROM control.properties WHERE object_type_id=?)", id);
        jdbc.update("DELETE FROM control.interface_implementations WHERE property_id IN (SELECT id FROM control.properties WHERE object_type_id=?)", id);
        jdbc.update("DELETE FROM control.ontology_mappings WHERE property_id IN (SELECT id FROM control.properties WHERE object_type_id=?)", id);
        jdbc.update("DELETE FROM control.ontology_health_issues WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.ontology_mappings WHERE resource_version_id IN (SELECT id FROM control.ontology_resource_versions WHERE resource_id=?)", id);

        jdbc.update("DELETE FROM control.action_parameters WHERE action_version_id IN (SELECT version_id FROM control.action_type_versions WHERE resource_id=?)", id);
        jdbc.update("DELETE FROM control.action_type_versions WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.action_types WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.function_parameters WHERE function_version_id IN (SELECT version_id FROM control.function_type_versions WHERE resource_id=?)", id);
        jdbc.update("DELETE FROM control.function_type_versions WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.function_types WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.interface_implementations WHERE interface_version_id IN (SELECT version_id FROM control.interface_versions WHERE resource_id=?)", id);
        jdbc.update("DELETE FROM control.interface_slots WHERE interface_version_id IN (SELECT version_id FROM control.interface_versions WHERE resource_id=?)", id);
        jdbc.update("DELETE FROM control.interface_versions WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.link_type_versions WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.property_versions WHERE object_type_version_id IN (SELECT version_id FROM control.object_type_versions WHERE resource_id=?)", id);
        jdbc.update("DELETE FROM control.property_versions WHERE property_id IN (SELECT id FROM control.properties WHERE object_type_id=?)", id);
        jdbc.update("DELETE FROM control.object_type_versions WHERE resource_id=?", id);
        jdbc.update("DELETE FROM control.properties WHERE object_type_id=?", id);
        jdbc.update("DELETE FROM control.ontology_resource_versions WHERE resource_id=?", id);

        String physicalKey = jdbc.query(
                "SELECT physical_key FROM control.ontology_resources WHERE id=?",
                (row, index) -> row.getString(1), id).stream().findFirst().orElse(null);
        if (physicalKey != null) {
            jdbc.update("DELETE FROM control.object_properties WHERE type_id=?", physicalKey);
            jdbc.update("DELETE FROM control.relation_types WHERE type_id=? OR source_type_id=? OR target_type_id=?", physicalKey, physicalKey, physicalKey);
            jdbc.update("DELETE FROM control.object_types WHERE type_id=?", physicalKey);
        }
        jdbc.update("DELETE FROM control.ontology_resources WHERE id=?", id);
    }
}
