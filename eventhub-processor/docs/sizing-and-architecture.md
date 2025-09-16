# Sizing, Partitions, and AKS Architecture

Date: 2025-09-15

## 1) Sizing for 100M events/day

Assumptions (adjust to your real payload and traffic profile):
- Average event size: 1 KB (body+properties on the wire). If 2 KB, double bandwidth.
- Daily events: 100,000,000 ≈ 1.157e3 events/sec on average, but real systems have peaks. Assume peak 5× average (5,800 eps) or shape from historical data.
- Required ingress MB/s = events_per_sec × avg_event_size.

Event Hubs limits (Standard TU, per docs):
- 1 TU ≈ Ingress 1 MB/s or 1,000 eps, Egress 2 MB/s or 4,096 eps; 40 TUs max per namespace.
- Premium uses Processing Units (PU) with higher isolation and 90‑day retention; throughput per PU depends on partitions and workload; also higher connection limits.

Back-of-the-envelope:
- Average: 1,157 eps × 1 KB ≈ 1.13 MB/s ingress. Peak 5× → 5.6 MB/s.
- Standard tier: need 6 TUs to cover 5.6 MB/s peak ingress (round up). Egress 2 MB/s per TU → 12 MB/s total, typically sufficient for consumer.
- If events are 2 KB avg or peak 10×, recalc: 11.3 MB/s ingress → 12 TUs.

Recommendation:
- Start with Standard tier, auto-inflate enabled up to 12–20 TUs depending on peak uncertainty. Monitor throttling and TU ceiling; scale accordingly.
- Choose Premium if you need: 90‑day retention, dedicated compute isolation, VNet features at scale, more partitions per hub, or higher connection limits. Begin with 2 PUs and load test; adjust by observing throttling/latency. Use Premium if you expect >40 TU equivalent or strict noisy neighbor isolation.
- Dedicated only if you need single‑tenant at very high sustained rates, predictable latency, or > Premium limits.

## 2) Partitions for topic-a and topic-b

Rule of thumb (docs): target ~1 MB/s per partition. Partitions enable parallelism and ordering per key.

Given 5.6 MB/s peak ingest, set partitions ≥ 6. For headroom and balancing, choose 8–16.

Suggested counts:
- topic-a (ingest): 12 partitions (headroom for spikes; good balance between parallelism and complexity). Pros: better parallelism; Cons: more leases/checkpoints and consumers to manage.
- topic-b (post‑enrichment): 12 partitions if same key distribution and throughput; if fan‑in/fan‑out changes reduce volume, 8 may suffice. Keep equal partition count if you preserve partition keys end‑to‑end to avoid re‑ordering.

Notes:
- Standard tier max 32 partitions per hub; can’t change after creation. Premium allows dynamic increase; still avoid frequent changes.
- If you require strict ordering per entity, ensure partition key = entityId; increasing partitions won’t help throughput for a single hot key.

## 3) Reference architecture on AKS

3a) Consumer groups
- Use distinct consumer groups per downstream purpose: cg-processing (main pipeline), cg-storage (capture/archival if separate), cg-replay (adhoc reprocessing), cg-dlq-retry (retry pipeline).
- Reprocessing: create a new consumer group and start from timestamp/offset; don’t disturb production lag.

3b) Autoscaling
- Use KEDA with the Event Hubs scaler to drive HPA by partition lag and unprocessed events. Configure min/max replicas and cooldown.
- Optionally add CPU/memory metrics to avoid over‑scaling when lag is stale.

3c) Resilience patterns
- Checkpointing: use BlobCheckpointStore; one container per consumer group; disable soft delete/versioning per docs.
- Idempotency: include eventId in processing to avoid duplicates on retries.
- DLQ: publish failed events to a dlq hub; implement a dedicated consumer to triage and retry with backoff.
- Retry: exponential backoff for transient failures (REST enrichment); circuit breaker for downstream outages.
- Capture: enable Event Hubs Capture to ADLS/Blob for cold replay.

### KEDA sample
```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: eh-consumer-scaler
spec:
  scaleTargetRef:
    name: eh-consumer
  minReplicaCount: 2
  maxReplicaCount: 50
  cooldownPeriod: 120
  triggers:
  - type: azure-eventhub
    metadata:
      connectionFromEnv: EVENTHUBS_CONNECTION
      storageConnectionFromEnv: CHECKPOINT_CONNECTION
      eventHubName: topic-a
      consumerGroup: cg-processing
      unprocessedEventThreshold: "1000"
      activationUnprocessedEventThreshold: "100"
```

## 4) Implementation approach (Java 21)

4a) SDK usage
- azure-messaging-eventhubs EventProcessorClient for leasing, load balance, and checkpointing. Use DefaultAzureCredential in AKS with managed identity.

4b) Imperative with Virtual Threads
- EventProcessorClient hands batches; process each event on a virtual thread, call enrichment REST via WebClient, cache with Caffeine, publish to topic-b, then checkpoint.
- Tune batch size and prefetch; bound concurrency if downstream limits apply.

4c) Reactive with Project Reactor + Spring Cloud Stream
- Define Function<Flux<String>, Flux<String>> bean; binder handles Event Hubs in/out.
- Use manual checkpoint for at-least-once; ack after successful enrich+publish.

4d) Caching
- Caffeine with expireAfterWrite and maximumSize; expose cache metrics; consider a small near‑cache + async refresh for hot keys.

## 6) Observability

6a) Key metrics
- Event Hubs: incoming/outgoing msgs, incoming/outgoing MB, server errors, throttling, credits, capture backlog, partition lag.
- App: processing latency p50/p95/p99, error rate, retry counts, DLQ rate, cache hit/miss, external REST latency, concurrency/queue depth.

6b) Tools
- Azure Monitor + Application Insights (OpenTelemetry) for traces, metrics, logs; Container Insights for AKS; KEDA metrics via Prometheus if used.
- Enable distributed tracing: incoming EH span → enrich REST span → publish span.

## Links
- Event Hubs quotas and limits: https://learn.microsoft.com/azure/event-hubs/event-hubs-quotas
- Features, partitions, and sizing guidance: https://learn.microsoft.com/azure/event-hubs/event-hubs-features#partitions
- KEDA scaler: https://keda.sh/docs/2.14/scalers/azure-event-hub/
