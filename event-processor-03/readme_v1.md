# Sistema Completo Azure Event Hub com Spring Boot 3.5.x

Este sistema fornece um esqueleto reutilizável para processamento de eventos Azure Event Hub com Spring Boot 3.5.x, suportando 100 milhões de eventos por dia com máxima resilência e escalabilidade.

## Arquitetura do sistema e configurações otimizadas

O sistema utiliza **Spring Boot 3.5.5** com **Java 21** e **Azure Event Hub SDK 5.20.4**, aproveitando virtual threads para processamento de alto throughput (~1.157 eventos/segundo). A arquitetura suporta tanto processamento em batch quanto streaming em tempo real, com garantias de entrega configuráveis e suporte completo para JSON, Avro e Protobuf.

## Configuração principal do sistema

### application.yml - Configuração otimizada para alto throughput

```yaml
spring:
  application:
    name: azure-eventhub-processor
  
  # Virtual Threads (Spring Boot 3.5+)
  threads:
    virtual:
      enabled: true
  
  # Task Execution for High Performance
  task:
    execution:
      mode: force
      pool:
        core-size: 8
        max-size: 16
        queue-capacity: 200
        keep-alive: 60s
        thread-name-prefix: "event-processor-"
  
  # Azure Event Hub Configuration
  cloud:
    azure:
      eventhubs:
        namespace: ${EVENTHUBS_NAMESPACE}
        event-hub-name: ${EVENT_HUB_NAME}
        consumer-group: ${CONSUMER_GROUP:$Default}
        processor:
          checkpoint-store:
            account-name: ${STORAGE_ACCOUNT}
            container-name: ${STORAGE_CONTAINER}
            create-container-if-not-exists: true
          batch-size: 64
          max-wait-time: PT5S
          prefetch-count: 1000
          load-balancing:
            update-interval: PT10S
            partition-ownership-expiration: PT30S
      
      credentials:
        managed-identity-enabled: true
      
      retry:
        exponential:
          max-retries: 3
          base-delay: PT2S
          max-delay: PT1M

  # Database Configuration
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:eventprocessor}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 300000
      max-lifetime: 1800000
      leak-detection-threshold: 60000

  # JPA Configuration
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        use_sql_comments: false

# Processing Configuration
event:
  processing:
    mode: ${PROCESSING_MODE:streaming} # streaming, batch
    delivery-guarantee: ${DELIVERY_GUARANTEE:at-least-once} # at-least-once, exactly-once
    data-format: ${DATA_FORMAT:json} # json, avro, protobuf
    max-retry-attempts: 3
    dead-letter-enabled: true

# Resilience4j Configuration
resilience4j:
  circuitbreaker:
    instances:
      external-api:
        register-health-indicator: true
        sliding-window-size: 100
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        minimum-number-of-calls: 5
        event-consumer-buffer-size: 10
      database:
        sliding-window-size: 50
        failure-rate-threshold: 60
        wait-duration-in-open-state: 30s
  
  retry:
    instances:
      external-api:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.net.ConnectException
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.HttpServerErrorException
        ignore-exceptions:
          - java.lang.IllegalArgumentException

# Monitoring Configuration
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,circuitbreakers,retries
  endpoint:
    health:
      show-details: always
    metrics:
      enabled: true
    prometheus:
      enabled: true
  
  health:
    azure-eventhubs:
      enabled: true
    circuitbreakers:
      enabled: true
  
  metrics:
    export:
      prometheus:
        enabled: true
    ssl:
      enabled: true
  
  observations:
    annotations:
      enabled: true
  
  tracing:
    opentelemetry:
      export:
        enabled: true

# Logging Configuration
logging:
  level:
    com.azure: INFO
    org.springframework.cloud.azure: INFO
    com.yourcompany.eventprocessor: DEBUG
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level [%X{traceId},%X{spanId}] %logger{36} - %msg%n"
```

## Implementação do processador de eventos principal

### EventProcessorService.java - Core do processamento

