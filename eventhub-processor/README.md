# Event Hub Processor (Java 21, Spring Boot 3.5.5)

This project demonstrates two models to process Azure Event Hubs streams:
- Imperative using Java 21 Virtual Threads and the Azure SDK
- Reactive using Spring Cloud Stream with the Event Hubs binder

Includes guidance for sizing (100M events/day), partitions, AKS autoscaling, checkpoints/DLQ, caching with Caffeine, and observability.

## Build & Run

Profiles:
- `reactive` (default): Spring Cloud Stream functional `process`
- `imperative`: EventProcessorClient with virtual threads

Environment variables to set:
- EVENTHUBS_NAMESPACE / EVENTHUBS_FQDN
- INPUT_HUB, OUTPUT_HUB, DLQ_HUB
- CHECKPOINT_CONNECTION_STRING, CHECKPOINT_CONTAINER

## Notes
- Uses Spring Cloud Azure BOM 5.23.0 (supports Spring Boot 3.3+).
- Caffeine for in-memory caching.
- Actuator endpoints enabled.

See `docs/` for architecture and sizing.