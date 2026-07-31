package com.hezhangjian.ontology.flink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.flink.util.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public final class PipelineJob {
    private PipelineJob() { }

    public static void main(String[] args) throws Exception {
        ParameterTool parameters = ParameterTool.fromArgs(args);
        UUID workloadId = UUID.fromString(parameters.getRequired("workload-id"));
        String objectKey = parameters.getRequired("workload-object");
        RuntimeClient.RuntimeSpec spec = new RuntimeClient(objectKey, workloadId).load();
        if ("PREVIEW".equals(spec.kind())) preview(objectKey, workloadId, spec);
        else execute(objectKey, workloadId, spec);
    }

    private static void execute(String objectKey, UUID runId, RuntimeClient.RuntimeSpec safeSpec) throws Exception {
        safeSpec.credential().clear();
        safeSpec.sources().values().forEach(source -> source.credential().clear());

        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(integer(safeSpec.runtime().get("parallelism"), 1));
        if ("STREAMING".equals(safeSpec.sourceConfig().get("pipelineMode"))) {
            environment.enableCheckpointing(longValue(safeSpec.runtime().get("checkpointIntervalMs"), 60_000));
        }

        Map<String, DataStream<String>> sources = sources(environment, objectKey, runId, false,
                Long.MAX_VALUE, safeSpec);
        DataStream<String> events = PipelineDagCompiler.compile(sources, safeSpec.graph(),
                safeSpec.sourceConfig(), safeSpec.correlationId(), null);
        events.addSink(new RuntimePulsarSink(
                        objectKey,
                        runId,
                        safeSpec.targetTopic(),
                        safeSpec.graph(),
                        safeSpec.sourceConfig(),
                        safeSpec.correlationId()))
                .name("platform-pulsar").setParallelism(1);
        environment.execute("ontology-pipeline-" + runId);
    }

    private static void preview(String objectKey, UUID previewId,
                                RuntimeClient.RuntimeSpec safeSpec) throws Exception {
        safeSpec.credential().clear();
        safeSpec.sources().values().forEach(source -> source.credential().clear());
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(integer(safeSpec.runtime().get("parallelism"), 1));
        Map<String, DataStream<String>> sources = sources(environment, objectKey, previewId, true,
                safeSpec.previewLimit(), safeSpec);
        DataStream<String> results = PipelineDagCompiler.compile(sources, safeSpec.graph(),
                safeSpec.sourceConfig(), safeSpec.correlationId(), safeSpec.previewNodeId());
        results.addSink(new RuntimePreviewSink(objectKey, previewId, safeSpec.previewLimit()))
                .name("bounded-preview-result").setParallelism(1);
        environment.execute("ontology-preview-" + previewId);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Map<String, DataStream<String>> sources(
            StreamExecutionEnvironment environment, String objectKey, UUID workloadId,
            boolean preview, long limit, RuntimeClient.RuntimeSpec spec) {
        Map<String, DataStream<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> node : maps(spec.graph().get("nodes"))) {
            if (!"SOURCE".equals(String.valueOf(node.get("type")))) continue;
            String nodeId = String.valueOf(node.get("id"));
            DataStream<String> rows = environment
                    .addSource(new RuntimeSource(objectKey, workloadId, preview, limit, nodeId))
                    .name("controlled-source-" + nodeId).uid("pipeline-source-" + nodeId)
                    .setParallelism(1);
            result.put(nodeId, PipelineDagCompiler.assignWatermarks(rows, spec.runtime()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> raw
                ? raw.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList() : List.of();
    }
}