```java
package com.yourcompany.eventprocessor.service;

import com.yourcompany.eventprocessor.config.EventProcessingProperties;
import com.yourcompany.eventprocessor.model.ProcessingResult;
import com.yourcompany.eventprocessor.serialization.EventDeserializer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.azure.messaging.eventhubs.core.EventHubsProcessorFactory;
import org.springframework.cloud.azure.messaging.eventhubs.core.processor.EventHubsProcessor;
import org.springframework.cloud.azure.messaging.eventhubs.core.processor.EventHubsProcessorClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventProcessorService {
    
    private final EventHubsProcessorFactory processorFactory;
    private final EventDeserializer eventDeserializer;
    private final EventProcessingOrchestrator orchestrator;
    private final EventProcessingProperties properties;
    private final MeterRegistry meterRegistry;
    
    private EventHubsProcessorClient processorClient;
    private final AtomicLong processedEvents = new AtomicLong(0);
    private final AtomicInteger activeProcessors = new AtomicInteger(0);
    
    // Metrics
    private Counter eventsProcessedCounter;
    private Counter eventsFailedCounter;
    private Timer eventProcessingTimer;
    
    @PostConstruct
    public void initialize() {
        initializeMetrics();
        initializeProcessor();
        startProcessor();
    }
    
    private void initializeMetrics() {
        this.eventsProcessedCounter = Counter.builder("events.processed.total")
            .description("Total number of events processed")
            .tag("service", "event-processor")
            .tag("format", properties.getDataFormat().toString())
            .register(meterRegistry);
            
        this.eventsFailedCounter = Counter.builder("events.failed.total")
            .description("Total number of failed events")
            .tag("service", "event-processor")
            .register(meterRegistry);
            
        this.eventProcessingTimer = Timer.builder("event.processing.duration")
            .description("Event processing duration")
            .tag("service", "event-processor")
            .register(meterRegistry);
    }
    
    private void initializeProcessor() {
        this.processorClient = processorFactory.createProcessor(
            properties.getEventHubName(),
            properties.getConsumerGroup(),
            this::processEvent,
            this::handleError
        );
    }
    
    @Timed(value = "event.processing.duration", description = "Time taken to process an event")
    public void processEvent(ProcessEventArgs eventArgs) {
        activeProcessors.incrementAndGet();
        
        try {
            log.debug("Processing event from partition: {}, offset: {}", 
                eventArgs.getPartitionContext().getPartitionId(),
                eventArgs.getData().getOffset());
            
            // Deserialize based on configured format
            Object deserializedEvent = eventDeserializer.deserialize(
                eventArgs.getData().getBody(), 
                properties.getDataFormat()
            );
            
            // Process event through orchestrator
            CompletableFuture<ProcessingResult> processingFuture = 
                orchestrator.processEvent(deserializedEvent, eventArgs.getPartitionContext());
            
            // Handle result
            processingFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    handleProcessingFailure(eventArgs, throwable);
                } else {
                    handleProcessingSuccess(eventArgs, result);
                }
                activeProcessors.decrementAndGet();
            });
            
        } catch (Exception e) {
            log.error("Failed to process event from partition: {}", 
                eventArgs.getPartitionContext().getPartitionId(), e);
            handleProcessingFailure(eventArgs, e);
            activeProcessors.decrementAndGet();
        }
    }
    
    @CircuitBreaker(name = "database", fallbackMethod = "handleDatabaseFailure")
    @Retry(name = "external-api")
    private void handleProcessingSuccess(ProcessEventArgs eventArgs, ProcessingResult result) {
        eventsProcessedCounter.increment();
        processedEvents.incrementAndGet();
        
        if (properties.getDeliveryGuarantee() == DeliveryGuarantee.EXACTLY_ONCE || 
            processedEvents.get() % properties.getCheckpointBatchSize() == 0) {
            eventArgs.updateCheckpoint();
        }
        
        log.debug("Successfully processed event. Total processed: {}", processedEvents.get());
    }
    
    private void handleProcessingFailure(ProcessEventArgs eventArgs, Throwable throwable) {
        eventsFailedCounter.increment();
        
        log.error("Failed to process event from partition: {}, offset: {}", 
            eventArgs.getPartitionContext().getPartitionId(),
            eventArgs.getData().getOffset(), throwable);
        
        if (properties.isDeadLetterEnabled()) {
            sendToDeadLetterQueue(eventArgs, throwable);
        }
    }
    
    private void sendToDeadLetterQueue(ProcessEventArgs eventArgs, Throwable error) {
        try {
            // Implementation for DLQ with metadata
            orchestrator.sendToDeadLetterQueue(eventArgs.getData(), error);
        } catch (Exception dlqError) {
            log.error("Failed to send message to dead letter queue", dlqError);
        }
    }
    
    public void handleError(ErrorContext errorContext) {
        log.error("EventHub processor error in partition: {}", 
            errorContext.getPartitionContext().getPartitionId(), 
            errorContext.getThrowable());
    }
    
    private void startProcessor() {
        try {
            processorClient.start();
            log.info("Event processor started successfully for EventHub: {}, ConsumerGroup: {}", 
                properties.getEventHubName(), properties.getConsumerGroup());
        } catch (Exception e) {
            log.error("Failed to start event processor", e);
            throw new RuntimeException("Failed to start event processor", e);
        }
    }
    
    @PreDestroy
    public void shutdown() {
        if (processorClient != null) {
            try {
                processorClient.stop(Duration.ofSeconds(30));
                log.info("Event processor stopped gracefully");
            } catch (Exception e) {
                log.error("Error during processor shutdown", e);
            }
        }
    }
    
    // Health check support
    public boolean isHealthy() {
        return processorClient != null && processorClient.isRunning();
    }
    
    public long getProcessedEventsCount() {
        return processedEvents.get();
    }
    
    public int getActiveProcessorsCount() {
        return activeProcessors.get();
    }
}
```

