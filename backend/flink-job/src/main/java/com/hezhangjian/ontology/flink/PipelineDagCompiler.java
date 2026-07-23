package com.hezhangjian.ontology.flink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.CoGroupFunction;
import org.apache.flink.api.common.functions.RichFilterFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

final class PipelineDagCompiler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OUTPUT_TYPES =
            Set.of("DATASET_OUTPUT", "LINK_OUTPUT", "OBJECT_OUTPUT");

    private PipelineDagCompiler() { }

    static DataStream<String> assignWatermarks(DataStream<String> source, Map<String, Object> runtime) {
        String field = text(runtime.get("eventTimeField"));
        long delay = positive(runtime.get("watermarkDelayMs"), 5_000);
        return source.assignTimestampsAndWatermarks(
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofMillis(delay))
                        .withTimestampAssigner(new JsonTimestampAssigner(field)))
                .name("event-time-watermarks");
    }

    static DataStream<String> compile(DataStream<String> source, Map<String, Object> graph,
                                      Map<String, Object> sourceConfig, String correlationId,
                                      String previewNodeId) {
        Map<String, DataStream<String>> sources = new LinkedHashMap<>();
        for (Map<String, Object> node : maps(graph.get("nodes"))) {
            if ("SOURCE".equals(text(node.get("type")))) sources.put(text(node.get("id")), source);
        }
        return compile(sources, graph, sourceConfig, correlationId, previewNodeId);
    }

    static DataStream<String> compile(Map<String, DataStream<String>> sourceStreams,
                                      Map<String, Object> graph,
                                      Map<String, Object> sourceConfig, String correlationId,
                                      String previewNodeId) {
        List<Map<String, Object>> nodes = maps(graph.get("nodes"));
        List<Map<String, Object>> edges = maps(graph.get("edges"));
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        nodes.forEach(node -> nodesById.put(text(node.get("id")), node));
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        edges.forEach(edge -> {
            String from = text(edge.get("source"));
            String to = text(edge.get("target"));
            incoming.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from);
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
        });

        Map<String, DataStream<String>> streams = new LinkedHashMap<>();
        for (String nodeId : topological(nodesById.keySet(), incoming, outgoing)) {
            Map<String, Object> node = nodesById.get(nodeId);
            String type = text(node.get("type"));
            if ("SOURCE".equals(type)) {
                DataStream<String> source = sourceStreams.get(nodeId);
                if (source == null) throw new IllegalArgumentException("Runtime source is missing: " + nodeId);
                streams.put(nodeId, source);
                continue;
            }
            List<String> parentIds = incoming.getOrDefault(nodeId, List.of());
            List<DataStream<String>> parents = parentIds.stream().map(streams::get)
                    .filter(Objects::nonNull).toList();
            if (parents.isEmpty()) throw new IllegalArgumentException("Pipeline node has no input: " + nodeId);
            Map<String, Object> config = map(node.get("config"));
            DataStream<String> compiled;
            if ("JOIN".equals(type)) {
                if (parents.size() != 2) throw new IllegalArgumentException("JOIN requires exactly two inputs: " + nodeId);
                compiled = join(parents.get(0), parents.get(1), config);
            } else {
                DataStream<String> input = union(parents);
                compiled = switch (type) {
                    case "AGGREGATE" -> {
                        Map<String, Object> window = config;
                        if (parentIds.size() == 1) {
                            Map<String, Object> parent = nodesById.get(parentIds.get(0));
                            if (parent != null && "WINDOW".equals(text(parent.get("type")))) {
                                List<String> grandparents = incoming.getOrDefault(parentIds.get(0), List.of());
                                if (grandparents.size() == 1 && streams.get(grandparents.get(0)) != null) {
                                    input = streams.get(grandparents.get(0));
                                    window = merge(map(parent.get("config")), config);
                                }
                            }
                        }
                        yield aggregate(input, window);
                    }
                    case "DEDUPLICATE" -> deduplicate(input, config);
                    case "LINK_OUTPUT", "OBJECT_OUTPUT" ->
                            deduplicateProjectionEvents(transform(input, node, sourceConfig, correlationId));
                    case "WINDOW" -> window(input, config);
                    default -> transform(input, node, sourceConfig, correlationId);
                };
            }
            if (compiled instanceof SingleOutputStreamOperator<?> operator) {
                @SuppressWarnings("unchecked")
                SingleOutputStreamOperator<String> named = (SingleOutputStreamOperator<String>) operator;
                named.name(type.toLowerCase(Locale.ROOT) + "-" + nodeId).uid("pipeline-node-" + nodeId);
            }
            streams.put(nodeId, compiled);
        }

        if (previewNodeId != null) {
            DataStream<String> preview = streams.get(previewNodeId);
            if (preview == null) throw new IllegalArgumentException("Preview node is not in the compiled DAG: " + previewNodeId);
            return preview;
        }
        List<DataStream<String>> outputs = nodes.stream()
                .filter(node -> OUTPUT_TYPES.contains(text(node.get("type"))))
                .map(node -> streams.get(text(node.get("id")))).filter(Objects::nonNull).toList();
        if (outputs.isEmpty()) throw new IllegalArgumentException("Pipeline DAG has no output");
        return union(outputs);
    }

    private static DataStream<String> transform(DataStream<String> input, Map<String, Object> node,
                                                Map<String, Object> sourceConfig, String correlationId) {
        String nodeId = text(node.get("id"));
        Map<String, Object> miniGraph = Map.of(
                "edges", List.of(Map.of("id", "edge-" + nodeId, "source", "source", "target", nodeId)),
                "nodes", List.of(Map.of("id", "source", "type", "SOURCE", "config", Map.of()), node));
        return input.flatMap(new PipelineTransform(miniGraph, sourceConfig, correlationId, nodeId));
    }

    private static DataStream<String> deduplicate(DataStream<String> input, Map<String, Object> config) {
        List<String> keys = strings(config.get("keys"));
        if (keys.isEmpty()) throw new IllegalArgumentException("DEDUPLICATE requires keys");
        long ttlMs = positive(config.get("stateTtlMs"), 86_400_000);
        return input.keyBy(new JsonKeySelector(keys)).filter(new DeduplicateFilter(ttlMs));
    }

    private static DataStream<String> deduplicateProjectionEvents(DataStream<String> input) {
        return input.keyBy(new JsonKeySelector(List.of("event_id")))
                .filter(new DeduplicateFilter(86_400_000));
    }

    private static DataStream<String> join(DataStream<String> left, DataStream<String> right,
                                           Map<String, Object> config) {
        String leftKey = required(config, "leftKey");
        String rightKey = required(config, "rightKey");
        long windowMs = positive(config.get("windowSizeMs"), 60_000);
        return left.coGroup(right)
                .where(new JsonKeySelector(List.of(leftKey)))
                .equalTo(new JsonKeySelector(List.of(rightKey)))
                .window(TumblingEventTimeWindows.of(Time.milliseconds(windowMs)))
                .apply(new JsonJoin(config));
    }

    private static DataStream<String> aggregate(DataStream<String> input, Map<String, Object> config) {
        List<String> groupBy = strings(config.get("groupBy"));
        KeyedStream<String, String> keyed = input.keyBy(new JsonKeySelector(groupBy));
        WindowedStream<String, String, TimeWindow> windowed = windowed(keyed, config);
        return windowed.aggregate(new JsonAggregate(config), new AddWindowMetadata());
    }

    private static DataStream<String> window(DataStream<String> input, Map<String, Object> config) {
        List<String> groupBy = strings(config.get("groupBy"));
        KeyedStream<String, String> keyed = input.keyBy(new JsonKeySelector(groupBy));
        return windowed(keyed, config).process(new WindowRows());
    }

    private static WindowedStream<String, String, TimeWindow> windowed(
            KeyedStream<String, String> keyed, Map<String, Object> config) {
        long size = positive(config.get("windowSizeMs"), 60_000);
        return switch (upper(config.get("windowType"), "TUMBLING")) {
            case "SESSION" -> keyed.window(EventTimeSessionWindows.withGap(Time.milliseconds(
                    positive(config.get("sessionGapMs"), size))));
            case "SLIDING" -> keyed.window(SlidingEventTimeWindows.of(
                    Time.milliseconds(size),
                    Time.milliseconds(positive(config.get("slideMs"), Math.max(1_000, size / 2)))));
            default -> keyed.window(TumblingEventTimeWindows.of(Time.milliseconds(size)));
        };
    }

    @SafeVarargs
    private static DataStream<String> union(List<DataStream<String>> streams, DataStream<String>... ignored) {
        DataStream<String> result = streams.get(0);
        if (streams.size() > 1) result = result.union(streams.subList(1, streams.size()).toArray(DataStream[]::new));
        return result;
    }

    private static List<String> topological(Set<String> ids, Map<String, List<String>> incoming,
                                            Map<String, List<String>> outgoing) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        ids.forEach(id -> degree.put(id, incoming.getOrDefault(id, List.of()).size()));
        ArrayDeque<String> queue = new ArrayDeque<>();
        degree.entrySet().stream().filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey).sorted().forEach(queue::add);
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            result.add(current);
            for (String target : outgoing.getOrDefault(current, List.of())) {
                int next = degree.computeIfPresent(target, (ignored, value) -> value - 1);
                if (next == 0) queue.add(target);
            }
        }
        if (result.size() != ids.size()) throw new IllegalArgumentException("Pipeline graph contains a cycle");
        return result;
    }

    private static final class JsonTimestampAssigner implements SerializableTimestampAssigner<String> {
        private final String field;
        private transient ObjectMapper json;

        private JsonTimestampAssigner(String field) {
            this.field = field;
        }

        @Override
        public long extractTimestamp(String element, long recordTimestamp) {
            if (field.isBlank()) return System.currentTimeMillis();
            try {
                if (json == null) json = new ObjectMapper();
                Object value = json.readValue(element, new TypeReference<Map<String, Object>>() { }).get(field);
                if (value instanceof Number number) return number.longValue();
                return Instant.parse(String.valueOf(value)).toEpochMilli();
            } catch (Exception ignored) {
                return System.currentTimeMillis();
            }
        }
    }

    private static final class JsonKeySelector implements KeySelector<String, String> {
        private final List<String> fields;
        private transient ObjectMapper json;

        private JsonKeySelector(List<String> fields) {
            this.fields = List.copyOf(fields);
        }

        @Override
        public String getKey(String value) throws Exception {
            if (fields.isEmpty()) return "__all__";
            if (json == null) json = new ObjectMapper();
            Map<String, Object> row = json.readValue(value, new TypeReference<>() { });
            List<Object> key = fields.stream().map(row::get).toList();
            return json.writeValueAsString(key);
        }
    }

    private static final class DeduplicateFilter extends RichFilterFunction<String> {
        private final long ttlMs;
        private transient ValueState<Boolean> seen;

        private DeduplicateFilter(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        @Override
        public void open(Configuration parameters) {
            ValueStateDescriptor<Boolean> descriptor = new ValueStateDescriptor<>("seen", Boolean.class);
            descriptor.enableTimeToLive(StateTtlConfig.newBuilder(Duration.ofMillis(ttlMs))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired).build());
            seen = getRuntimeContext().getState(descriptor);
        }

        @Override
        public boolean filter(String value) throws Exception {
            if (Boolean.TRUE.equals(seen.value())) return false;
            seen.update(true);
            return true;
        }
    }

    private static final class JsonJoin implements CoGroupFunction<String, String, String> {
        private final Map<String, Object> config;
        private transient ObjectMapper json;

        private JsonJoin(Map<String, Object> config) {
            this.config = new LinkedHashMap<>(config);
        }

        @Override
        public void coGroup(Iterable<String> leftValues, Iterable<String> rightValues,
                            Collector<String> collector) throws Exception {
            if (json == null) json = new ObjectMapper();
            List<Map<String, Object>> left = decode(leftValues, json);
            List<Map<String, Object>> right = decode(rightValues, json);
            String joinType = upper(config.get("joinType"), "INNER");
            String prefix = textDefault(config.get("lookupPrefix"), "right_");
            if (!left.isEmpty() && !right.isEmpty()) {
                for (Map<String, Object> leftRow : left) {
                    for (Map<String, Object> rightRow : right) {
                        collector.collect(json.writeValueAsString(mergeRows(leftRow, rightRow, prefix)));
                    }
                }
            } else if (right.isEmpty() && Set.of("FULL", "LEFT").contains(joinType)) {
                for (Map<String, Object> row : left) collector.collect(json.writeValueAsString(row));
            } else if (left.isEmpty() && Set.of("FULL", "RIGHT").contains(joinType)) {
                for (Map<String, Object> row : right) collector.collect(json.writeValueAsString(row));
            }
        }
    }

    private static final class AggregateAccumulator implements Serializable {
        private Map<String, Object> group = new LinkedHashMap<>();
        private long count;
        private Map<String, BigDecimal> sums = new LinkedHashMap<>();
        private Map<String, BigDecimal> minimums = new LinkedHashMap<>();
        private Map<String, BigDecimal> maximums = new LinkedHashMap<>();
        private Map<String, Set<String>> distinct = new LinkedHashMap<>();
    }

    private static final class JsonAggregate implements AggregateFunction<String, AggregateAccumulator, String> {
        private final Map<String, Object> config;
        private transient ObjectMapper json;

        private JsonAggregate(Map<String, Object> config) {
            this.config = new LinkedHashMap<>(config);
        }

        @Override
        public AggregateAccumulator createAccumulator() {
            return new AggregateAccumulator();
        }

        @Override
        public AggregateAccumulator add(String value, AggregateAccumulator accumulator) {
            try {
                if (json == null) json = new ObjectMapper();
                Map<String, Object> row = json.readValue(value, new TypeReference<>() { });
                strings(config.get("groupBy")).forEach(field -> accumulator.group.put(field, row.get(field)));
                accumulator.count++;
                for (Map<String, Object> metric : metrics(config)) {
                    String field = text(metric.get("field"));
                    String name = metricName(metric);
                    Object raw = row.get(field);
                    BigDecimal number = decimal(raw);
                    if (number != null) {
                        accumulator.sums.merge(name, number, BigDecimal::add);
                        accumulator.minimums.merge(name, number, BigDecimal::min);
                        accumulator.maximums.merge(name, number, BigDecimal::max);
                    }
                    if (raw != null) accumulator.distinct.computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                            .add(String.valueOf(raw));
                }
                return accumulator;
            } catch (Exception failure) {
                throw new IllegalArgumentException("AGGREGATE row is invalid", failure);
            }
        }

        @Override
        public String getResult(AggregateAccumulator accumulator) {
            try {
                if (json == null) json = new ObjectMapper();
                Map<String, Object> result = new LinkedHashMap<>(accumulator.group);
                for (Map<String, Object> metric : metrics(config)) {
                    String operation = upper(metric.get("operation"),
                            upper(metric.get("aggregation"), "COUNT"));
                    String name = metricName(metric);
                    Object value = switch (operation) {
                        case "AVG" -> accumulator.count == 0 ? BigDecimal.ZERO
                                : accumulator.sums.getOrDefault(name, BigDecimal.ZERO)
                                .divide(BigDecimal.valueOf(accumulator.count), 6, java.math.RoundingMode.HALF_UP);
                        case "COUNT_DISTINCT", "DISTINCT_COUNT" ->
                                accumulator.distinct.getOrDefault(name, Set.of()).size();
                        case "MAX" -> accumulator.maximums.get(name);
                        case "MIN" -> accumulator.minimums.get(name);
                        case "SUM" -> accumulator.sums.getOrDefault(name, BigDecimal.ZERO);
                        default -> accumulator.count;
                    };
                    result.put(name, value);
                }
                return json.writeValueAsString(result);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot encode AGGREGATE result", failure);
            }
        }

        @Override
        public AggregateAccumulator merge(AggregateAccumulator left, AggregateAccumulator right) {
            if (left.group.isEmpty()) left.group.putAll(right.group);
            left.count += right.count;
            right.sums.forEach((key, value) -> left.sums.merge(key, value, BigDecimal::add));
            right.minimums.forEach((key, value) -> left.minimums.merge(key, value, BigDecimal::min));
            right.maximums.forEach((key, value) -> left.maximums.merge(key, value, BigDecimal::max));
            right.distinct.forEach((key, values) ->
                    left.distinct.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values));
            return left;
        }
    }

    private static final class AddWindowMetadata
            extends ProcessWindowFunction<String, String, String, TimeWindow> {
        @Override
        public void process(String key, Context context, Iterable<String> values,
                            Collector<String> collector) throws Exception {
            Map<String, Object> row = JSON.readValue(values.iterator().next(), new TypeReference<>() { });
            row.put("_window_start", Instant.ofEpochMilli(context.window().getStart()).toString());
            row.put("_window_end", Instant.ofEpochMilli(context.window().getEnd()).toString());
            collector.collect(JSON.writeValueAsString(row));
        }
    }

    private static final class WindowRows extends ProcessWindowFunction<String, String, String, TimeWindow> {
        @Override
        public void process(String key, Context context, Iterable<String> values,
                            Collector<String> collector) throws Exception {
            for (String value : values) {
                Map<String, Object> row = JSON.readValue(value, new TypeReference<>() { });
                row.put("_window_start", Instant.ofEpochMilli(context.window().getStart()).toString());
                row.put("_window_end", Instant.ofEpochMilli(context.window().getEnd()).toString());
                collector.collect(JSON.writeValueAsString(row));
            }
        }
    }

    private static List<Map<String, Object>> decode(Iterable<String> values, ObjectMapper json) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String value : values) result.add(json.readValue(value, new TypeReference<>() { }));
        return result;
    }

    private static Map<String, Object> mergeRows(Map<String, Object> left, Map<String, Object> right,
                                                 String prefix) {
        Map<String, Object> result = new LinkedHashMap<>(left);
        right.forEach((key, value) -> result.put(result.containsKey(key) ? prefix + key : key, value));
        return result;
    }

    private static List<Map<String, Object>> metrics(Map<String, Object> config) {
        List<Map<String, Object>> configured = maps(config.get("aggregations"));
        if (!configured.isEmpty()) return configured;
        return List.of(Map.of(
                "field", text(config.get("field")),
                "operation", upper(config.get("aggregation"), "COUNT"),
                "outputField", textDefault(config.get("outputField"),
                        textDefault(config.get("aggregation"), "count").toLowerCase(Locale.ROOT))));
    }

    private static String metricName(Map<String, Object> metric) {
        return textDefault(metric.get("outputField"),
                textDefault(metric.get("as"), textDefault(metric.get("operation"), "count").toLowerCase(Locale.ROOT)));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Map<String, Object> merge(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> result = new LinkedHashMap<>(first);
        result.putAll(second);
        return result;
    }

    private static String required(Map<String, Object> values, String key) {
        String value = text(values.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static long positive(Object value, long fallback) {
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        try {
            long number = Long.parseLong(text(value));
            return number > 0 ? number : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textDefault(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private static String upper(Object value, String fallback) {
        return textDefault(value, fallback).toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof Collection<?> raw
                ? raw.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof Collection<?> values
                ? values.stream().map(PipelineDagCompiler::text).filter(item -> !item.isBlank()).toList()
                : List.of();
    }
}
