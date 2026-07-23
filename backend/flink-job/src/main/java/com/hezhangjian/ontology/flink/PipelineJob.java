package com.hezhangjian.ontology.flink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public final class PipelineJob {
    private PipelineJob() { }

    public static void main(String[] args) throws Exception {
        ParameterTool parameters = ParameterTool.fromArgs(args);
        String coreUrl = parameters.getRequired("core-url");
        if (parameters.has("preview-id")) {
            preview(coreUrl, UUID.fromString(parameters.getRequired("preview-id")));
            return;
        }
        execute(coreUrl, UUID.fromString(parameters.getRequired("run-id")));
    }

    private static void execute(String coreUrl, UUID runId) throws Exception {
        RuntimeClient.RuntimeSpec safeSpec = new RuntimeClient(coreUrl, runId).exchange();
        safeSpec.credential().clear();
        safeSpec.sources().values().forEach(source -> source.credential().clear());

        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(integer(safeSpec.runtime().get("parallelism"), 1));
        int restartAttempts = integer(safeSpec.runtime().get("restartAttempts"), 3);
        environment.setRestartStrategy(RestartStrategies.fixedDelayRestart(restartAttempts, 2_000));
        if ("STREAMING".equals(safeSpec.sourceConfig().get("pipelineMode"))) {
            environment.enableCheckpointing(longValue(safeSpec.runtime().get("checkpointIntervalMs"), 60_000));
        }

        Map<String, DataStream<String>> sources = sources(environment, coreUrl, runId, false,
                Long.MAX_VALUE, safeSpec);
        DataStream<String> events = PipelineDagCompiler.compile(sources, safeSpec.graph(),
                safeSpec.sourceConfig(), safeSpec.correlationId(), null);
        events.addSink(new RuntimePulsarSink(coreUrl, runId, safeSpec.targetTopic()))
                .name("platform-pulsar").setParallelism(1);
        environment.execute("ontology-pipeline-" + runId);
    }

    private static void preview(String coreUrl, UUID previewId) throws Exception {
        RuntimeClient.PreviewSpec preview = new RuntimeClient(coreUrl, previewId).exchangePreview(previewId);
        RuntimeClient.RuntimeSpec safeSpec = preview.runtime();
        safeSpec.credential().clear();
        safeSpec.sources().values().forEach(source -> source.credential().clear());
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setParallelism(integer(safeSpec.runtime().get("parallelism"), 1));
        environment.setRestartStrategy(RestartStrategies.noRestart());
        Map<String, DataStream<String>> sources = sources(environment, coreUrl, previewId, true,
                preview.limit(), safeSpec);
        DataStream<String> results = PipelineDagCompiler.compile(sources, safeSpec.graph(),
                safeSpec.sourceConfig(), safeSpec.correlationId(), preview.nodeId());
        results.addSink(new RuntimePreviewSink(coreUrl, previewId, preview.limit()))
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
            StreamExecutionEnvironment environment, String coreUrl, UUID workloadId,
            boolean preview, long limit, RuntimeClient.RuntimeSpec spec) {
        Map<String, DataStream<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> node : maps(spec.graph().get("nodes"))) {
            if (!"SOURCE".equals(String.valueOf(node.get("type")))) continue;
            String nodeId = String.valueOf(node.get("id"));
            DataStream<String> rows = environment
                    .addSource(new RuntimeSource(coreUrl, workloadId, preview, limit, nodeId))
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