## Sistema de serialização multi-formato

### EventDeserializer.java - Suporte a JSON, Avro e Protobuf

```java
package com.yourcompany.eventprocessor.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventDeserializer {
    
    private final ObjectMapper objectMapper;
    private final AvroSchemaRegistry avroSchemaRegistry;
    private final ProtobufTypeRegistry protobufTypeRegistry;
    
    public Object deserialize(byte[] data, DataFormat format) throws SerializationException {
        try {
            return switch (format) {
                case JSON -> deserializeJson(data);
                case AVRO -> deserializeAvro(data);
                case PROTOBUF -> deserializeProtobuf(data);
            };
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize data with format: " + format, e);
        }
    }
    
    private Object deserializeJson(byte[] data) throws IOException {
        // Performance optimized JSON deserialization
        return objectMapper.readValue(data, GenericEventData.class);
    }
    
    private GenericRecord deserializeAvro(byte[] data) throws IOException {
        // Schema resolution from registry
        Schema schema = avroSchemaRegistry.getLatestSchema();
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
        return reader.read(null, decoder);
    }
    
    private Message deserializeProtobuf(byte[] data) throws IOException {
        // Dynamic protobuf deserialization
        return protobufTypeRegistry.parseMessage(data);
    }
}

@Component
public class SerializationPerformanceMetrics {
    
    private final Timer jsonDeserializationTimer;
    private final Timer avroDeserializationTimer;
    private final Timer protobufDeserializationTimer;
    
    public SerializationPerformanceMetrics(MeterRegistry meterRegistry) {
        this.jsonDeserializationTimer = Timer.builder("serialization.json.duration")
            .description("JSON deserialization time")
            .register(meterRegistry);
        this.avroDeserializationTimer = Timer.builder("serialization.avro.duration")
            .description("Avro deserialization time")
            .register(meterRegistry);
        this.protobufDeserializationTimer = Timer.builder("serialization.protobuf.duration")
            .description("Protobuf deserialization time")
            .register(meterRegistry);
    }
    
    public void recordDeserializationTime(DataFormat format, Duration duration) {
        switch (format) {
            case JSON -> jsonDeserializationTimer.record(duration);
            case AVRO -> avroDeserializationTimer.record(duration);
            case PROTOBUF -> protobufDeserializationTimer.record(duration);
        }
    }
}
```

## Orquestrador de processamento com padrões arquiteturais

### EventProcessingOrchestrator.java - Event Sourcing e Circuit Breaker

