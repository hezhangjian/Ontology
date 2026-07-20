package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PipelineEventStreamService {
    private static final List<String> TERMINAL = List.of("CANCELLED", "COMPLETED", "DEGRADED", "FAILED", "STOPPED");
    private final PipelineService pipelines;
    private final TaskScheduler scheduler;

    public PipelineEventStreamService(PipelineService pipelines, TaskScheduler scheduler) {
        this.pipelines = pipelines;
        this.scheduler = scheduler;
    }

    public SseEmitter stream(UUID runId, long afterSequence) {
        pipelines.runById(runId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        AtomicLong cursor = new AtomicLong(Math.max(0, afterSequence));
        AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        Runnable cancel = () -> {
            ScheduledFuture<?> scheduled = future.get();
            if (scheduled != null) scheduled.cancel(false);
        };
        emitter.onCompletion(cancel);
        emitter.onError(ignored -> cancel.run());
        emitter.onTimeout(() -> {
            cancel.run();
            emitter.complete();
        });
        future.set(scheduler.scheduleAtFixedRate(() -> send(runId, cursor, emitter, cancel), Duration.ofSeconds(1)));
        return emitter;
    }

    private void send(UUID runId, AtomicLong cursor, SseEmitter emitter, Runnable cancel) {
        try {
            for (RunEvent event : pipelines.events(runId)) {
                if (event.sequence() <= cursor.get()) continue;
                emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("pipeline-run-event").data(event));
                cursor.set(event.sequence());
            }
            PipelineRun run = pipelines.runById(runId);
            if (TERMINAL.contains(run.status())) {
                emitter.send(SseEmitter.event().name("pipeline-run-terminal").data(run));
                cancel.run();
                emitter.complete();
            }
        } catch (IOException cause) {
            cancel.run();
            emitter.completeWithError(cause);
        } catch (RuntimeException cause) {
            cancel.run();
            emitter.completeWithError(cause);
        }
    }
}
