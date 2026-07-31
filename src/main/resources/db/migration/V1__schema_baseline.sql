--
-- PostgreSQL database dump
--


-- Dumped from database version 17.10
-- Dumped by pg_dump version 17.10

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: control; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS control;


--
-- Name: instance; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA IF NOT EXISTS instance;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: action_executions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_executions (
    id uuid NOT NULL,
    preview_id uuid NOT NULL,
    action_id uuid NOT NULL,
    idempotency_key character varying(240) NOT NULL,
    correlation_id character varying(240) NOT NULL,
    status character varying(24) NOT NULL,
    safe_error character varying(1000),
    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    ontology_id uuid NOT NULL,
    action_version integer DEFAULT 1 NOT NULL,
    trace_id character varying(240) NOT NULL,
    CONSTRAINT action_executions_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING_APPROVAL'::character varying)::text, ('SUBMITTED'::character varying)::text, ('PROJECTING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('DEGRADED'::character varying)::text, ('FAILED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: action_mutation_outbox; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_mutation_outbox (
    id uuid NOT NULL,
    execution_id uuid NOT NULL,
    payload jsonb NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT action_mutation_outbox_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: action_parameters; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_parameters (
    id uuid NOT NULL,
    action_version_id uuid NOT NULL,
    api_name character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    value_type character varying(48) NOT NULL,
    required boolean DEFAULT false NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    default_value jsonb
);


--
-- Name: action_previews; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_previews (
    id uuid NOT NULL,
    action_id uuid NOT NULL,
    action_version integer NOT NULL,
    object_id character varying(512),
    expected_version bigint,
    parameters jsonb DEFAULT '{}'::jsonb NOT NULL,
    edits jsonb NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    ontology_id uuid NOT NULL
);


--
-- Name: action_type_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_type_versions (
    version_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    operation character varying(24) NOT NULL,
    rules jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT action_type_versions_operation_check CHECK (((operation)::text = ANY (ARRAY[('CLEAR_OVERRIDES'::character varying)::text, ('CREATE'::character varying)::text, ('DELETE'::character varying)::text, ('LINK'::character varying)::text, ('UNLINK'::character varying)::text, ('UPDATE'::character varying)::text])))
);


--
-- Name: action_types; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.action_types (
    resource_id uuid NOT NULL,
    target_object_type_id uuid NOT NULL
);


--
-- Name: agent_conversations; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.agent_conversations (
    id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    title character varying(240) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: agent_messages; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.agent_messages (
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    content text NOT NULL,
    tools text DEFAULT '[]'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_messages_role_check CHECK (((role)::text = ANY (ARRAY[('assistant'::character varying)::text, ('user'::character varying)::text])))
);


--
-- Name: connection_secrets; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.connection_secrets (
    id uuid NOT NULL,
    name character varying(160) NOT NULL,
    provider character varying(16) NOT NULL,
    ciphertext bytea,
    nonce bytea,
    algorithm character varying(32) DEFAULT 'AES-256-GCM'::character varying NOT NULL,
    key_version integer DEFAULT 1 NOT NULL,
    file_references jsonb,
    credential_type character varying(40) NOT NULL,
    created_by character varying(160) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    rotated_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT connection_secrets_check CHECK (((((provider)::text = 'MANAGED'::text) AND (ciphertext IS NOT NULL) AND (nonce IS NOT NULL) AND (file_references IS NULL)) OR (((provider)::text = 'FILE'::text) AND (ciphertext IS NULL) AND (nonce IS NULL) AND (file_references IS NOT NULL)))),
    CONSTRAINT connection_secrets_provider_check CHECK (((provider)::text = ANY (ARRAY[('MANAGED'::character varying)::text, ('FILE'::character varying)::text])))
);


--
-- Name: COLUMN connection_secrets.ciphertext; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON COLUMN control.connection_secrets.ciphertext IS 'AES-256-GCM ciphertext only. The key is injected through a Docker secret.';


--
-- Name: dashboard_data_sources; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_data_sources (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    draft_id uuid,
    version_id uuid,
    stable_id uuid NOT NULL,
    name character varying(160) NOT NULL,
    source_kind character varying(24) NOT NULL,
    object_type_id uuid,
    reference_id uuid,
    reference_version integer,
    query_ast jsonb DEFAULT '{}'::jsonb NOT NULL,
    dataset_id uuid,
    CONSTRAINT dashboard_data_sources_check CHECK (((draft_id IS NULL) <> (version_id IS NULL))),
    CONSTRAINT dashboard_data_sources_source_kind_check CHECK (((source_kind)::text = ANY (ARRAY[('DATASET'::character varying)::text, ('FUNCTION'::character varying)::text, ('OBJECT_SET'::character varying)::text])))
);


--
-- Name: dashboard_dependencies; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_dependencies (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    version_id uuid NOT NULL,
    dependency_kind character varying(24) NOT NULL,
    resource_id uuid NOT NULL,
    resource_version integer,
    required boolean DEFAULT true NOT NULL,
    health_status character varying(16) NOT NULL,
    CONSTRAINT dashboard_dependencies_health_status_check CHECK (((health_status)::text = ANY (ARRAY[('HEALTHY'::character varying)::text, ('WARNING'::character varying)::text, ('ERROR'::character varying)::text, ('UNKNOWN'::character varying)::text])))
);


--
-- Name: dashboard_drafts; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_drafts (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    base_version_id uuid,
    definition jsonb NOT NULL,
    schema_version integer NOT NULL,
    etag bigint DEFAULT 1 NOT NULL,
    status character varying(24) NOT NULL,
    updated_by character varying(240) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dashboard_drafts_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VALIDATING'::character varying)::text, ('READY'::character varying)::text, ('VALIDATION_FAILED'::character varying)::text, ('PUBLISH_FAILED'::character varying)::text])))
);


--
-- Name: COLUMN dashboard_drafts.definition; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON COLUMN control.dashboard_drafts.definition IS 'Versioned typed dashboard schema mirrored into normalized page, data-source, widget, filter, and binding tables.';


--
-- Name: dashboard_edit_locks; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_edit_locks (
    dashboard_id uuid NOT NULL,
    holder_id character varying(240) NOT NULL,
    holder_name character varying(240) NOT NULL,
    lease_token uuid NOT NULL,
    acquired_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL
);


--
-- Name: dashboard_favorites; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_favorites (
    dashboard_id uuid NOT NULL,
    user_id character varying(240) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dashboard_filter_bindings; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_filter_bindings (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    draft_id uuid,
    version_id uuid,
    filter_stable_id uuid NOT NULL,
    data_source_stable_id uuid NOT NULL,
    property_id uuid NOT NULL,
    operator character varying(32) NOT NULL,
    CONSTRAINT dashboard_filter_bindings_check CHECK (((draft_id IS NULL) <> (version_id IS NULL)))
);


--
-- Name: dashboard_filter_variables; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_filter_variables (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    draft_id uuid,
    version_id uuid,
    stable_id uuid NOT NULL,
    name character varying(160) NOT NULL,
    value_type character varying(24) NOT NULL,
    control_type character varying(24) NOT NULL,
    scope character varying(16) NOT NULL,
    scope_id uuid,
    default_value jsonb,
    required boolean DEFAULT false NOT NULL,
    allow_empty boolean DEFAULT true NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    apply_mode character varying(16) NOT NULL,
    CONSTRAINT dashboard_filter_variables_apply_mode_check CHECK (((apply_mode)::text = ANY (ARRAY[('AUTO'::character varying)::text, ('MANUAL'::character varying)::text, ('DEFERRED'::character varying)::text]))),
    CONSTRAINT dashboard_filter_variables_check CHECK (((draft_id IS NULL) <> (version_id IS NULL))),
    CONSTRAINT dashboard_filter_variables_scope_check CHECK (((scope)::text = ANY (ARRAY[('GLOBAL'::character varying)::text, ('PAGE'::character varying)::text, ('WIDGET'::character varying)::text])))
);


--
-- Name: dashboard_health_issues; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_health_issues (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    version_id uuid,
    page_stable_id uuid,
    widget_stable_id uuid,
    data_source_stable_id uuid,
    severity character varying(16) NOT NULL,
    issue_code character varying(80) NOT NULL,
    summary character varying(1000) NOT NULL,
    status character varying(16) NOT NULL,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT dashboard_health_issues_severity_check CHECK (((severity)::text = ANY (ARRAY[('WARNING'::character varying)::text, ('ERROR'::character varying)::text, ('UNKNOWN'::character varying)::text]))),
    CONSTRAINT dashboard_health_issues_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('RESOLVED'::character varying)::text])))
);


--
-- Name: dashboard_pages; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_pages (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    draft_id uuid,
    version_id uuid,
    stable_id uuid NOT NULL,
    name character varying(160) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    page_order integer NOT NULL,
    CONSTRAINT dashboard_pages_check CHECK (((draft_id IS NULL) <> (version_id IS NULL)))
);


--
-- Name: dashboard_permissions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_permissions (
    dashboard_id uuid NOT NULL,
    subject_type character varying(16) NOT NULL,
    subject_id character varying(240) NOT NULL,
    permission_role character varying(16) NOT NULL,
    granted_by character varying(240) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dashboard_permissions_permission_role_check CHECK (((permission_role)::text = ANY (ARRAY[('VIEWER'::character varying)::text, ('EDITOR'::character varying)::text, ('OWNER'::character varying)::text]))),
    CONSTRAINT dashboard_permissions_subject_type_check CHECK (((subject_type)::text = ANY (ARRAY[('USER'::character varying)::text, ('GROUP'::character varying)::text, ('TEAM'::character varying)::text])))
);