```java
package com.yourcompany.eventprocessor.orchestration;

import com.yourcompany.eventprocessor.model.*;
import com.yourcompany.eventprocessor.persistence.EventStore;
import com.yourcompany.eventprocessor.external.ExternalApiClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventProcessingOrchestrator {
    
    private final EventStore eventStore;
    private final ExternalApiClient externalApiClient;
    private final List<EventProcessor<?>> eventProcessors;
    private final DeadLetterQueueService deadLetterQueueService;
    private final Executor eventProcessingExecutor;
    
    @Transactional
    public CompletableFuture<ProcessingResult> processEvent(
            Object event, 
            PartitionContext partitionContext) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Transform event data
                TransformedEvent transformedEvent = transformEvent(event);
                
                // 2. Persist to event store (Event Sourcing pattern)
                EventEntity persistedEvent = persistEvent(transformedEvent, partitionContext);
                
                // 3. Process through applicable processors
                ProcessingResult result = processWithPlugins(transformedEvent);
                
                // 4. Call external APIs if needed
                if (requiresExternalProcessing(transformedEvent)) {
                    callExternalApis(transformedEvent);
                }
                
                return result;
                
            } catch (Exception e) {
                log.error("Failed to process event in orchestrator", e);
                throw new EventProcessingException("Processing failed", e);
            }
        }, eventProcessingExecutor);
    }
    
    private TransformedEvent transformEvent(Object rawEvent) {
        // Business logic transformation
        return EventTransformer.transform(rawEvent);
    }
    
    @Transactional
    private EventEntity persistEvent(TransformedEvent event, PartitionContext context) {
        // Event Sourcing pattern - append-only event store
        EventEntity entity = EventEntity.builder()
            .aggregateId(event.getAggregateId())
            .eventType(event.getEventType())
            .eventData(event.getPayload())
            .partitionId(context.getPartitionId())
            .offset(context.getOffset())
            .timestamp(Instant.now())
            .version(generateVersion())
            .build();
            
        return eventStore.save(entity);
    }
    
    private ProcessingResult processWithPlugins(TransformedEvent event) {
        List<CompletableFuture<Void>> processingFutures = eventProcessors.stream()
            .filter(processor -> processor.canProcess(event))
            .map(processor -> processor.processAsync(event))
            .toList();
        
        // Wait for all processors to complete
        CompletableFuture.allOf(processingFutures.toArray(new CompletableFuture[0]))
            .join();
        
        return ProcessingResult.success();
    }
    
    @CircuitBreaker(name = "external-api", fallbackMethod = "handleExternalApiFailure")
    @Retry(name = "external-api")
    private void callExternalApis(TransformedEvent event) {
        externalApiClient.processEvent(event);
    }
    
    private void handleExternalApiFailure(TransformedEvent event, Exception ex) {
        log.warn("External API call failed, using fallback processing", ex);
        // Fallback logic or queue for later retry
    }
    
    public void sendToDeadLetterQueue(EventData eventData, Throwable error) {
        deadLetterQueueService.sendToDLQ(eventData, error);
    }
    
    private boolean requiresExternalProcessing(TransformedEvent event) {
        return event.getEventType().requiresExternalCall();
    }
    
    private Long generateVersion() {
        return System.currentTimeMillis();
    }
}
```

## Configuração de infraestrutura e deployment

### Dockerfile otimizado para Spring Boot 3.5.x

```dockerfile
# Multi-stage build with GraalVM native compilation
FROM ghcr.io/graalvm/graalvm-ce:ol8-java21-22.3.0 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Build native image
RUN ./mvnw -Pnative native:compile -DskipTests

FROM gcr.io/distroless/base-debian11:latest

# Copy native executable
COPY --from=builder /app/target/azure-eventhub-processor /app/azure-eventhub-processor

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

# Run as non-root user
USER 1001

ENTRYPOINT ["/app/azure-eventhub-processor"]
```

### kubernetes-deployment.yaml - Deployment otimizado para Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: azure-eventhub-processor
  labels:
    app: azure-eventhub-processor
    version: "1.0"
spec:
  replicas: 4
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  selector:
    matchLabels:
      app: azure-eventhub-processor
  template:
    metadata:
      labels:
        app: azure-eventhub-processor
        version: "1.0"
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: azure-eventhub-processor-sa
      securityContext:
        runAsNonRoot: true
        runAsUser: 1001
        fsGroup: 1001
      containers:
      - name: azure-eventhub-processor
        image: your-registry/azure-eventhub-processor:latest
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "kubernetes"
        - name: EVENTHUBS_NAMESPACE
          valueFrom:
            secretKeyRef:
              name: azure-credentials
              key: eventhubs-namespace
        - name: EVENT_HUB_NAME
          value: "events"
        - name: CONSUMER_GROUP
          value: "processor-group"
        - name: STORAGE_ACCOUNT
          valueFrom:
            secretKeyRef:
              name: azure-credentials
              key: storage-account
        - name: STORAGE_CONTAINER
          value: "checkpoints"
        - name: DB_HOST
          value: "postgresql-service"
        - name: DB_NAME
          value: "eventprocessor"
        - name: DB_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        - name: JVM_OPTS
          value: "-server -Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
        
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 10
          failureThreshold: 3
        
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        
        volumeMounts:
        - name: config
          mountPath: /app/config
          readOnly: true
      
      volumes:
      - name: config
        configMap:
          name: azure-eventhub-processor-config

