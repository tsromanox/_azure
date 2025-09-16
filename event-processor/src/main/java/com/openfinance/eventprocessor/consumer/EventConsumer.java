package com.openfinance.eventprocessor.consumer;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.models.*;

import com.openfinance.eventprocessor.metrics.CustomMetrics;
import com.openfinance.eventprocessor.service.EventProcessingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final EventProcessorClient processorClient;
    private final EventProcessorClientBuilder clientBuilder;
    private final EventProcessingService processingService;
    private final CustomMetrics metrics;

    // Configurações
    @Value("${eventhub.source.name}")
    private String sourceEventHub;

    @Value("${eventhub.source.partition-count:32}")
    private int partitionCount;

    @Value("${processing.batch.enabled:true}")
    private boolean batchProcessingEnabled;

    @Value("${processing.batch.max-size:100}")
    private int maxBatchSize;

    @Value("${processing.batch.max-wait-ms:5000}")
    private long maxBatchWaitMs;

    @Value("${processing.checkpoint.interval:100}")
    private int checkpointInterval;

    @Value("${processing.error.max-retries:3}")
    private int maxRetries;

    @Value("${processing.parallel.enabled:true}")
    private boolean parallelProcessingEnabled;

    // Estado
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // Estatísticas por partição
    private final Map<String, PartitionStatistics> partitionStats = new ConcurrentHashMap<>();

    // Contadores globais
    private final AtomicLong totalEventsReceived = new AtomicLong(0);
    private final AtomicLong totalEventsProcessed = new AtomicLong(0);
    private final AtomicLong totalEventsFailed = new AtomicLong(0);
    private final AtomicLong totalCheckpoints = new AtomicLong(0);

    // Executor para processamento paralelo
    private final ExecutorService processingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Monitoramento
    private ScheduledExecutorService monitoringExecutor;
    private Instant startTime;

    @PostConstruct
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            try {
                log.info("════════════════════════════════════════");
                log.info("Iniciando Event Consumer");
                log.info("  Event Hub: {}", sourceEventHub);
                log.info("  Partições: {}", partitionCount);
                log.info("  Batch: {} (max: {})", batchProcessingEnabled, maxBatchSize);
                log.info("  Processamento paralelo: {}", parallelProcessingEnabled);
                log.info("════════════════════════════════════════");

                startTime = Instant.now();

                // Configura callbacks do processador
                EventProcessorClient client = clientBuilder
                        .processEvent(this::processEvent)
                        .processEventBatch(this::processEventBatch, maxBatchSize, Duration.ofMillis(maxBatchWaitMs))
                        .processError(this::processError)
                        .processPartitionInitialization(this::initializePartition)
                        .processPartitionClose(this::closePartition)
                        .buildEventProcessorClient();

                // Inicia o processamento
                client.start();

                // Inicia monitoramento
                startMonitoring();

                log.info("✅ Event Consumer iniciado com sucesso");

                // Registra shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "consumer-shutdown"));

            } catch (Exception e) {
                log.error("Falha ao iniciar Event Consumer", e);
                isRunning.set(false);
                throw new RuntimeException("Falha na inicialização do consumer", e);
            }
        } else {
            log.warn("Event Consumer já está em execução");
        }
    }

    @PreDestroy
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("════════════════════════════════════════");
            log.info("Parando Event Consumer...");

            try {
                // Para de aceitar novos eventos
                isHealthy.set(false);

                // Para o cliente
                if (processorClient != null) {
                    processorClient.stop();
                    log.info("Processor client parado");
                }

                // Para o executor de processamento
                processingExecutor.shutdown();
                if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    processingExecutor.shutdownNow();
                    log.warn("Forçando shutdown do executor de processamento");
                }

                // Para monitoramento
                if (monitoringExecutor != null) {
                    monitoringExecutor.shutdown();
                    monitoringExecutor.awaitTermination(2, TimeUnit.SECONDS);
                }

                // Log de estatísticas finais
                logFinalStatistics();

                shutdownLatch.countDown();

                log.info("✅ Event Consumer parado com sucesso");
                log.info("════════════════════════════════════════");

            } catch (Exception e) {
                log.error("Erro durante shutdown do consumer", e);
            }
        }
    }

    /**
     * Processa um único evento
     */
    private void processEvent(EventContext eventContext) {
        if (!isRunning.get()) {
            return;
        }

        EventData event = eventContext.getEventData();
        PartitionContext partition = eventContext.getPartitionContext();

        String partitionId = partition.getPartitionId();
        PartitionStatistics stats = getOrCreatePartitionStats(partitionId);

        totalEventsReceived.incrementAndGet();
        stats.eventsReceived.incrementAndGet();

        try {
            // Log de debug para acompanhamento
            if (totalEventsReceived.get() % 1000 == 0) {
                log.debug("Recebidos {} eventos totais", totalEventsReceived.get());
            }

            // Adiciona contexto de rastreamento
            String traceId = event.getProperties() != null ?
                    (String) event.getProperties().get("TraceId") : null;
            if (traceId == null) {
                traceId = java.util.UUID.randomUUID().toString();
            }

            org.slf4j.MDC.put("TraceId", traceId);
            org.slf4j.MDC.put("PartitionId", partitionId);
            org.slf4j.MDC.put("SequenceNumber", String.valueOf(event.getSequenceNumber()));

            // Processa o evento
            long startTime = System.currentTimeMillis();
            processingService.processEvent(event);
            long duration = System.currentTimeMillis() - startTime;

            // Atualiza estatísticas
            stats.eventsProcessed.incrementAndGet();
            stats.totalProcessingTime.addAndGet(duration);
            totalEventsProcessed.incrementAndGet();

            // Checkpoint periódico
            if (shouldCheckpoint(stats)) {
                performCheckpoint(eventContext, stats);
            }

            // Atualiza métricas
            metrics.incrementProcessedEvents();
            metrics.recordEventProcessingTime(duration);

        } catch (Exception e) {
            handleProcessingError(event, partition, e, stats);
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    /**
     * Processa batch de eventos
     */
    private void processEventBatch(EventBatchContext batchContext) {
        if (!isRunning.get() || !batchProcessingEnabled) {
            // Se batch desabilitado, processa individualmente
            for (EventContext event : batchContext.getEvents()) {
                processEvent(event);
            }
            return;
        }

        PartitionContext partition = batchContext.getPartitionContext();
        String partitionId = partition.getPartitionId();
        List<EventData> events = batchContext.getEvents().stream()
                .map(EventContext::getEventData)
                .toList();

        if (events.isEmpty()) {
            return;
        }

        log.debug("Processando batch de {} eventos da partição {}",
                events.size(), partitionId);

        PartitionStatistics stats = getOrCreatePartitionStats(partitionId);
        stats.eventsReceived.addAndGet(events.size());
        totalEventsReceived.addAndGet(events.size());

        long batchStartTime = System.currentTimeMillis();

        try {
            if (parallelProcessingEnabled) {
                processBatchParallel(events, partition, stats);
            } else {
                processBatchSequential(events, partition, stats);
            }

            // Checkpoint após processar o batch
            batchContext.updateCheckpoint();
            stats.lastCheckpoint = Instant.now();
            totalCheckpoints.incrementAndGet();

            long batchDuration = System.currentTimeMillis() - batchStartTime;
            metrics.recordBatchProcessingTime(batchDuration);

            log.debug("Batch processado com sucesso em {}ms", batchDuration);

        } catch (Exception e) {
            log.error("Erro ao processar batch", e);
            metrics.incrementProcessingErrors();
            // Não faz checkpoint para reprocessar
        }
    }

    /**
     * Trata erros de processamento
     */
    private void processError(ErrorContext errorContext) {
        PartitionContext partition = errorContext.getPartitionContext();
        Throwable error = errorContext.getThrowable();

        String partitionId = partition != null ? partition.getPartitionId() : "unknown";
        PartitionStatistics stats = getOrCreatePartitionStats(partitionId);
        stats.errors.incrementAndGet();

        log.error("Erro no processamento - Partição: {}, Erro: {}",
                partitionId, error.getMessage(), error);

        metrics.incrementProcessingErrors();

        // Determina severidade do erro
        if (isCriticalError(error)) {
            log.error("ERRO CRÍTICO detectado - considerando parada do consumer");
            isHealthy.set(false);

            if (shouldStopOnError(error)) {
                log.error("Parando consumer devido a erro crítico");
                stop();
            }
        }
    }

    /**
     * Inicializa partição
     */
    private void initializePartition(InitializationContext context) {
        String partitionId = context.getPartitionContext().getPartitionId();

        log.info("Inicializando partição {}", partitionId);

        PartitionStatistics stats = new PartitionStatistics(partitionId);
        partitionStats.put(partitionId, stats);

        // Atualiza métricas
        metrics.updateActivePartitions(partitionStats.size());
    }

    /**
     * Fecha partição
     */
    private void closePartition(CloseContext context) {
        String partitionId = context.getPartitionContext().getPartitionId();
        CloseReason reason = context.getCloseReason();

        log.info("Fechando partição {} - Razão: {}", partitionId, reason);

        PartitionStatistics stats = partitionStats.get(partitionId);
        if (stats != null) {
            logPartitionStatistics(stats);
        }

        // Atualiza métricas
        metrics.updateActivePartitions(partitionStats.size());
    }

    // ==================== Métodos Auxiliares ====================

    private void processBatchParallel(List<EventData> events, PartitionContext partition,
                                      PartitionStatistics stats) {
        List<CompletableFuture<Void>> futures = events.stream()
                .map(event -> CompletableFuture.runAsync(() -> {
                    try {
                        processingService.processEvent(event);
                        stats.eventsProcessed.incrementAndGet();
                        totalEventsProcessed.incrementAndGet();
                    } catch (Exception e) {
                        stats.eventsFailed.incrementAndGet();
                        totalEventsFailed.incrementAndGet();
                        log.error("Erro processando evento no batch", e);
                    }
                }, processingExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void processBatchSequential(List<EventData> events, PartitionContext partition,
                                        PartitionStatistics stats) {
        for (EventData event : events) {
            try {
                processingService.processEvent(event);
                stats.eventsProcessed.incrementAndGet();
                totalEventsProcessed.incrementAndGet();
            } catch (Exception e) {
                stats.eventsFailed.incrementAndGet();
                totalEventsFailed.incrementAndGet();
                log.error("Erro processando evento no batch", e);
            }
        }
    }

    private void handleProcessingError(EventData event, PartitionContext partition,
                                       Exception error, PartitionStatistics stats) {
        stats.eventsFailed.incrementAndGet();
        totalEventsFailed.incrementAndGet();

        log.error("Erro ao processar evento. Partição: {}, Sequência: {}",
                partition.getPartitionId(), event.getSequenceNumber(), error);

        metrics.incrementProcessingErrors();

        // Aqui você pode implementar lógica adicional de retry ou DLQ
    }

    private boolean shouldCheckpoint(PartitionStatistics stats) {
        long processed = stats.eventsProcessed.get();
        long lastCheckpointed = stats.eventsCheckpointed.get();

        return (processed - lastCheckpointed) >= checkpointInterval;
    }

    private void performCheckpoint(EventContext context, PartitionStatistics stats) {
        try {
            context.updateCheckpoint();
            stats.eventsCheckpointed.set(stats.eventsProcessed.get());
            stats.lastCheckpoint = Instant.now();
            totalCheckpoints.incrementAndGet();

            log.debug("Checkpoint realizado para partição {}",
                    context.getPartitionContext().getPartitionId());

        } catch (Exception e) {
            log.error("Erro ao realizar checkpoint", e);
        }
    }

    private PartitionStatistics getOrCreatePartitionStats(String partitionId) {
        return partitionStats.computeIfAbsent(partitionId, PartitionStatistics::new);
    }

    private boolean isCriticalError(Throwable error) {
        return error instanceof OutOfMemoryError ||
                error instanceof StackOverflowError ||
                error instanceof NoClassDefFoundError ||
                error instanceof SecurityException;
    }

    private boolean shouldStopOnError(Throwable error) {
        return error instanceof OutOfMemoryError ||
                error instanceof SecurityException;
    }

    private void startMonitoring() {
        monitoringExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().name("consumer-monitor").unstarted(r);
            t.setDaemon(true);
            return t;
        });

        // Monitora estatísticas a cada 30 segundos
        monitoringExecutor.scheduleAtFixedRate(this::logStatistics, 30, 30, TimeUnit.SECONDS);

        // Verifica health a cada 10 segundos
        monitoringExecutor.scheduleAtFixedRate(this::checkHealth, 10, 10, TimeUnit.SECONDS);
    }

    private void logStatistics() {
        long received = totalEventsReceived.get();
        long processed = totalEventsProcessed.get();
        long failed = totalEventsFailed.get();

        double successRate = received > 0 ?
                (double) processed / received * 100 : 0;

        log.info("📊 Estatísticas do Consumer:");
        log.info("  Eventos recebidos: {}", received);
        log.info("  Eventos processados: {}", processed);
        log.info("  Eventos com falha: {}", failed);
        log.info("  Taxa de sucesso: {:.2f}%", successRate);
        log.info("  Checkpoints: {}", totalCheckpoints.get());
        log.info("  Partições ativas: {}", partitionStats.size());

        // Calcula lag total
        long totalLag = calculateTotalLag();
        if (totalLag > 0) {
            log.info("  Lag total estimado: {} eventos", totalLag);
            metrics.updateConsumerLag(totalLag);
        }
    }

    private void checkHealth() {
        boolean healthy = true;
        String reason = null;

        // Verifica taxa de erro
        long total = totalEventsReceived.get();
        long failed = totalEventsFailed.get();
        if (total > 100 && failed > total * 0.1) { // Mais de 10% de erro
            healthy = false;
            reason = "Taxa de erro alta: " + (failed * 100.0 / total) + "%";
        }

        // Verifica se está processando
        if (isRunning.get() && totalEventsReceived.get() == 0 &&
                Duration.between(startTime, Instant.now()).toMinutes() > 5) {
            healthy = false;
            reason = "Nenhum evento recebido há mais de 5 minutos";
        }

        boolean previousHealth = isHealthy.getAndSet(healthy);
        if (previousHealth != healthy) {
            if (healthy) {
                log.info("✅ Consumer recuperado - status: HEALTHY");
            } else {
                log.error("❌ Consumer degradado - status: UNHEALTHY - Razão: {}", reason);
            }
        }
    }

    private long calculateTotalLag() {
        // Este é um cálculo estimado baseado nas estatísticas locais
        // Para um lag preciso, seria necessário consultar o Event Hub
        return partitionStats.values().stream()
                .mapToLong(stats -> {
                    long received = stats.eventsReceived.get();
                    long processed = stats.eventsProcessed.get();
                    return Math.max(0, received - processed);
                })
                .sum();
    }

    private void logPartitionStatistics(PartitionStatistics stats) {
        long avgProcessingTime = stats.eventsProcessed.get() > 0 ?
                stats.totalProcessingTime.get() / stats.eventsProcessed.get() : 0;

        log.info("Estatísticas da partição {}:", stats.partitionId);
        log.info("  Recebidos: {}", stats.eventsReceived.get());
        log.info("  Processados: {}", stats.eventsProcessed.get());
        log.info("  Falhas: {}", stats.eventsFailed.get());
        log.info("  Tempo médio: {}ms", avgProcessingTime);
        log.info("  Último checkpoint: {}", stats.lastCheckpoint);
    }

    private void logFinalStatistics() {
        Duration uptime = Duration.between(startTime, Instant.now());

        log.info("════════════════════════════════════════");
        log.info("Estatísticas Finais do Consumer:");
        log.info("  Tempo de execução: {}", formatDuration(uptime));
        log.info("  Total de eventos recebidos: {}", totalEventsReceived.get());
        log.info("  Total de eventos processados: {}", totalEventsProcessed.get());
        log.info("  Total de eventos com falha: {}", totalEventsFailed.get());
        log.info("  Taxa de sucesso: {:.2f}%",
                totalEventsReceived.get() > 0 ?
                        (double) totalEventsProcessed.get() / totalEventsReceived.get() * 100 : 0);
        log.info("  Total de checkpoints: {}", totalCheckpoints.get());
        log.info("  Taxa de processamento: {:.2f} eventos/segundo",
                totalEventsProcessed.get() / (double) uptime.toSeconds());
        log.info("════════════════════════════════════════");
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    // ==================== Classes Internas ====================

    private static class PartitionStatistics {
        final String partitionId;
        final AtomicLong eventsReceived = new AtomicLong(0);
        final AtomicLong eventsProcessed = new AtomicLong(0);
        final AtomicLong eventsFailed = new AtomicLong(0);
        final AtomicLong eventsCheckpointed = new AtomicLong(0);
        final AtomicLong totalProcessingTime = new AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);
        volatile Instant lastCheckpoint = Instant.now();
        volatile Instant startTime = Instant.now();

        PartitionStatistics(String partitionId) {
            this.partitionId = partitionId;
        }
    }

    /**
     * Retorna status do consumer
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isHealthy() {
        return isHealthy.get();
    }

    /**
     * Aguarda shutdown completo
     */
    public void awaitShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        shutdownLatch.await(timeout, unit);
    }

    /**
     * Retorna estatísticas atuais
     */
    public ConsumerStatistics getStatistics() {
        return new ConsumerStatistics(
                totalEventsReceived.get(),
                totalEventsProcessed.get(),
                totalEventsFailed.get(),
                totalCheckpoints.get(),
                partitionStats.size(),
                isRunning.get(),
                isHealthy.get()
        );
    }

    public record ConsumerStatistics(
            long eventsReceived,
            long eventsProcessed,
            long eventsFailed,
            long checkpoints,
            int activePartitions,
            boolean running,
            boolean healthy
    ) {}
}