--
-- Name: dashboard_query_plans; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_query_plans (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    version_id uuid NOT NULL,
    plan_hash character varying(64) NOT NULL,
    definition_hash character varying(64) NOT NULL,
    estimated_cost integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dashboard_query_runs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_query_runs (
    id uuid NOT NULL,
    plan_id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    version_id uuid NOT NULL,
    actor_id character varying(240) NOT NULL,
    page_id uuid NOT NULL,
    refresh_id uuid NOT NULL,
    security_context_hash character varying(64) NOT NULL,
    filter_hash character varying(64) NOT NULL,
    status character varying(24) NOT NULL,
    widget_count integer NOT NULL,
    succeeded_count integer DEFAULT 0 NOT NULL,
    failed_count integer DEFAULT 0 NOT NULL,
    cache_hit_count integer DEFAULT 0 NOT NULL,
    duration_ms bigint,
    watermark timestamp with time zone,
    correlation_id uuid NOT NULL,
    safe_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT dashboard_query_runs_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: TABLE dashboard_query_runs; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON TABLE control.dashboard_query_runs IS 'Stores execution metadata and hashes only, never object rows, aggregate results, or sensitive filter values.';


--
-- Name: dashboard_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_versions (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    version integer NOT NULL,
    definition jsonb NOT NULL,
    schema_version integer NOT NULL,
    query_plan_hash character varying(64) NOT NULL,
    published_by character varying(240) NOT NULL,
    published_by_name character varying(240) NOT NULL,
    release_notes text DEFAULT ''::text NOT NULL,
    health_status character varying(16) NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dashboard_versions_health_status_check CHECK (((health_status)::text = ANY (ARRAY[('HEALTHY'::character varying)::text, ('WARNING'::character varying)::text, ('ERROR'::character varying)::text, ('UNKNOWN'::character varying)::text])))
);


--
-- Name: TABLE dashboard_versions; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON TABLE control.dashboard_versions IS 'Immutable dashboard definitions and query-plan evidence; object data is queried under the current caller.';


--
-- Name: dashboard_widgets; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboard_widgets (
    id uuid NOT NULL,
    dashboard_id uuid NOT NULL,
    draft_id uuid,
    version_id uuid,
    stable_id uuid NOT NULL,
    page_stable_id uuid NOT NULL,
    data_source_stable_id uuid,
    widget_type character varying(24) NOT NULL,
    title character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    layout jsonb NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    interaction jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT dashboard_widgets_check CHECK (((draft_id IS NULL) <> (version_id IS NULL))),
    CONSTRAINT dashboard_widgets_widget_type_check CHECK (((widget_type)::text = ANY (ARRAY[('METRIC'::character varying)::text, ('LINE'::character varying)::text, ('AREA'::character varying)::text, ('BAR'::character varying)::text, ('STACKED_BAR'::character varying)::text, ('PIE'::character varying)::text, ('DONUT'::character varying)::text, ('SCATTER'::character varying)::text, ('OBJECT_TABLE'::character varying)::text, ('PIVOT'::character varying)::text, ('MARKDOWN'::character varying)::text, ('FILTER'::character varying)::text, ('SECTION'::character varying)::text])))
);


--
-- Name: dashboards; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dashboards (
    id uuid NOT NULL,
    name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    owner_id character varying(240) NOT NULL,
    owner_name character varying(240) NOT NULL,
    visibility character varying(24) NOT NULL,
    lifecycle character varying(24) NOT NULL,
    refresh_policy character varying(16) NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    current_version_id uuid,
    active_draft_id uuid,
    etag bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    last_published_at timestamp with time zone,
    archived_at timestamp with time zone,
    ontology_id uuid NOT NULL,
    CONSTRAINT dashboards_lifecycle_check CHECK (((lifecycle)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VALIDATING'::character varying)::text, ('READY'::character varying)::text, ('PUBLISHING'::character varying)::text, ('PUBLISHED'::character varying)::text, ('VALIDATION_FAILED'::character varying)::text, ('PUBLISH_FAILED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT dashboards_refresh_policy_check CHECK (((refresh_policy)::text = ANY (ARRAY[('MANUAL'::character varying)::text, ('OFF'::character varying)::text, ('1_MIN'::character varying)::text, ('5_MIN'::character varying)::text, ('15_MIN'::character varying)::text, ('60_MIN'::character varying)::text]))),
    CONSTRAINT dashboards_visibility_check CHECK (((visibility)::text = ANY (ARRAY[('PRIVATE'::character varying)::text, ('USERS'::character varying)::text, ('GROUPS'::character varying)::text, ('TEAM'::character varying)::text, ('ORGANIZATION'::character varying)::text])))
);


--
-- Name: data_source_asset_fields; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.data_source_asset_fields (
    id uuid NOT NULL,
    asset_id uuid NOT NULL,
    ordinal integer NOT NULL,
    name character varying(320) NOT NULL,
    inferred_type character varying(64) NOT NULL,
    original_type character varying(160),
    nullable boolean NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    primary_key_candidate boolean DEFAULT false NOT NULL,
    sample_value_masked character varying(320)
);


--
-- Name: data_source_assets; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.data_source_assets (
    id uuid NOT NULL,
    data_source_id uuid NOT NULL,
    stable_key character varying(768) NOT NULL,
    name character varying(320) NOT NULL,
    full_path character varying(1000) NOT NULL,
    parent_path character varying(1000),
    asset_type character varying(24) NOT NULL,
    status character varying(24) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    schema_status character varying(24) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    schema_hash character varying(64),
    schema_version integer DEFAULT 0 NOT NULL,
    size_bytes bigint,
    estimated_rows bigint,
    partition_count integer,
    permission_status character varying(24) DEFAULT 'METADATA_ONLY'::character varying NOT NULL,
    discovered_at timestamp with time zone DEFAULT now() NOT NULL,
    unavailable_at timestamp with time zone,
    CONSTRAINT data_source_assets_asset_type_check CHECK (((asset_type)::text = ANY (ARRAY[('BUCKET'::character varying)::text, ('PREFIX'::character varying)::text, ('FILE'::character varying)::text, ('DATABASE'::character varying)::text, ('SCHEMA'::character varying)::text, ('TABLE'::character varying)::text, ('VIEW'::character varying)::text, ('CLUSTER'::character varying)::text, ('TOPIC'::character varying)::text]))),
    CONSTRAINT data_source_assets_permission_status_check CHECK (((permission_status)::text = ANY (ARRAY[('READABLE'::character varying)::text, ('METADATA_ONLY'::character varying)::text, ('DENIED'::character varying)::text]))),
    CONSTRAINT data_source_assets_schema_status_check CHECK (((schema_status)::text = ANY (ARRAY[('UNKNOWN'::character varying)::text, ('READY'::character varying)::text, ('CHANGED'::character varying)::text, ('ERROR'::character varying)::text]))),
    CONSTRAINT data_source_assets_status_check CHECK (((status)::text = ANY (ARRAY[('AVAILABLE'::character varying)::text, ('NEW'::character varying)::text, ('UNAVAILABLE'::character varying)::text])))
);


--
-- Name: data_source_discovery_runs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.data_source_discovery_runs (
    id uuid NOT NULL,
    data_source_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    discovered_count integer DEFAULT 0 NOT NULL,
    requested_by character varying(160) NOT NULL,
    diagnostic jsonb,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT data_source_discovery_runs_status_check CHECK (((status)::text = ANY (ARRAY[('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: data_source_test_results; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.data_source_test_results (
    id uuid NOT NULL,
    data_source_id uuid NOT NULL,
    request_id uuid NOT NULL,
    tested_by character varying(160) NOT NULL,
    config_fingerprint character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    stages jsonb NOT NULL,
    discovered_summary jsonb DEFAULT '{}'::jsonb NOT NULL,
    tested_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_source_test_results_status_check CHECK (((status)::text = ANY (ARRAY[('HEALTHY'::character varying)::text, ('HEALTHY_RESTRICTED'::character varying)::text, ('ERROR'::character varying)::text])))
);


--
-- Name: data_sources; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.data_sources (
    id uuid NOT NULL,
    name character varying(160) NOT NULL,
    normalized_name character varying(160) NOT NULL,
    description character varying(1000),
    source_type character varying(32) NOT NULL,
    owner_id character varying(160) NOT NULL,
    owner_name character varying(240) NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    config jsonb NOT NULL,
    secret_ref uuid NOT NULL,
    connection_status character varying(32) NOT NULL,
    status_before_disable character varying(32),
    sync_status character varying(32) DEFAULT 'NO_TASKS'::character varying NOT NULL,
    asset_count integer DEFAULT 0 NOT NULL,
    last_checked_at timestamp with time zone,
    last_error jsonb,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(160) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    ontology_id uuid NOT NULL,
    CONSTRAINT data_sources_asset_count_check CHECK ((asset_count >= 0)),
    CONSTRAINT data_sources_connection_status_check CHECK (((connection_status)::text = ANY (ARRAY[('UNTESTED'::character varying)::text, ('TESTING'::character varying)::text, ('HEALTHY'::character varying)::text, ('HEALTHY_RESTRICTED'::character varying)::text, ('ERROR'::character varying)::text, ('DISABLED'::character varying)::text]))),
    CONSTRAINT data_sources_source_type_check CHECK (((source_type)::text = ANY (ARRAY[('S3_CSV'::character varying)::text, ('MYSQL'::character varying)::text, ('POSTGRESQL'::character varying)::text, ('KAFKA'::character varying)::text, ('EXTERNAL_PULSAR'::character varying)::text]))),
    CONSTRAINT data_sources_sync_status_check CHECK (((sync_status)::text = ANY (ARRAY[('NO_TASKS'::character varying)::text, ('IDLE'::character varying)::text, ('RUNNING'::character varying)::text, ('STREAMING'::character varying)::text, ('PARTIAL_FAILURE'::character varying)::text, ('ALL_FAILURE'::character varying)::text])))
);


--
-- Name: COLUMN data_sources.config; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON COLUMN control.data_sources.config IS 'Non-sensitive typed configuration; credential keys are rejected before persistence.';


--
-- Name: dataset_materialization_rows; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_materialization_rows (
    correlation_id character varying(240) NOT NULL,
    event_id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    message_id character varying(512) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    dataset_id uuid NOT NULL
);


--
-- Name: dataset_object_import_errors; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_object_import_errors (
    job_id uuid NOT NULL,
    row_number bigint NOT NULL,
    object_id character varying(512),
    field_id character varying(160),
    error_code character varying(80) NOT NULL,
    safe_message character varying(1000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dataset_object_import_jobs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_object_import_jobs (
    id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    mapping_id uuid NOT NULL,
    dataset_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    inserted_count bigint DEFAULT 0 NOT NULL,
    updated_count bigint DEFAULT 0 NOT NULL,
    deleted_count bigint DEFAULT 0 NOT NULL,
    unchanged_count bigint DEFAULT 0 NOT NULL,
    failed_count bigint DEFAULT 0 NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    safe_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT dataset_object_import_jobs_mode_check CHECK (((mode)::text = ANY ((ARRAY['UPSERT'::character varying, 'REPLACE'::character varying])::text[]))),
    CONSTRAINT dataset_object_import_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'VALIDATING'::character varying, 'MERGING'::character varying, 'COMPLETED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: dataset_object_import_staging; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_object_import_staging (
    job_id uuid NOT NULL,
    row_number bigint NOT NULL,
    object_id character varying(512) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: dataset_object_mappings; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_object_mappings (
    id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    dataset_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    identity_field character varying(240) NOT NULL,
    title_field character varying(240) NOT NULL,
    field_mappings jsonb NOT NULL,
    default_mode character varying(16) DEFAULT 'UPSERT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dataset_object_mappings_mode_check CHECK (((default_mode)::text = ANY ((ARRAY['UPSERT'::character varying, 'REPLACE'::character varying])::text[])))
);


--
-- Name: dataset_rows; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.dataset_rows (
    dataset_id uuid NOT NULL,
    row_number bigint NOT NULL,
    body jsonb NOT NULL
);


--
-- Name: datasets; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.datasets (
    id uuid NOT NULL,
    name character varying(240) NOT NULL,
    normalized_name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    pipeline_id uuid,
    schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    row_count bigint DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    object_key character varying(1000),
    owner_id character varying(240) NOT NULL,
    owner_name character varying(240) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ontology_id uuid NOT NULL,
    output_node_id character varying(120),
    api_id character varying(160) NOT NULL,
    source_kind character varying(24) DEFAULT 'PIPELINE'::character varying NOT NULL,
    CONSTRAINT datasets_source_kind_check CHECK (((source_kind)::text = ANY (ARRAY[('MANUAL'::character varying)::text, ('PIPELINE'::character varying)::text]))),
    CONSTRAINT datasets_status_check CHECK (((status)::text = ANY (ARRAY[('BUILDING'::character varying)::text, ('READY'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: function_parameters; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.function_parameters (
    id uuid NOT NULL,
    function_version_id uuid NOT NULL,
    api_name character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    value_type character varying(48) NOT NULL,
    required boolean DEFAULT false NOT NULL
);


--
-- Name: function_type_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.function_type_versions (
    version_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    output_type character varying(48) NOT NULL,
    query_dsl jsonb NOT NULL,
    dependency_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    timeout_ms integer NOT NULL,
    max_results integer NOT NULL,
    cache_seconds integer NOT NULL,
    CONSTRAINT function_type_versions_cache_seconds_check CHECK (((cache_seconds >= 0) AND (cache_seconds <= 86400))),
    CONSTRAINT function_type_versions_max_results_check CHECK (((max_results >= 1) AND (max_results <= 10000))),
    CONSTRAINT function_type_versions_timeout_ms_check CHECK (((timeout_ms >= 100) AND (timeout_ms <= 30000)))
);


--
-- Name: function_types; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.function_types (
    resource_id uuid NOT NULL
);


--
-- Name: index_rebuild_jobs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.index_rebuild_jobs (
    rebuild_id uuid NOT NULL,
    requested_by character varying(240) NOT NULL,
    correlation_id character varying(240) NOT NULL,
    target_index character varying(240),
    object_count bigint DEFAULT 0 NOT NULL,
    status character varying(24) NOT NULL,
    safe_error character varying(1000),
    requested_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    ontology_id uuid NOT NULL,
    CONSTRAINT index_rebuild_jobs_status_check CHECK (((status)::text = ANY (ARRAY[('RECEIVED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: interface_implementations; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.interface_implementations (
    id uuid NOT NULL,
    interface_version_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    slot_id uuid NOT NULL,
    property_id uuid NOT NULL
);


--
-- Name: interface_slots; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.interface_slots (
    id uuid NOT NULL,
    interface_version_id uuid NOT NULL,
    api_name character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    value_type character varying(24) NOT NULL,
    required boolean DEFAULT false NOT NULL
);


--
-- Name: interface_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.interface_versions (
    version_id uuid NOT NULL,
    resource_id uuid NOT NULL
);


--
-- Name: link_type_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.link_type_versions (
    version_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    left_object_type_id uuid NOT NULL,
    right_object_type_id uuid NOT NULL,
    cardinality character varying(8) NOT NULL,
    source_mode character varying(24) NOT NULL,
    source_property_id uuid,
    pipeline_id uuid,
    left_display_name character varying(240) NOT NULL,
    right_display_name character varying(240) NOT NULL,
    CONSTRAINT link_type_versions_cardinality_check CHECK (((cardinality)::text = ANY (ARRAY[('1:1'::character varying)::text, ('1:N'::character varying)::text, ('N:1'::character varying)::text, ('N:M'::character varying)::text]))),
    CONSTRAINT link_type_versions_source_mode_check CHECK (((source_mode)::text = ANY (ARRAY[('FOREIGN_KEY'::character varying)::text, ('MANUAL'::character varying)::text, ('PIPELINE'::character varying)::text])))
);


--
-- Name: object_instance_bulk_idempotency; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_bulk_idempotency (
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    idempotency_key character varying(240) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '24:00:00'::interval) NOT NULL
);


--
-- Name: object_instance_idempotency; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_idempotency (
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    idempotency_key character varying(240) NOT NULL,
    request_hash character varying(64) NOT NULL,
    object_id character varying(512) NOT NULL,
    response_version bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '24:00:00'::interval) NOT NULL
);


--
-- Name: object_instance_outbox; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_outbox (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    object_id character varying(512) NOT NULL,
    version bigint NOT NULL,
    correlation_id uuid NOT NULL,
    topic character varying(512) NOT NULL,
    message_key character varying(1024) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    last_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT object_instance_outbox_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PUBLISHING'::character varying, 'PUBLISHED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: object_instance_projection_state; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_projection_state (
    target character varying(24) NOT NULL,
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    object_id character varying(512) NOT NULL,
    projected_version bigint DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    last_event_id uuid,
    last_error character varying(1000),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT object_instance_projection_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PROJECTED'::character varying, 'DEGRADED'::character varying, 'DLQ'::character varying, 'STALE'::character varying])::text[]))),
    CONSTRAINT object_instance_projection_target_check CHECK (((target)::text = ANY ((ARRAY['HUGEGRAPH'::character varying, 'OPENSEARCH'::character varying])::text[])))
);


--
-- Name: object_instance_reconciliation_differences; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_reconciliation_differences (
    job_id uuid NOT NULL,
    target character varying(24) NOT NULL,
    object_id character varying(512) NOT NULL,
    difference_kind character varying(16) NOT NULL,
    authoritative_version bigint,
    projected_version bigint,
    repair_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    safe_error character varying(1000),
    CONSTRAINT object_instance_reconciliation_difference_kind_check CHECK (((difference_kind)::text = ANY ((ARRAY['MISSING'::character varying, 'STALE'::character varying, 'EXTRA'::character varying])::text[]))),
    CONSTRAINT object_instance_reconciliation_target_check CHECK (((target)::text = ANY ((ARRAY['HUGEGRAPH'::character varying, 'OPENSEARCH'::character varying])::text[])))
);


--
-- Name: object_instance_reconciliation_jobs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_reconciliation_jobs (
    id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    status character varying(24) DEFAULT 'QUEUED'::character varying NOT NULL,
    repair boolean DEFAULT true NOT NULL,
    missing_count bigint DEFAULT 0 NOT NULL,
    stale_count bigint DEFAULT 0 NOT NULL,
    extra_count bigint DEFAULT 0 NOT NULL,
    repaired_count bigint DEFAULT 0 NOT NULL,
    safe_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT object_instance_reconciliation_jobs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: object_instance_table_registry; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_instance_table_registry (
    ontology_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    object_type_api_name character varying(160) NOT NULL,
    object_type_physical_key character varying(160) NOT NULL,
    schema_name character varying(63) DEFAULT 'instance'::character varying NOT NULL,
    table_name character varying(63) NOT NULL,
    schema_version integer DEFAULT 1 NOT NULL,
    status character varying(24) DEFAULT 'READY'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT object_instance_table_registry_status_check CHECK (((status)::text = ANY ((ARRAY['CREATING'::character varying, 'READY'::character varying, 'MIGRATING'::character varying, 'BLOCKED'::character varying, 'DELETING'::character varying])::text[])))
);


--
-- Name: object_properties; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_properties (
    type_id character varying(160) NOT NULL,
    property_id character varying(160) NOT NULL,
    value_type character varying(24) NOT NULL,
    required boolean DEFAULT false NOT NULL,
    searchable boolean DEFAULT true NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    ontology_id uuid NOT NULL,
    CONSTRAINT object_properties_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('BOOLEAN'::character varying)::text, ('DATE'::character varying)::text, ('DECIMAL'::character varying)::text, ('INTEGER'::character varying)::text, ('JSON'::character varying)::text, ('TEXT'::character varying)::text])))
);


--
-- Name: object_type_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_type_versions (
    version_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    source_mode character varying(24) NOT NULL,
    primary_pipeline_id uuid,
    primary_property_id uuid,
    title_property_id uuid,
    object_count bigint DEFAULT 0 NOT NULL,
    projection_status character varying(24) DEFAULT 'NOT_DEPLOYED'::character varying NOT NULL,
    CONSTRAINT object_type_versions_source_mode_check CHECK (((source_mode)::text = ANY ((ARRAY['ACTION'::character varying, 'DATASET'::character varying, 'PIPELINE'::character varying])::text[])))
);


--
-- Name: object_types; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.object_types (
    type_id character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    ontology_id uuid NOT NULL
);


--
-- Name: ontologies; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.ontologies (
    id uuid NOT NULL,
    api_name character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    icon character varying(32) DEFAULT 'deployment-unit'::character varying NOT NULL,
    color character varying(24) DEFAULT '#3157d5'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ontology_health_issues; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.ontology_health_issues (
    id uuid NOT NULL,
    issue_key character varying(320) NOT NULL,
    severity character varying(16) NOT NULL,
    category character varying(80) NOT NULL,
    resource_id uuid,
    title character varying(240) NOT NULL,
    evidence text DEFAULT ''::text NOT NULL,
    recommendation text DEFAULT ''::text NOT NULL,
    owner_name character varying(240),
    status character varying(24) DEFAULT 'OPEN'::character varying NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    accepted_until timestamp with time zone,
    ontology_id uuid NOT NULL,
    CONSTRAINT ontology_health_issues_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('ERROR'::character varying)::text, ('CRITICAL'::character varying)::text]))),
    CONSTRAINT ontology_health_issues_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('ACCEPTED'::character varying)::text, ('RESOLVED'::character varying)::text])))
);


--
-- Name: ontology_mappings; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.ontology_mappings (
    id uuid NOT NULL,
    resource_version_id uuid NOT NULL,
    pipeline_id uuid,
    pipeline_version integer,
    sink_node_id character varying(160),
    source_field character varying(240),
    property_id uuid,
    transform_path text[] DEFAULT '{}'::text[] NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ontology_resource_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.ontology_resource_versions (
    id uuid NOT NULL,
    resource_id uuid NOT NULL,
    version integer NOT NULL,
    lifecycle character varying(24) NOT NULL,
    display_name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    maturity character varying(24) NOT NULL,
    promoted boolean DEFAULT false NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    definition jsonb DEFAULT '{}'::jsonb NOT NULL,
    fingerprint character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT ontology_resource_versions_lifecycle_check CHECK (((lifecycle)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('APPROVED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('REJECTED'::character varying)::text, ('RETIRED'::character varying)::text])))
);


--
-- Name: ontology_resources; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.ontology_resources (
    id uuid NOT NULL,
    kind character varying(24) NOT NULL,
    api_name character varying(160) NOT NULL,
    display_name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    physical_key character varying(160) NOT NULL,
    maturity character varying(24) NOT NULL,
    promoted boolean DEFAULT false NOT NULL,
    tags text[] DEFAULT '{}'::text[] NOT NULL,
    etag bigint DEFAULT 1 NOT NULL,
    latest_version integer DEFAULT 1 NOT NULL,
    active_version integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ontology_id uuid NOT NULL,
    CONSTRAINT ontology_resources_kind_check CHECK (((kind)::text = ANY (ARRAY[('OBJECT_TYPE'::character varying)::text, ('LINK_TYPE'::character varying)::text, ('INTERFACE'::character varying)::text, ('ACTION'::character varying)::text, ('FUNCTION'::character varying)::text]))),
    CONSTRAINT ontology_resources_maturity_check CHECK (((maturity)::text = ANY (ARRAY[('EXPERIMENTAL'::character varying)::text, ('ACTIVE'::character varying)::text, ('DEPRECATED'::character varying)::text])))
);


--
-- Name: pipeline_checkpoints; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_checkpoints (
    id uuid NOT NULL,
    pipeline_run_id uuid NOT NULL,
    checkpoint_type character varying(24) NOT NULL,
    external_id character varying(240),
    location character varying(1000),
    status character varying(24) NOT NULL,
    size_bytes bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_control_event_receipts; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_control_event_receipts (
    event_id uuid NOT NULL,
    message_id character varying(256) NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_dependencies; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_dependencies (
    id uuid NOT NULL,
    pipeline_version_id uuid NOT NULL,
    dependency_type character varying(40) NOT NULL,
    resource_id character varying(240) NOT NULL,
    resource_name character varying(480),
    node_id character varying(160),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL
);


--
-- Name: pipeline_drafts; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_drafts (
    pipeline_id uuid NOT NULL,
    base_version integer,
    graph jsonb NOT NULL,
    runtime jsonb NOT NULL,
    schedule jsonb NOT NULL,
    etag bigint DEFAULT 1 NOT NULL,
    updated_by character varying(160) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_object_materialization_rows; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_object_materialization_rows (
    event_id uuid NOT NULL,
    run_id uuid NOT NULL,
    output_node_id character varying(240) NOT NULL,
    object_id character varying(512) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_object_materializations; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_object_materializations (
    run_id uuid NOT NULL,
    output_node_id character varying(240) NOT NULL,
    correlation_id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    object_type_api_name character varying(160) NOT NULL,
    pipeline_mode character varying(16) NOT NULL,
    expected_rows bigint,
    received_rows bigint DEFAULT 0 NOT NULL,
    inserted_count bigint DEFAULT 0 NOT NULL,
    updated_count bigint DEFAULT 0 NOT NULL,
    deleted_count bigint DEFAULT 0 NOT NULL,
    unchanged_count bigint DEFAULT 0 NOT NULL,
    failed_count bigint DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'RECEIVING'::character varying NOT NULL,
    safe_error character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pipeline_object_materializations_mode_check CHECK (((pipeline_mode)::text = ANY ((ARRAY['BATCH'::character varying, 'STREAMING'::character varying])::text[]))),
    CONSTRAINT pipeline_object_materializations_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVING'::character varying, 'MERGING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: pipeline_object_membership_stage; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_object_membership_stage (
    run_id uuid NOT NULL,
    ontology_id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    object_type character varying(160) NOT NULL,
    object_id character varying(320) NOT NULL
);


--
-- Name: pipeline_object_memberships; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_object_memberships (
    ontology_id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    object_type character varying(160) NOT NULL,
    object_id character varying(320) NOT NULL,
    last_seen_run_id uuid NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_preview_runs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_preview_runs (
    id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    draft_etag bigint NOT NULL,
    node_id character varying(160) NOT NULL,
    status character varying(24) NOT NULL,
    flink_job_id character varying(160),
    rows jsonb DEFAULT '[]'::jsonb NOT NULL,
    schema_snapshot jsonb DEFAULT '[]'::jsonb NOT NULL,
    diagnostic jsonb,
    requested_by character varying(160) NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone NOT NULL,
    graph jsonb,
    runtime jsonb,
    row_count integer DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    row_limit integer DEFAULT 100 NOT NULL,
    CONSTRAINT pipeline_preview_runs_row_limit_check CHECK (((row_limit >= 1) AND (row_limit <= 100)))
);


--
-- Name: COLUMN pipeline_preview_runs.graph; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON COLUMN control.pipeline_preview_runs.graph IS 'Immutable draft graph snapshot compiled by the bounded Flink preview job.';


--
-- Name: COLUMN pipeline_preview_runs.row_limit; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON COLUMN control.pipeline_preview_runs.row_limit IS 'Immutable bounded preview row limit requested by the builder.';


--
-- Name: pipeline_run_events; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_run_events (
    id uuid NOT NULL,
    pipeline_run_id uuid NOT NULL,
    sequence bigint NOT NULL,
    event_type character varying(40) NOT NULL,
    status character varying(24),
    message character varying(1000) NOT NULL,
    safe_details jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_run_stages; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_run_stages (
    id uuid NOT NULL,
    pipeline_run_id uuid NOT NULL,
    stage_order integer NOT NULL,
    stage_type character varying(40) NOT NULL,
    status character varying(24) NOT NULL,
    correlation_id character varying(240) NOT NULL,
    flink_job_id character varying(160),
    event_position jsonb,
    read_count bigint DEFAULT 0 NOT NULL,
    written_count bigint DEFAULT 0 NOT NULL,
    rejected_count bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone
);


--
-- Name: pipeline_runs; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_runs (
    id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    trigger_type character varying(24) NOT NULL,
    status character varying(24) NOT NULL,
    flink_job_id character varying(160),
    correlation_id character varying(240) NOT NULL,
    read_count bigint DEFAULT 0 NOT NULL,
    written_count bigint DEFAULT 0 NOT NULL,
    rejected_count bigint DEFAULT 0 NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    pipeline_version_id uuid,
    retry_of uuid,
    requested_by character varying(160),
    requested_by_name character varying(240),
    diagnostic jsonb,
    projection_status character varying(24),
    savepoint_path character varying(1000),
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_schedules; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_schedules (
    pipeline_id uuid NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    schedule_type character varying(24) DEFAULT 'MANUAL'::character varying NOT NULL,
    cron_expression character varying(160),
    run_at timestamp with time zone,
    concurrency_policy character varying(24) DEFAULT 'SKIP'::character varying NOT NULL,
    next_run_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: pipeline_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_versions (
    id uuid NOT NULL,
    pipeline_id uuid NOT NULL,
    version integer NOT NULL,
    graph jsonb NOT NULL,
    pipeline_ir jsonb NOT NULL,
    job_spec jsonb NOT NULL,
    content_hash character varying(64) NOT NULL,
    validation jsonb NOT NULL,
    published_by character varying(160) NOT NULL,
    published_by_name character varying(240) NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE pipeline_versions; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON TABLE control.pipeline_versions IS 'Immutable published Pipeline IR and reproducible Flink job specifications.';


--
-- Name: pipeline_workloads; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipeline_workloads (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    workload_kind character varying(16) NOT NULL,
    object_key character varying(512) NOT NULL,
    payload_hash character varying(64) NOT NULL,
    status character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE pipeline_workloads; Type: COMMENT; Schema: control; Owner: -
--

COMMENT ON TABLE control.pipeline_workloads IS 'Encrypted, expiring MinIO workload bundles consumed by Flink without backend callbacks.';


--
-- Name: pipelines; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.pipelines (
    id uuid NOT NULL,
    name character varying(240) NOT NULL,
    data_source_id uuid NOT NULL,
    source_asset_id uuid,
    mode character varying(16) NOT NULL,
    status character varying(24) NOT NULL,
    owner_id character varying(160) NOT NULL,
    owner_name character varying(240) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(1000),
    normalized_name character varying(240) NOT NULL,
    template character varying(40) DEFAULT 'BLANK'::character varying NOT NULL,
    lifecycle character varying(24) DEFAULT 'DRAFT'::character varying NOT NULL,
    run_status character varying(24) DEFAULT 'NEVER_RUN'::character varying NOT NULL,
    target_summary character varying(480),
    schedule_summary character varying(240) DEFAULT 'MANUAL'::character varying NOT NULL,
    published_version integer,
    row_version bigint DEFAULT 1 NOT NULL,
    last_run_at timestamp with time zone,
    archived_at timestamp with time zone,
    ontology_id uuid NOT NULL,
    CONSTRAINT pipelines_lifecycle_check CHECK (((lifecycle)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('PUBLISHED'::character varying)::text, ('PAUSED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT pipelines_mode_check CHECK (((mode)::text = ANY (ARRAY[('BATCH'::character varying)::text, ('STREAMING'::character varying)::text]))),
    CONSTRAINT pipelines_run_status_check CHECK (((run_status)::text = ANY (ARRAY[('NEVER_RUN'::character varying)::text, ('HEALTHY'::character varying)::text, ('RUNNING'::character varying)::text, ('LIVE'::character varying)::text, ('DEGRADED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: projection_batches; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.projection_batches (
    id uuid NOT NULL,
    pipeline_run_id uuid NOT NULL,
    correlation_id character varying(240) NOT NULL,
    expected_events bigint DEFAULT 0 NOT NULL,
    acknowledged_events bigint DEFAULT 0 NOT NULL,
    failed_events bigint DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    completed_at timestamp with time zone
);


--
-- Name: projection_failures; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.projection_failures (
    failure_id uuid NOT NULL,
    event_id uuid,
    error_code character varying(80) NOT NULL,
    retryable boolean NOT NULL,
    attempt integer NOT NULL,
    safe_message character varying(1000) NOT NULL,
    failed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: projection_fk_relations; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.projection_fk_relations (
    ontology_id uuid NOT NULL,
    relation_type character varying(160) NOT NULL,
    source_object_type character varying(160) NOT NULL,
    source_object_id character varying(320) NOT NULL,
    target_object_type character varying(160) NOT NULL,
    target_object_id character varying(320) NOT NULL,
    relation_id character varying(512) NOT NULL,
    status character varying(24) NOT NULL,
    last_error character varying(1000),
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT projection_fk_relations_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PROJECTED'::character varying)::text])))
);


--
-- Name: projection_ledger; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.projection_ledger (
    event_id uuid NOT NULL,
    event_type character varying(48) NOT NULL,
    topic character varying(320) NOT NULL,
    message_id character varying(320) NOT NULL,
    entity_key character varying(512) NOT NULL,
    projection_sequence bigint NOT NULL,
    correlation_id character varying(240) NOT NULL,
    graph_element_id text,
    status character varying(24) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    last_error_code character varying(80),
    last_error_message character varying(1000),
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    graph_applied_at timestamp with time zone,
    projected_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ontology_id uuid NOT NULL,
    CONSTRAINT projection_ledger_status_check CHECK (((status)::text = ANY (ARRAY[('RECEIVED'::character varying)::text, ('GRAPH_APPLIED'::character varying)::text, ('PROJECTED'::character varying)::text, ('DEGRADED'::character varying)::text, ('STALE'::character varying)::text, ('DLQ'::character varying)::text])))
);


--
-- Name: projection_operations; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.projection_operations (
    operation_id uuid NOT NULL,
    idempotency_key character varying(240) NOT NULL,
    correlation_id character varying(240) NOT NULL,
    edit_count integer NOT NULL,
    status character varying(24) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    ontology_id uuid NOT NULL,
    CONSTRAINT projection_operations_edit_count_check CHECK (((edit_count >= 1) AND (edit_count <= 100))),
    CONSTRAINT projection_operations_status_check CHECK (((status)::text = ANY (ARRAY[('RECEIVED'::character varying)::text, ('PROJECTING'::character varying)::text, ('PROJECTED'::character varying)::text, ('DEGRADED'::character varying)::text, ('FAILED'::character varying)::text])))
);


--
-- Name: properties; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.properties (
    id uuid NOT NULL,
    object_type_id uuid NOT NULL,
    api_name character varying(160) NOT NULL,
    physical_key character varying(160) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: property_versions; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.property_versions (
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    object_type_version_id uuid NOT NULL,
    display_name character varying(240) NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    value_type character varying(24) NOT NULL,
    required boolean DEFAULT false NOT NULL,
    primary_key boolean DEFAULT false NOT NULL,
    title_property boolean DEFAULT false NOT NULL,
    searchable boolean DEFAULT true NOT NULL,
    filterable boolean DEFAULT true NOT NULL,
    sortable boolean DEFAULT false NOT NULL,
    sensitive boolean DEFAULT false NOT NULL,
    masking_policy character varying(80),
    analyzer character varying(80),
    source_field character varying(240),
    enum_values text[] DEFAULT '{}'::text[] NOT NULL,
    action_writable boolean DEFAULT true NOT NULL,
    CONSTRAINT property_versions_value_type_check CHECK (((value_type)::text = ANY (ARRAY[('STRING'::character varying)::text, ('INTEGER'::character varying)::text, ('LONG'::character varying)::text, ('DECIMAL'::character varying)::text, ('BOOLEAN'::character varying)::text, ('DATE'::character varying)::text, ('DATETIME'::character varying)::text, ('ENUM'::character varying)::text, ('STRING_ARRAY'::character varying)::text, ('INTEGER_ARRAY'::character varying)::text, ('JSON'::character varying)::text])))
);


--
-- Name: relation_projection_state; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.relation_projection_state (
    ontology_id uuid NOT NULL,
    entity_key character varying(1200) NOT NULL,
    projection_sequence bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: relation_types; Type: TABLE; Schema: control; Owner: -
--

CREATE TABLE control.relation_types (
    type_id character varying(160) NOT NULL,
    source_type_id character varying(160) NOT NULL,
    target_type_id character varying(160) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    source_mode character varying(24) DEFAULT 'FOREIGN_KEY'::character varying NOT NULL,
    source_property_id character varying(160),
    ontology_id uuid NOT NULL,
    CONSTRAINT relation_types_source_mode_check CHECK (((source_mode)::text = ANY (ARRAY[('FOREIGN_KEY'::character varying)::text, ('MANUAL'::character varying)::text, ('PIPELINE'::character varying)::text])))
);


--
-- Name: action_executions action_executions_correlation_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_correlation_id_key UNIQUE (correlation_id);


--
-- Name: action_executions action_executions_idempotency_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: action_executions action_executions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_pkey PRIMARY KEY (id);


--
-- Name: action_executions action_executions_preview_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_preview_id_key UNIQUE (preview_id);


--
-- Name: action_mutation_outbox action_mutation_outbox_execution_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_mutation_outbox
    ADD CONSTRAINT action_mutation_outbox_execution_id_key UNIQUE (execution_id);


--
-- Name: action_mutation_outbox action_mutation_outbox_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_mutation_outbox
    ADD CONSTRAINT action_mutation_outbox_pkey PRIMARY KEY (id);


--
-- Name: action_parameters action_parameters_action_version_id_api_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_parameters
    ADD CONSTRAINT action_parameters_action_version_id_api_name_key UNIQUE (action_version_id, api_name);


--
-- Name: action_parameters action_parameters_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_parameters
    ADD CONSTRAINT action_parameters_pkey PRIMARY KEY (id);


--
-- Name: action_previews action_previews_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_previews
    ADD CONSTRAINT action_previews_pkey PRIMARY KEY (id);


--
-- Name: action_previews action_previews_token_hash_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_previews
    ADD CONSTRAINT action_previews_token_hash_key UNIQUE (token_hash);


--
-- Name: action_type_versions action_type_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_type_versions
    ADD CONSTRAINT action_type_versions_pkey PRIMARY KEY (version_id);


--
-- Name: action_types action_types_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_types
    ADD CONSTRAINT action_types_pkey PRIMARY KEY (resource_id);


--
-- Name: agent_conversations agent_conversations_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.agent_conversations
    ADD CONSTRAINT agent_conversations_pkey PRIMARY KEY (id);


--
-- Name: agent_messages agent_messages_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.agent_messages
    ADD CONSTRAINT agent_messages_pkey PRIMARY KEY (id);


--
-- Name: connection_secrets connection_secrets_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.connection_secrets
    ADD CONSTRAINT connection_secrets_pkey PRIMARY KEY (id);


--
-- Name: dashboard_data_sources dashboard_data_sources_draft_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_draft_id_stable_id_key UNIQUE (draft_id, stable_id);


--
-- Name: dashboard_data_sources dashboard_data_sources_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_pkey PRIMARY KEY (id);


--
-- Name: dashboard_data_sources dashboard_data_sources_version_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_version_id_stable_id_key UNIQUE (version_id, stable_id);


--
-- Name: dashboard_dependencies dashboard_dependencies_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_dependencies
    ADD CONSTRAINT dashboard_dependencies_pkey PRIMARY KEY (id);


--
-- Name: dashboard_dependencies dashboard_dependencies_version_id_dependency_kind_resource__key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_dependencies
    ADD CONSTRAINT dashboard_dependencies_version_id_dependency_kind_resource__key UNIQUE (version_id, dependency_kind, resource_id);


--
-- Name: dashboard_drafts dashboard_drafts_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_drafts
    ADD CONSTRAINT dashboard_drafts_pkey PRIMARY KEY (id);


--
-- Name: dashboard_edit_locks dashboard_edit_locks_lease_token_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_edit_locks
    ADD CONSTRAINT dashboard_edit_locks_lease_token_key UNIQUE (lease_token);


--
-- Name: dashboard_edit_locks dashboard_edit_locks_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_edit_locks
    ADD CONSTRAINT dashboard_edit_locks_pkey PRIMARY KEY (dashboard_id);


--
-- Name: dashboard_favorites dashboard_favorites_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_favorites
    ADD CONSTRAINT dashboard_favorites_pkey PRIMARY KEY (dashboard_id, user_id);


--
-- Name: dashboard_filter_bindings dashboard_filter_bindings_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_bindings
    ADD CONSTRAINT dashboard_filter_bindings_pkey PRIMARY KEY (id);


--
-- Name: dashboard_filter_variables dashboard_filter_variables_draft_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_draft_id_stable_id_key UNIQUE (draft_id, stable_id);


--
-- Name: dashboard_filter_variables dashboard_filter_variables_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_pkey PRIMARY KEY (id);


--
-- Name: dashboard_filter_variables dashboard_filter_variables_version_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_version_id_stable_id_key UNIQUE (version_id, stable_id);


--
-- Name: dashboard_health_issues dashboard_health_issues_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_health_issues
    ADD CONSTRAINT dashboard_health_issues_pkey PRIMARY KEY (id);


--
-- Name: dashboard_pages dashboard_pages_draft_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_draft_id_stable_id_key UNIQUE (draft_id, stable_id);


--
-- Name: dashboard_pages dashboard_pages_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_pkey PRIMARY KEY (id);


--
-- Name: dashboard_pages dashboard_pages_version_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_version_id_stable_id_key UNIQUE (version_id, stable_id);


--
-- Name: dashboard_permissions dashboard_permissions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_permissions
    ADD CONSTRAINT dashboard_permissions_pkey PRIMARY KEY (dashboard_id, subject_type, subject_id);


--
-- Name: dashboard_query_plans dashboard_query_plans_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_plans
    ADD CONSTRAINT dashboard_query_plans_pkey PRIMARY KEY (id);


--
-- Name: dashboard_query_plans dashboard_query_plans_version_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_plans
    ADD CONSTRAINT dashboard_query_plans_version_id_key UNIQUE (version_id);


--
-- Name: dashboard_query_runs dashboard_query_runs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_runs
    ADD CONSTRAINT dashboard_query_runs_pkey PRIMARY KEY (id);


--
-- Name: dashboard_versions dashboard_versions_dashboard_id_version_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_versions
    ADD CONSTRAINT dashboard_versions_dashboard_id_version_key UNIQUE (dashboard_id, version);


--
-- Name: dashboard_versions dashboard_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_versions
    ADD CONSTRAINT dashboard_versions_pkey PRIMARY KEY (id);


--
-- Name: dashboard_widgets dashboard_widgets_draft_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_draft_id_stable_id_key UNIQUE (draft_id, stable_id);


--
-- Name: dashboard_widgets dashboard_widgets_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_pkey PRIMARY KEY (id);


--
-- Name: dashboard_widgets dashboard_widgets_version_id_stable_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_version_id_stable_id_key UNIQUE (version_id, stable_id);


--
-- Name: dashboards dashboards_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboards
    ADD CONSTRAINT dashboards_pkey PRIMARY KEY (id);


--
-- Name: data_source_asset_fields data_source_asset_fields_asset_id_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_asset_fields
    ADD CONSTRAINT data_source_asset_fields_asset_id_name_key UNIQUE (asset_id, name);


--
-- Name: data_source_asset_fields data_source_asset_fields_asset_id_ordinal_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_asset_fields
    ADD CONSTRAINT data_source_asset_fields_asset_id_ordinal_key UNIQUE (asset_id, ordinal);


--
-- Name: data_source_asset_fields data_source_asset_fields_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_asset_fields
    ADD CONSTRAINT data_source_asset_fields_pkey PRIMARY KEY (id);


--
-- Name: data_source_assets data_source_assets_data_source_id_stable_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_assets
    ADD CONSTRAINT data_source_assets_data_source_id_stable_key_key UNIQUE (data_source_id, stable_key);


--
-- Name: data_source_assets data_source_assets_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_assets
    ADD CONSTRAINT data_source_assets_pkey PRIMARY KEY (id);


--
-- Name: data_source_discovery_runs data_source_discovery_runs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_discovery_runs
    ADD CONSTRAINT data_source_discovery_runs_pkey PRIMARY KEY (id);


--
-- Name: data_source_test_results data_source_test_results_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_test_results
    ADD CONSTRAINT data_source_test_results_pkey PRIMARY KEY (id);


--
-- Name: data_sources data_sources_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_sources
    ADD CONSTRAINT data_sources_pkey PRIMARY KEY (id);


--
-- Name: data_sources data_sources_workspace_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_sources
    ADD CONSTRAINT data_sources_workspace_name_key UNIQUE (ontology_id, normalized_name);


--
-- Name: dataset_materialization_rows dataset_materialization_rows_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_materialization_rows
    ADD CONSTRAINT dataset_materialization_rows_pkey PRIMARY KEY (event_id);


--
-- Name: dataset_object_import_errors dataset_object_import_errors_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_errors
    ADD CONSTRAINT dataset_object_import_errors_pkey PRIMARY KEY (job_id, row_number, error_code);


--
-- Name: dataset_object_import_jobs dataset_object_import_jobs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_jobs
    ADD CONSTRAINT dataset_object_import_jobs_pkey PRIMARY KEY (id);


--
-- Name: dataset_object_import_staging dataset_object_import_staging_job_id_object_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_staging
    ADD CONSTRAINT dataset_object_import_staging_job_id_object_id_key UNIQUE (job_id, object_id);


--
-- Name: dataset_object_import_staging dataset_object_import_staging_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_staging
    ADD CONSTRAINT dataset_object_import_staging_pkey PRIMARY KEY (job_id, row_number);


--
-- Name: dataset_object_mappings dataset_object_mappings_dataset_id_object_type_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_mappings
    ADD CONSTRAINT dataset_object_mappings_dataset_id_object_type_id_key UNIQUE (dataset_id, object_type_id);


--
-- Name: dataset_object_mappings dataset_object_mappings_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_mappings
    ADD CONSTRAINT dataset_object_mappings_pkey PRIMARY KEY (id);


--
-- Name: dataset_rows dataset_rows_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_rows
    ADD CONSTRAINT dataset_rows_pkey PRIMARY KEY (dataset_id, row_number);


--
-- Name: datasets datasets_ontology_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.datasets
    ADD CONSTRAINT datasets_ontology_name_key UNIQUE (ontology_id, normalized_name);


--
-- Name: datasets datasets_pipeline_output_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.datasets
    ADD CONSTRAINT datasets_pipeline_output_key UNIQUE (pipeline_id, output_node_id);


--
-- Name: datasets datasets_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.datasets
    ADD CONSTRAINT datasets_pkey PRIMARY KEY (id);


--
-- Name: function_parameters function_parameters_function_version_id_api_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_parameters
    ADD CONSTRAINT function_parameters_function_version_id_api_name_key UNIQUE (function_version_id, api_name);


--
-- Name: function_parameters function_parameters_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_parameters
    ADD CONSTRAINT function_parameters_pkey PRIMARY KEY (id);


--
-- Name: function_type_versions function_type_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_type_versions
    ADD CONSTRAINT function_type_versions_pkey PRIMARY KEY (version_id);


--
-- Name: function_types function_types_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_types
    ADD CONSTRAINT function_types_pkey PRIMARY KEY (resource_id);


--
-- Name: index_rebuild_jobs index_rebuild_jobs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.index_rebuild_jobs
    ADD CONSTRAINT index_rebuild_jobs_pkey PRIMARY KEY (rebuild_id);


--
-- Name: interface_implementations interface_implementations_interface_version_id_object_type__key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_interface_version_id_object_type__key UNIQUE (interface_version_id, object_type_id, slot_id);


--
-- Name: interface_implementations interface_implementations_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_pkey PRIMARY KEY (id);


--
-- Name: interface_slots interface_slots_interface_version_id_api_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_slots
    ADD CONSTRAINT interface_slots_interface_version_id_api_name_key UNIQUE (interface_version_id, api_name);


--
-- Name: interface_slots interface_slots_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_slots
    ADD CONSTRAINT interface_slots_pkey PRIMARY KEY (id);


--
-- Name: interface_versions interface_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_versions
    ADD CONSTRAINT interface_versions_pkey PRIMARY KEY (version_id);


--
-- Name: link_type_versions link_type_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_pkey PRIMARY KEY (version_id);


--
-- Name: object_instance_bulk_idempotency object_instance_bulk_idempotency_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_bulk_idempotency
    ADD CONSTRAINT object_instance_bulk_idempotency_pkey PRIMARY KEY (ontology_id, object_type_id, idempotency_key);


--
-- Name: object_instance_idempotency object_instance_idempotency_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_idempotency
    ADD CONSTRAINT object_instance_idempotency_pkey PRIMARY KEY (ontology_id, object_type_id, idempotency_key);


--
-- Name: object_instance_outbox object_instance_outbox_event_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_outbox
    ADD CONSTRAINT object_instance_outbox_event_id_key UNIQUE (event_id);


--
-- Name: object_instance_outbox object_instance_outbox_ontology_id_object_type_id_object_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_outbox
    ADD CONSTRAINT object_instance_outbox_ontology_id_object_type_id_object_id_key UNIQUE (ontology_id, object_type_id, object_id, version);


--
-- Name: object_instance_outbox object_instance_outbox_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_outbox
    ADD CONSTRAINT object_instance_outbox_pkey PRIMARY KEY (id);


--
-- Name: object_instance_projection_state object_instance_projection_state_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_projection_state
    ADD CONSTRAINT object_instance_projection_state_pkey PRIMARY KEY (target, ontology_id, object_type_id, object_id);


--
-- Name: object_instance_reconciliation_differences object_instance_reconciliation_differences_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_reconciliation_differences
    ADD CONSTRAINT object_instance_reconciliation_differences_pkey PRIMARY KEY (job_id, target, object_id, difference_kind);


--
-- Name: object_instance_reconciliation_jobs object_instance_reconciliation_jobs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_reconciliation_jobs
    ADD CONSTRAINT object_instance_reconciliation_jobs_pkey PRIMARY KEY (id);


--
-- Name: object_instance_table_registry object_instance_table_registry_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_table_registry
    ADD CONSTRAINT object_instance_table_registry_pkey PRIMARY KEY (ontology_id, object_type_id);


--
-- Name: object_instance_table_registry object_instance_table_registry_schema_name_table_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_table_registry
    ADD CONSTRAINT object_instance_table_registry_schema_name_table_name_key UNIQUE (schema_name, table_name);


--
-- Name: object_properties object_properties_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_properties
    ADD CONSTRAINT object_properties_pkey PRIMARY KEY (ontology_id, type_id, property_id);


--
-- Name: object_type_versions object_type_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_type_versions
    ADD CONSTRAINT object_type_versions_pkey PRIMARY KEY (version_id);


--
-- Name: object_types object_types_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_types
    ADD CONSTRAINT object_types_pkey PRIMARY KEY (ontology_id, type_id);


--
-- Name: ontologies ontologies_api_name_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontologies
    ADD CONSTRAINT ontologies_api_name_key UNIQUE (api_name);


--
-- Name: ontologies ontologies_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontologies
    ADD CONSTRAINT ontologies_pkey PRIMARY KEY (id);


--
-- Name: ontology_health_issues ontology_health_issues_ontology_issue_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_health_issues
    ADD CONSTRAINT ontology_health_issues_ontology_issue_key_key UNIQUE (ontology_id, issue_key);


--
-- Name: ontology_health_issues ontology_health_issues_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_health_issues
    ADD CONSTRAINT ontology_health_issues_pkey PRIMARY KEY (id);


--
-- Name: ontology_mappings ontology_mappings_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_mappings
    ADD CONSTRAINT ontology_mappings_pkey PRIMARY KEY (id);


--
-- Name: ontology_resource_versions ontology_resource_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resource_versions
    ADD CONSTRAINT ontology_resource_versions_pkey PRIMARY KEY (id);


--
-- Name: ontology_resource_versions ontology_resource_versions_resource_id_version_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resource_versions
    ADD CONSTRAINT ontology_resource_versions_resource_id_version_key UNIQUE (resource_id, version);


--
-- Name: ontology_resources ontology_resources_physical_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resources
    ADD CONSTRAINT ontology_resources_physical_key_key UNIQUE (physical_key);


--
-- Name: ontology_resources ontology_resources_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resources
    ADD CONSTRAINT ontology_resources_pkey PRIMARY KEY (id);


--
-- Name: pipeline_checkpoints pipeline_checkpoints_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_checkpoints
    ADD CONSTRAINT pipeline_checkpoints_pkey PRIMARY KEY (id);


--
-- Name: pipeline_control_event_receipts pipeline_control_event_receipts_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_control_event_receipts
    ADD CONSTRAINT pipeline_control_event_receipts_pkey PRIMARY KEY (event_id);


--
-- Name: pipeline_dependencies pipeline_dependencies_pipeline_version_id_dependency_type_r_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_dependencies
    ADD CONSTRAINT pipeline_dependencies_pipeline_version_id_dependency_type_r_key UNIQUE (pipeline_version_id, dependency_type, resource_id, node_id);


--
-- Name: pipeline_dependencies pipeline_dependencies_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_dependencies
    ADD CONSTRAINT pipeline_dependencies_pkey PRIMARY KEY (id);


--
-- Name: pipeline_drafts pipeline_drafts_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_drafts
    ADD CONSTRAINT pipeline_drafts_pkey PRIMARY KEY (pipeline_id);


--
-- Name: pipeline_object_materialization_rows pipeline_object_materialization_rows_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materialization_rows
    ADD CONSTRAINT pipeline_object_materialization_rows_pkey PRIMARY KEY (event_id);


--
-- Name: pipeline_object_materializations pipeline_object_materializations_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materializations
    ADD CONSTRAINT pipeline_object_materializations_pkey PRIMARY KEY (run_id, output_node_id);


--
-- Name: pipeline_object_membership_stage pipeline_object_membership_stage_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_membership_stage
    ADD CONSTRAINT pipeline_object_membership_stage_pkey PRIMARY KEY (run_id, object_type, object_id);


--
-- Name: pipeline_object_memberships pipeline_object_memberships_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_memberships
    ADD CONSTRAINT pipeline_object_memberships_pkey PRIMARY KEY (ontology_id, pipeline_id, object_type, object_id);


--
-- Name: pipeline_preview_runs pipeline_preview_runs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_preview_runs
    ADD CONSTRAINT pipeline_preview_runs_pkey PRIMARY KEY (id);


--
-- Name: pipeline_run_events pipeline_run_events_pipeline_run_id_sequence_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_events
    ADD CONSTRAINT pipeline_run_events_pipeline_run_id_sequence_key UNIQUE (pipeline_run_id, sequence);


--
-- Name: pipeline_run_events pipeline_run_events_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_events
    ADD CONSTRAINT pipeline_run_events_pkey PRIMARY KEY (id);


--
-- Name: pipeline_run_stages pipeline_run_stages_pipeline_run_id_stage_order_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_stages
    ADD CONSTRAINT pipeline_run_stages_pipeline_run_id_stage_order_key UNIQUE (pipeline_run_id, stage_order);


--
-- Name: pipeline_run_stages pipeline_run_stages_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_stages
    ADD CONSTRAINT pipeline_run_stages_pkey PRIMARY KEY (id);


--
-- Name: pipeline_runs pipeline_runs_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_runs
    ADD CONSTRAINT pipeline_runs_pkey PRIMARY KEY (id);


--
-- Name: pipeline_schedules pipeline_schedules_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_schedules
    ADD CONSTRAINT pipeline_schedules_pkey PRIMARY KEY (pipeline_id);


--
-- Name: pipeline_versions pipeline_versions_pipeline_id_version_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_versions
    ADD CONSTRAINT pipeline_versions_pipeline_id_version_key UNIQUE (pipeline_id, version);


--
-- Name: pipeline_versions pipeline_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_versions
    ADD CONSTRAINT pipeline_versions_pkey PRIMARY KEY (id);


--
-- Name: pipeline_workloads pipeline_workloads_object_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_workloads
    ADD CONSTRAINT pipeline_workloads_object_key_key UNIQUE (object_key);


--
-- Name: pipeline_workloads pipeline_workloads_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_workloads
    ADD CONSTRAINT pipeline_workloads_pkey PRIMARY KEY (id);


--
-- Name: pipelines pipelines_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipelines
    ADD CONSTRAINT pipelines_pkey PRIMARY KEY (id);


--
-- Name: projection_batches projection_batches_pipeline_run_id_correlation_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_batches
    ADD CONSTRAINT projection_batches_pipeline_run_id_correlation_id_key UNIQUE (pipeline_run_id, correlation_id);


--
-- Name: projection_batches projection_batches_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_batches
    ADD CONSTRAINT projection_batches_pkey PRIMARY KEY (id);


--
-- Name: projection_failures projection_failures_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_failures
    ADD CONSTRAINT projection_failures_pkey PRIMARY KEY (failure_id);


--
-- Name: projection_fk_relations projection_fk_relations_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_fk_relations
    ADD CONSTRAINT projection_fk_relations_pkey PRIMARY KEY (ontology_id, relation_type, source_object_type, source_object_id);


--
-- Name: projection_ledger projection_ledger_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_ledger
    ADD CONSTRAINT projection_ledger_pkey PRIMARY KEY (event_id);


--
-- Name: projection_operations projection_operations_idempotency_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_operations
    ADD CONSTRAINT projection_operations_idempotency_key_key UNIQUE (idempotency_key);


--
-- Name: projection_operations projection_operations_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_operations
    ADD CONSTRAINT projection_operations_pkey PRIMARY KEY (operation_id);


--
-- Name: properties properties_physical_key_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.properties
    ADD CONSTRAINT properties_physical_key_key UNIQUE (physical_key);


--
-- Name: properties properties_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.properties
    ADD CONSTRAINT properties_pkey PRIMARY KEY (id);


--
-- Name: property_versions property_versions_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.property_versions
    ADD CONSTRAINT property_versions_pkey PRIMARY KEY (id);


--
-- Name: property_versions property_versions_property_id_object_type_version_id_key; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.property_versions
    ADD CONSTRAINT property_versions_property_id_object_type_version_id_key UNIQUE (property_id, object_type_version_id);


--
-- Name: relation_projection_state relation_projection_state_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.relation_projection_state
    ADD CONSTRAINT relation_projection_state_pkey PRIMARY KEY (ontology_id, entity_key);


--
-- Name: relation_types relation_types_pkey; Type: CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.relation_types
    ADD CONSTRAINT relation_types_pkey PRIMARY KEY (ontology_id, type_id);


--
-- Name: action_executions_action_time_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX action_executions_action_time_idx ON control.action_executions USING btree (action_id, submitted_at DESC);


--
-- Name: action_mutation_outbox_pending_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX action_mutation_outbox_pending_idx ON control.action_mutation_outbox USING btree (status, next_attempt_at, created_at);


--
-- Name: dashboard_health_open_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dashboard_health_open_idx ON control.dashboard_health_issues USING btree (dashboard_id, status, severity);


--
-- Name: dashboard_query_runs_dashboard_created_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dashboard_query_runs_dashboard_created_idx ON control.dashboard_query_runs USING btree (dashboard_id, created_at DESC);


--
-- Name: dashboards_lifecycle_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dashboards_lifecycle_updated_idx ON control.dashboards USING btree (lifecycle, updated_at DESC);


--
-- Name: dashboards_owner_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dashboards_owner_updated_idx ON control.dashboards USING btree (owner_id, updated_at DESC);


--
-- Name: dashboards_workspace_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dashboards_workspace_updated_idx ON control.dashboards USING btree (ontology_id, updated_at DESC);


--
-- Name: data_source_assets_path_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX data_source_assets_path_idx ON control.data_source_assets USING btree (data_source_id, full_path);


--
-- Name: data_source_test_results_latest_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX data_source_test_results_latest_idx ON control.data_source_test_results USING btree (data_source_id, tested_at DESC);


--
-- Name: data_sources_owner_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX data_sources_owner_idx ON control.data_sources USING btree (owner_id) WHERE (deleted_at IS NULL);


--
-- Name: data_sources_status_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX data_sources_status_updated_idx ON control.data_sources USING btree (connection_status, updated_at DESC) WHERE (deleted_at IS NULL);


--
-- Name: data_sources_type_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX data_sources_type_idx ON control.data_sources USING btree (source_type) WHERE (deleted_at IS NULL);


--
-- Name: dataset_materialization_rows_correlation_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dataset_materialization_rows_correlation_idx ON control.dataset_materialization_rows USING btree (dataset_id, correlation_id, event_id);


--
-- Name: dataset_object_import_jobs_queue_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dataset_object_import_jobs_queue_idx ON control.dataset_object_import_jobs USING btree (created_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: dataset_object_import_staging_scan_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dataset_object_import_staging_scan_idx ON control.dataset_object_import_staging USING btree (job_id, row_number);


--
-- Name: dataset_rows_body_gin_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX dataset_rows_body_gin_idx ON control.dataset_rows USING gin (body);


--
-- Name: datasets_ontology_api_id_key; Type: INDEX; Schema: control; Owner: -
--

CREATE UNIQUE INDEX datasets_ontology_api_id_key ON control.datasets USING btree (ontology_id, api_id);


--
-- Name: datasets_pipeline_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX datasets_pipeline_idx ON control.datasets USING btree (pipeline_id, updated_at DESC);


--
-- Name: idx_agent_conversations_ontology_updated; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX idx_agent_conversations_ontology_updated ON control.agent_conversations USING btree (ontology_id, updated_at DESC);


--
-- Name: idx_agent_messages_conversation_created; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX idx_agent_messages_conversation_created ON control.agent_messages USING btree (conversation_id, created_at, id);


--
-- Name: object_instance_bulk_idempotency_expiry_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX object_instance_bulk_idempotency_expiry_idx ON control.object_instance_bulk_idempotency USING btree (expires_at);


--
-- Name: object_instance_outbox_correlation_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX object_instance_outbox_correlation_idx ON control.object_instance_outbox USING btree (correlation_id, status, created_at);


--
-- Name: object_instance_outbox_pending_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX object_instance_outbox_pending_idx ON control.object_instance_outbox USING btree (next_attempt_at, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: ontology_resource_versions_resource_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX ontology_resource_versions_resource_idx ON control.ontology_resource_versions USING btree (resource_id, version DESC);


--
-- Name: ontology_resources_kind_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX ontology_resources_kind_updated_idx ON control.ontology_resources USING btree (kind, updated_at DESC);


--
-- Name: ontology_resources_ontology_kind_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX ontology_resources_ontology_kind_idx ON control.ontology_resources USING btree (ontology_id, kind, updated_at DESC);


--
-- Name: pipeline_dependencies_resource_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_dependencies_resource_idx ON control.pipeline_dependencies USING btree (dependency_type, resource_id);


--
-- Name: pipeline_object_materialization_rows_scan_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_object_materialization_rows_scan_idx ON control.pipeline_object_materialization_rows USING btree (run_id, output_node_id, object_id, event_id);


--
-- Name: pipeline_object_materializations_correlation_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_object_materializations_correlation_idx ON control.pipeline_object_materializations USING btree (correlation_id, status);


--
-- Name: pipeline_preview_active_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_preview_active_idx ON control.pipeline_preview_runs USING btree (status, expires_at) WHERE ((status)::text = ANY (ARRAY[('SUBMITTED'::character varying)::text, ('RUNNING'::character varying)::text]));


--
-- Name: pipeline_runs_flink_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_runs_flink_idx ON control.pipeline_runs USING btree (flink_job_id) WHERE (flink_job_id IS NOT NULL);


--
-- Name: pipeline_runs_pipeline_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_runs_pipeline_idx ON control.pipeline_runs USING btree (pipeline_id, started_at DESC);


--
-- Name: pipeline_workloads_expiry_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipeline_workloads_expiry_idx ON control.pipeline_workloads USING btree (expires_at) WHERE ((status)::text <> 'DELETED'::text);


--
-- Name: pipelines_list_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX pipelines_list_idx ON control.pipelines USING btree (lifecycle, run_status, updated_at DESC);


--
-- Name: pipelines_workspace_name_active_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE UNIQUE INDEX pipelines_workspace_name_active_idx ON control.pipelines USING btree (ontology_id, normalized_name) WHERE (archived_at IS NULL);


--
-- Name: projection_fk_relations_target_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX projection_fk_relations_target_idx ON control.projection_fk_relations USING btree (ontology_id, target_object_type, target_object_id, status);


--
-- Name: projection_ledger_entity_sequence_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX projection_ledger_entity_sequence_idx ON control.projection_ledger USING btree (ontology_id, entity_key, projection_sequence DESC);


--
-- Name: projection_ledger_status_updated_idx; Type: INDEX; Schema: control; Owner: -
--

CREATE INDEX projection_ledger_status_updated_idx ON control.projection_ledger USING btree (status, updated_at);


--
-- Name: action_executions action_executions_action_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_action_id_fkey FOREIGN KEY (action_id) REFERENCES control.ontology_resources(id);


--
-- Name: action_executions action_executions_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: action_executions action_executions_preview_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_executions
    ADD CONSTRAINT action_executions_preview_id_fkey FOREIGN KEY (preview_id) REFERENCES control.action_previews(id);


--
-- Name: action_mutation_outbox action_mutation_outbox_execution_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_mutation_outbox
    ADD CONSTRAINT action_mutation_outbox_execution_id_fkey FOREIGN KEY (execution_id) REFERENCES control.action_executions(id) ON DELETE CASCADE;


--
-- Name: action_parameters action_parameters_action_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_parameters
    ADD CONSTRAINT action_parameters_action_version_id_fkey FOREIGN KEY (action_version_id) REFERENCES control.action_type_versions(version_id);


--
-- Name: action_previews action_previews_action_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_previews
    ADD CONSTRAINT action_previews_action_id_fkey FOREIGN KEY (action_id) REFERENCES control.ontology_resources(id);


--
-- Name: action_previews action_previews_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_previews
    ADD CONSTRAINT action_previews_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: action_type_versions action_type_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_type_versions
    ADD CONSTRAINT action_type_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.action_types(resource_id);


--
-- Name: action_type_versions action_type_versions_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_type_versions
    ADD CONSTRAINT action_type_versions_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: action_types action_types_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_types
    ADD CONSTRAINT action_types_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: action_types action_types_target_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.action_types
    ADD CONSTRAINT action_types_target_object_type_id_fkey FOREIGN KEY (target_object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: agent_conversations agent_conversations_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.agent_conversations
    ADD CONSTRAINT agent_conversations_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: agent_messages agent_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.agent_messages
    ADD CONSTRAINT agent_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES control.agent_conversations(id) ON DELETE CASCADE;


--
-- Name: dashboard_data_sources dashboard_data_sources_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_data_sources dashboard_data_sources_dataset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES control.datasets(id);


--
-- Name: dashboard_data_sources dashboard_data_sources_draft_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE;


--
-- Name: dashboard_data_sources dashboard_data_sources_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: dashboard_data_sources dashboard_data_sources_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_data_sources
    ADD CONSTRAINT dashboard_data_sources_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_dependencies dashboard_dependencies_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_dependencies
    ADD CONSTRAINT dashboard_dependencies_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_dependencies dashboard_dependencies_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_dependencies
    ADD CONSTRAINT dashboard_dependencies_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_drafts dashboard_drafts_base_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_drafts
    ADD CONSTRAINT dashboard_drafts_base_version_id_fkey FOREIGN KEY (base_version_id) REFERENCES control.dashboard_versions(id);


--
-- Name: dashboard_drafts dashboard_drafts_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_drafts
    ADD CONSTRAINT dashboard_drafts_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_edit_locks dashboard_edit_locks_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_edit_locks
    ADD CONSTRAINT dashboard_edit_locks_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id) ON DELETE CASCADE;


--
-- Name: dashboard_favorites dashboard_favorites_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_favorites
    ADD CONSTRAINT dashboard_favorites_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id) ON DELETE CASCADE;


--
-- Name: dashboard_filter_bindings dashboard_filter_bindings_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_bindings
    ADD CONSTRAINT dashboard_filter_bindings_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_filter_bindings dashboard_filter_bindings_draft_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_bindings
    ADD CONSTRAINT dashboard_filter_bindings_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE;


--
-- Name: dashboard_filter_bindings dashboard_filter_bindings_property_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_bindings
    ADD CONSTRAINT dashboard_filter_bindings_property_id_fkey FOREIGN KEY (property_id) REFERENCES control.properties(id);


--
-- Name: dashboard_filter_bindings dashboard_filter_bindings_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_bindings
    ADD CONSTRAINT dashboard_filter_bindings_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_filter_variables dashboard_filter_variables_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_filter_variables dashboard_filter_variables_draft_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE;


--
-- Name: dashboard_filter_variables dashboard_filter_variables_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_filter_variables
    ADD CONSTRAINT dashboard_filter_variables_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_health_issues dashboard_health_issues_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_health_issues
    ADD CONSTRAINT dashboard_health_issues_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id) ON DELETE CASCADE;


--
-- Name: dashboard_health_issues dashboard_health_issues_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_health_issues
    ADD CONSTRAINT dashboard_health_issues_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_pages dashboard_pages_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_pages dashboard_pages_draft_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE;


--
-- Name: dashboard_pages dashboard_pages_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_pages
    ADD CONSTRAINT dashboard_pages_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboard_permissions dashboard_permissions_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_permissions
    ADD CONSTRAINT dashboard_permissions_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id) ON DELETE CASCADE;


--
-- Name: dashboard_query_plans dashboard_query_plans_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_plans
    ADD CONSTRAINT dashboard_query_plans_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_query_plans dashboard_query_plans_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_plans
    ADD CONSTRAINT dashboard_query_plans_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id);


--
-- Name: dashboard_query_runs dashboard_query_runs_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_runs
    ADD CONSTRAINT dashboard_query_runs_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_query_runs dashboard_query_runs_plan_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_runs
    ADD CONSTRAINT dashboard_query_runs_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES control.dashboard_query_plans(id);


--
-- Name: dashboard_query_runs dashboard_query_runs_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_query_runs
    ADD CONSTRAINT dashboard_query_runs_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id);


--
-- Name: dashboard_versions dashboard_versions_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_versions
    ADD CONSTRAINT dashboard_versions_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_widgets dashboard_widgets_dashboard_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_dashboard_id_fkey FOREIGN KEY (dashboard_id) REFERENCES control.dashboards(id);


--
-- Name: dashboard_widgets dashboard_widgets_draft_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_draft_id_fkey FOREIGN KEY (draft_id) REFERENCES control.dashboard_drafts(id) ON DELETE CASCADE;


--
-- Name: dashboard_widgets dashboard_widgets_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboard_widgets
    ADD CONSTRAINT dashboard_widgets_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.dashboard_versions(id) ON DELETE CASCADE;


--
-- Name: dashboards dashboards_active_draft_fk; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboards
    ADD CONSTRAINT dashboards_active_draft_fk FOREIGN KEY (active_draft_id) REFERENCES control.dashboard_drafts(id);


--
-- Name: dashboards dashboards_current_version_fk; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboards
    ADD CONSTRAINT dashboards_current_version_fk FOREIGN KEY (current_version_id) REFERENCES control.dashboard_versions(id);


--
-- Name: dashboards dashboards_workspace_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dashboards
    ADD CONSTRAINT dashboards_workspace_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: data_source_asset_fields data_source_asset_fields_asset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_asset_fields
    ADD CONSTRAINT data_source_asset_fields_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES control.data_source_assets(id) ON DELETE CASCADE;


--
-- Name: data_source_assets data_source_assets_data_source_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_assets
    ADD CONSTRAINT data_source_assets_data_source_id_fkey FOREIGN KEY (data_source_id) REFERENCES control.data_sources(id) ON DELETE CASCADE;


--
-- Name: data_source_discovery_runs data_source_discovery_runs_data_source_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_discovery_runs
    ADD CONSTRAINT data_source_discovery_runs_data_source_id_fkey FOREIGN KEY (data_source_id) REFERENCES control.data_sources(id) ON DELETE CASCADE;


--
-- Name: data_source_test_results data_source_test_results_data_source_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_source_test_results
    ADD CONSTRAINT data_source_test_results_data_source_id_fkey FOREIGN KEY (data_source_id) REFERENCES control.data_sources(id) ON DELETE CASCADE;


--
-- Name: data_sources data_sources_secret_ref_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_sources
    ADD CONSTRAINT data_sources_secret_ref_fkey FOREIGN KEY (secret_ref) REFERENCES control.connection_secrets(id);


--
-- Name: data_sources data_sources_workspace_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.data_sources
    ADD CONSTRAINT data_sources_workspace_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: dataset_materialization_rows dataset_materialization_rows_dataset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_materialization_rows
    ADD CONSTRAINT dataset_materialization_rows_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES control.datasets(id) ON DELETE CASCADE;


--
-- Name: dataset_materialization_rows dataset_materialization_rows_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_materialization_rows
    ADD CONSTRAINT dataset_materialization_rows_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: dataset_object_import_errors dataset_object_import_errors_job_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_errors
    ADD CONSTRAINT dataset_object_import_errors_job_id_fkey FOREIGN KEY (job_id) REFERENCES control.dataset_object_import_jobs(id) ON DELETE CASCADE;


--
-- Name: dataset_object_import_jobs dataset_object_import_jobs_dataset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_jobs
    ADD CONSTRAINT dataset_object_import_jobs_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES control.datasets(id) ON DELETE CASCADE;


--
-- Name: dataset_object_import_jobs dataset_object_import_jobs_mapping_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_jobs
    ADD CONSTRAINT dataset_object_import_jobs_mapping_id_fkey FOREIGN KEY (mapping_id) REFERENCES control.dataset_object_mappings(id) ON DELETE CASCADE;


--
-- Name: dataset_object_import_jobs dataset_object_import_jobs_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_jobs
    ADD CONSTRAINT dataset_object_import_jobs_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: dataset_object_import_jobs dataset_object_import_jobs_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_jobs
    ADD CONSTRAINT dataset_object_import_jobs_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: dataset_object_import_staging dataset_object_import_staging_job_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_import_staging
    ADD CONSTRAINT dataset_object_import_staging_job_id_fkey FOREIGN KEY (job_id) REFERENCES control.dataset_object_import_jobs(id) ON DELETE CASCADE;


--
-- Name: dataset_object_mappings dataset_object_mappings_dataset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_mappings
    ADD CONSTRAINT dataset_object_mappings_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES control.datasets(id) ON DELETE CASCADE;


--
-- Name: dataset_object_mappings dataset_object_mappings_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_mappings
    ADD CONSTRAINT dataset_object_mappings_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: dataset_object_mappings dataset_object_mappings_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_object_mappings
    ADD CONSTRAINT dataset_object_mappings_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: dataset_rows dataset_rows_dataset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.dataset_rows
    ADD CONSTRAINT dataset_rows_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES control.datasets(id) ON DELETE CASCADE;


--
-- Name: datasets datasets_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.datasets
    ADD CONSTRAINT datasets_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id);


--
-- Name: datasets datasets_workspace_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.datasets
    ADD CONSTRAINT datasets_workspace_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: function_parameters function_parameters_function_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_parameters
    ADD CONSTRAINT function_parameters_function_version_id_fkey FOREIGN KEY (function_version_id) REFERENCES control.function_type_versions(version_id);


--
-- Name: function_type_versions function_type_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_type_versions
    ADD CONSTRAINT function_type_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.function_types(resource_id);


--
-- Name: function_type_versions function_type_versions_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_type_versions
    ADD CONSTRAINT function_type_versions_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: function_types function_types_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.function_types
    ADD CONSTRAINT function_types_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: index_rebuild_jobs index_rebuild_jobs_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.index_rebuild_jobs
    ADD CONSTRAINT index_rebuild_jobs_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: interface_implementations interface_implementations_interface_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_interface_version_id_fkey FOREIGN KEY (interface_version_id) REFERENCES control.interface_versions(version_id);


--
-- Name: interface_implementations interface_implementations_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: interface_implementations interface_implementations_property_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_property_id_fkey FOREIGN KEY (property_id) REFERENCES control.properties(id);


--
-- Name: interface_implementations interface_implementations_slot_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_implementations
    ADD CONSTRAINT interface_implementations_slot_id_fkey FOREIGN KEY (slot_id) REFERENCES control.interface_slots(id);


--
-- Name: interface_slots interface_slots_interface_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_slots
    ADD CONSTRAINT interface_slots_interface_version_id_fkey FOREIGN KEY (interface_version_id) REFERENCES control.interface_versions(version_id);


--
-- Name: interface_versions interface_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_versions
    ADD CONSTRAINT interface_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: interface_versions interface_versions_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.interface_versions
    ADD CONSTRAINT interface_versions_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: link_type_versions link_type_versions_left_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_left_object_type_id_fkey FOREIGN KEY (left_object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: link_type_versions link_type_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: link_type_versions link_type_versions_right_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_right_object_type_id_fkey FOREIGN KEY (right_object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: link_type_versions link_type_versions_source_property_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_source_property_id_fkey FOREIGN KEY (source_property_id) REFERENCES control.properties(id);


--
-- Name: link_type_versions link_type_versions_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.link_type_versions
    ADD CONSTRAINT link_type_versions_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: object_instance_bulk_idempotency object_instance_bulk_idempotency_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_bulk_idempotency
    ADD CONSTRAINT object_instance_bulk_idempotency_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_bulk_idempotency object_instance_bulk_idempotency_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_bulk_idempotency
    ADD CONSTRAINT object_instance_bulk_idempotency_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_instance_idempotency object_instance_idempotency_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_idempotency
    ADD CONSTRAINT object_instance_idempotency_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_idempotency object_instance_idempotency_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_idempotency
    ADD CONSTRAINT object_instance_idempotency_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_instance_outbox object_instance_outbox_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_outbox
    ADD CONSTRAINT object_instance_outbox_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_outbox object_instance_outbox_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_outbox
    ADD CONSTRAINT object_instance_outbox_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_instance_projection_state object_instance_projection_state_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_projection_state
    ADD CONSTRAINT object_instance_projection_state_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_projection_state object_instance_projection_state_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_projection_state
    ADD CONSTRAINT object_instance_projection_state_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_instance_reconciliation_differences object_instance_reconciliation_differences_job_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_reconciliation_differences
    ADD CONSTRAINT object_instance_reconciliation_differences_job_id_fkey FOREIGN KEY (job_id) REFERENCES control.object_instance_reconciliation_jobs(id) ON DELETE CASCADE;


--
-- Name: object_instance_reconciliation_jobs object_instance_reconciliation_jobs_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_reconciliation_jobs
    ADD CONSTRAINT object_instance_reconciliation_jobs_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_reconciliation_jobs object_instance_reconciliation_jobs_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_reconciliation_jobs
    ADD CONSTRAINT object_instance_reconciliation_jobs_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_instance_table_registry object_instance_table_registry_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_table_registry
    ADD CONSTRAINT object_instance_table_registry_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: object_instance_table_registry object_instance_table_registry_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_instance_table_registry
    ADD CONSTRAINT object_instance_table_registry_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: object_properties object_properties_ontology_id_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_properties
    ADD CONSTRAINT object_properties_ontology_id_type_id_fkey FOREIGN KEY (ontology_id, type_id) REFERENCES control.object_types(ontology_id, type_id) ON DELETE CASCADE;


--
-- Name: object_type_versions object_type_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_type_versions
    ADD CONSTRAINT object_type_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: object_type_versions object_type_versions_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_type_versions
    ADD CONSTRAINT object_type_versions_version_id_fkey FOREIGN KEY (version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: object_types object_types_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.object_types
    ADD CONSTRAINT object_types_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: ontology_health_issues ontology_health_issues_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_health_issues
    ADD CONSTRAINT ontology_health_issues_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: ontology_health_issues ontology_health_issues_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_health_issues
    ADD CONSTRAINT ontology_health_issues_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: ontology_mappings ontology_mappings_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_mappings
    ADD CONSTRAINT ontology_mappings_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id);


--
-- Name: ontology_mappings ontology_mappings_property_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_mappings
    ADD CONSTRAINT ontology_mappings_property_id_fkey FOREIGN KEY (property_id) REFERENCES control.properties(id);


--
-- Name: ontology_mappings ontology_mappings_resource_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_mappings
    ADD CONSTRAINT ontology_mappings_resource_version_id_fkey FOREIGN KEY (resource_version_id) REFERENCES control.ontology_resource_versions(id);


--
-- Name: ontology_resource_versions ontology_resource_versions_resource_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resource_versions
    ADD CONSTRAINT ontology_resource_versions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES control.ontology_resources(id);


--
-- Name: ontology_resources ontology_resources_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.ontology_resources
    ADD CONSTRAINT ontology_resources_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: pipeline_checkpoints pipeline_checkpoints_pipeline_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_checkpoints
    ADD CONSTRAINT pipeline_checkpoints_pipeline_run_id_fkey FOREIGN KEY (pipeline_run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_dependencies pipeline_dependencies_pipeline_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_dependencies
    ADD CONSTRAINT pipeline_dependencies_pipeline_version_id_fkey FOREIGN KEY (pipeline_version_id) REFERENCES control.pipeline_versions(id) ON DELETE CASCADE;


--
-- Name: pipeline_drafts pipeline_drafts_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_drafts
    ADD CONSTRAINT pipeline_drafts_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_materialization_rows pipeline_object_materialization_rows_run_id_output_node_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materialization_rows
    ADD CONSTRAINT pipeline_object_materialization_rows_run_id_output_node_id_fkey FOREIGN KEY (run_id, output_node_id) REFERENCES control.pipeline_object_materializations(run_id, output_node_id) ON DELETE CASCADE;


--
-- Name: pipeline_object_materializations pipeline_object_materializations_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materializations
    ADD CONSTRAINT pipeline_object_materializations_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_materializations pipeline_object_materializations_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materializations
    ADD CONSTRAINT pipeline_object_materializations_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_materializations pipeline_object_materializations_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materializations
    ADD CONSTRAINT pipeline_object_materializations_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_materializations pipeline_object_materializations_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_materializations
    ADD CONSTRAINT pipeline_object_materializations_run_id_fkey FOREIGN KEY (run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_membership_stage pipeline_object_membership_stage_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_membership_stage
    ADD CONSTRAINT pipeline_object_membership_stage_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: pipeline_object_membership_stage pipeline_object_membership_stage_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_membership_stage
    ADD CONSTRAINT pipeline_object_membership_stage_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_membership_stage pipeline_object_membership_stage_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_membership_stage
    ADD CONSTRAINT pipeline_object_membership_stage_run_id_fkey FOREIGN KEY (run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_memberships pipeline_object_memberships_last_seen_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_memberships
    ADD CONSTRAINT pipeline_object_memberships_last_seen_run_id_fkey FOREIGN KEY (last_seen_run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_object_memberships pipeline_object_memberships_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_memberships
    ADD CONSTRAINT pipeline_object_memberships_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: pipeline_object_memberships pipeline_object_memberships_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_object_memberships
    ADD CONSTRAINT pipeline_object_memberships_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_preview_runs pipeline_preview_runs_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_preview_runs
    ADD CONSTRAINT pipeline_preview_runs_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_run_events pipeline_run_events_pipeline_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_events
    ADD CONSTRAINT pipeline_run_events_pipeline_run_id_fkey FOREIGN KEY (pipeline_run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_run_stages pipeline_run_stages_pipeline_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_run_stages
    ADD CONSTRAINT pipeline_run_stages_pipeline_run_id_fkey FOREIGN KEY (pipeline_run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: pipeline_runs pipeline_runs_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_runs
    ADD CONSTRAINT pipeline_runs_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id);


--
-- Name: pipeline_runs pipeline_runs_pipeline_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_runs
    ADD CONSTRAINT pipeline_runs_pipeline_version_id_fkey FOREIGN KEY (pipeline_version_id) REFERENCES control.pipeline_versions(id);


--
-- Name: pipeline_runs pipeline_runs_retry_of_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_runs
    ADD CONSTRAINT pipeline_runs_retry_of_fkey FOREIGN KEY (retry_of) REFERENCES control.pipeline_runs(id);


--
-- Name: pipeline_schedules pipeline_schedules_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_schedules
    ADD CONSTRAINT pipeline_schedules_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id) ON DELETE CASCADE;


--
-- Name: pipeline_versions pipeline_versions_pipeline_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipeline_versions
    ADD CONSTRAINT pipeline_versions_pipeline_id_fkey FOREIGN KEY (pipeline_id) REFERENCES control.pipelines(id);


--
-- Name: pipelines pipelines_data_source_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipelines
    ADD CONSTRAINT pipelines_data_source_id_fkey FOREIGN KEY (data_source_id) REFERENCES control.data_sources(id);


--
-- Name: pipelines pipelines_source_asset_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipelines
    ADD CONSTRAINT pipelines_source_asset_id_fkey FOREIGN KEY (source_asset_id) REFERENCES control.data_source_assets(id);


--
-- Name: pipelines pipelines_workspace_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.pipelines
    ADD CONSTRAINT pipelines_workspace_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: projection_batches projection_batches_pipeline_run_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_batches
    ADD CONSTRAINT projection_batches_pipeline_run_id_fkey FOREIGN KEY (pipeline_run_id) REFERENCES control.pipeline_runs(id) ON DELETE CASCADE;


--
-- Name: projection_failures projection_failures_event_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_failures
    ADD CONSTRAINT projection_failures_event_id_fkey FOREIGN KEY (event_id) REFERENCES control.projection_ledger(event_id);


--
-- Name: projection_fk_relations projection_fk_relations_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_fk_relations
    ADD CONSTRAINT projection_fk_relations_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: projection_ledger projection_ledger_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_ledger
    ADD CONSTRAINT projection_ledger_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: projection_operations projection_operations_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.projection_operations
    ADD CONSTRAINT projection_operations_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: properties properties_object_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.properties
    ADD CONSTRAINT properties_object_type_id_fkey FOREIGN KEY (object_type_id) REFERENCES control.ontology_resources(id);


--
-- Name: property_versions property_versions_object_type_version_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.property_versions
    ADD CONSTRAINT property_versions_object_type_version_id_fkey FOREIGN KEY (object_type_version_id) REFERENCES control.object_type_versions(version_id);


--
-- Name: property_versions property_versions_property_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.property_versions
    ADD CONSTRAINT property_versions_property_id_fkey FOREIGN KEY (property_id) REFERENCES control.properties(id);


--
-- Name: relation_projection_state relation_projection_state_ontology_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.relation_projection_state
    ADD CONSTRAINT relation_projection_state_ontology_id_fkey FOREIGN KEY (ontology_id) REFERENCES control.ontologies(id);


--
-- Name: relation_types relation_types_ontology_id_source_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.relation_types
    ADD CONSTRAINT relation_types_ontology_id_source_type_id_fkey FOREIGN KEY (ontology_id, source_type_id) REFERENCES control.object_types(ontology_id, type_id) ON DELETE CASCADE;


--
-- Name: relation_types relation_types_ontology_id_target_type_id_fkey; Type: FK CONSTRAINT; Schema: control; Owner: -
--

ALTER TABLE ONLY control.relation_types
    ADD CONSTRAINT relation_types_ontology_id_target_type_id_fkey FOREIGN KEY (ontology_id, target_type_id) REFERENCES control.object_types(ontology_id, type_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

-- Built-in ontologies for local development.
INSERT INTO control.ontologies (id, api_name, display_name, description, icon, color)
VALUES
    ('00000000-0000-0000-0000-00000000a001', 'token_consumption', 'Token 消耗', '人员、小组与模型 Token 使用分析', 'fund', '#3157d5'),
    ('00000000-0000-0000-0000-00000000a002', 'iot_operations', 'IoT 设备运营', '设备、传感器、遥测与告警管理', 'gateway', '#0f8f6f');