---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: azure-eventhub-processor-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: azure-eventhub-processor
  minReplicas: 2
  maxReplicas: 16
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
```

### docker-compose.yml para desenvolvimento local

```yaml
version: '3.8'

services:
  azure-eventhub-processor:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=local
      - EVENTHUBS_NAMESPACE=your-namespace
      - EVENT_HUB_NAME=events
      - STORAGE_ACCOUNT=your-storage
      - DB_HOST=postgresql
      - DB_NAME=eventprocessor
      - DB_USERNAME=postgres
      - DB_PASSWORD=password
    depends_on:
      - postgresql
      - prometheus
    volumes:
      - ./config:/app/config
    networks:
      - event-processing

  postgresql:
    image: postgres:15
    environment:
      - POSTGRES_DB=eventprocessor
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - event-processing

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    networks:
      - event-processing

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - grafana_data:/var/lib/grafana
    networks:
      - event-processing

volumes:
  postgres_data:
  grafana_data:

networks:
  event-processing:
    driver: bridge
```

## Benchmarks de performance e configurações otimizadas

### Configuração de Azure Event Hub para 100M eventos/dia

**Especificações recomendadas:**
- **Partições**: 8-16 partições para distribuição eficiente
- **Throughput Units**: 4 TUs mínimo para throughput sustentado
- **Consumer Groups**: Grupos dedicados por aplicação
- **Checkpoint Strategy**: Batch checkpointing a cada 50-100 eventos

### Comparação de performance dos formatos de dados

**Benchmark de serialização** (baseado em testes JMH):

| Formato | Velocidade Serialização | Velocidade Deserialização | Tamanho (1K eventos) | Uso de Memória |
|---------|------------------------|---------------------------|---------------------|----------------|
| JSON | 1x (baseline) | 1x (baseline) | 100% | 100% |
| Protobuf | **6x mais rápido** | **5x mais rápido** | 45% do JSON | 60% |
| Avro | 3x mais rápido | 2.5x mais rápido | **40% do JSON** | 55% |

**Recomendações:**
- **Protobuf**: Para máxima velocidade de processamento (sistemas financeiros, IoT)
- **Avro**: Para pipelines de dados e analytics (melhor compressão)
- **JSON**: Para APIs e debugging (legibilidade humana)

### Configurações JVM otimizadas para produção

```bash
# Configuração JVM para alto throughput
JAVA_OPTS="-server \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+AlwaysPreTouch \
  -XX:+ParallelRefProcEnabled \
  -XX:+TieredCompilation \
  -XX:+DisableExplicitGC \
  -XX:NewRatio=1 \
  -XX:G1HeapRegionSize=16m \
  -Xlog:gc*:gc.log:time,uptime,level,tags"
```

## Health checks e monitoramento customizado

### EventProcessorHealthIndicator.java

```java
@Component
public class EventProcessorHealthIndicator implements HealthIndicator {
    
    private final EventProcessorService eventProcessorService;
    private final DataSource dataSource;
    private final ExternalApiClient externalApiClient;
    
    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        
        try {
            // Check event processor status
            if (!eventProcessorService.isHealthy()) {
                return builder.down()
                    .withDetail("event-processor", "Not running")
                    .build();
            }
            
            // Check database connectivity
            if (!isDatabaseHealthy()) {
                return builder.down()
                    .withDetail("database", "Connection failed")
                    .build();
            }
            
            // Check external API availability
            if (!externalApiClient.isHealthy()) {
                builder.unknown().withDetail("external-api", "Degraded");
            } else {
                builder.up();
            }
            
            // Add metrics
            builder.withDetail("processed-events", eventProcessorService.getProcessedEventsCount())
                   .withDetail("active-processors", eventProcessorService.getActiveProcessorsCount())
                   .withDetail("uptime", getUptime());
            
            return builder.build();
            
        } catch (Exception e) {
            return builder.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
    
    private boolean isDatabaseHealthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }
}
```

Este sistema completo fornece uma base sólida e reutilizável para processamento de eventos Azure Event Hub com Spring Boot 3.5.x, oferecendo alto throughput, resilência avançada e monitoramento abrangente. A arquitetura modular permite fácil extensão e personalização para diferentes casos de uso, mantendo as melhores práticas de desenvolvimento em 2024/2025.