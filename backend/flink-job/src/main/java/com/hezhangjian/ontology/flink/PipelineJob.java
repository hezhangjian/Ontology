package com.hezhangjian.ontology.flink;

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

        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        int restartAttempts = integer(safeSpec.runtime().get("restartAttempts"), 3);
        environment.setRestartStrategy(RestartStrategies.fixedDelayRestart(restartAttempts, 2_000));
        if ("STREAMING".equals(safeSpec.sourceConfig().get("pipelineMode"))) {
            environment.enableCheckpointing(longValue(safeSpec.runtime().get("checkpointIntervalMs"), 60_000));
        }

        DataStream<String> rows = environment.addSource(new RuntimeSource(coreUrl, runId, false, Integer.MAX_VALUE))
                .name("controlled-source").setParallelism(1);
        DataStream<String> events = rows.flatMap(new PipelineTransform(safeSpec.graph(), safeSpec.sourceConfig(), safeSpec.correlationId()))
                .name("pipeline-ir").setParallelism(1);
        events.addSink(new RuntimePulsarSink(coreUrl, runId, safeSpec.targetTopic())).name("platform-pulsar").setParallelism(1);
        environment.execute("ontology-pipeline-" + runId);
    }

    private static void preview(String coreUrl, UUID previewId) throws Exception {
        RuntimeClient.PreviewSpec preview = new RuntimeClient(coreUrl, previewId).exchangePreview(previewId);
        RuntimeClient.RuntimeSpec safeSpec = preview.runtime();
        safeSpec.credential().clear();
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setRestartStrategy(RestartStrategies.noRestart());
        DataStream<String> rows = environment.addSource(new RuntimeSource(coreUrl, previewId, true, preview.limit()))
                .name("bounded-preview-source").setParallelism(1);
        DataStream<String> results = rows.flatMap(new PipelineTransform(safeSpec.graph(), safeSpec.sourceConfig(),
                        safeSpec.correlationId(), preview.nodeId()))
                .name("bounded-pipeline-ir").setParallelism(1);
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
}
