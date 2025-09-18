
# 1) Capacidade — tier, TUs/PUs, contas e recomendações

## 1.1 Como a Microsoft mede capacidade

* **Standard (TUs)**: cada Throughput Unit dá **1 MB/s de entrada** (ou **1.000 ev/s de 1 KB**) e **2 MB/s de saída** (ou **4.096 ev/s de 1 KB**), além de **84 GB de retenção por TU**. Limites: **até 40 TUs** por namespace (20 via portal, 40 via solicitação). **Até 32 partições** por hub. Há ainda **limite de 1 MB/s por partição**. ([Microsoft Learn][1])
* **Premium (PUs)**: usa **Processing Units**, **100 partições** por hub (limite de **200 por PU** no namespace), **1 TB de retenção por PU**, até **16 PUs** por namespace. ([Microsoft Learn][2])
* **Dedicated (CUs)**: single-tenant, capacidade em **Capacity Units** (CUs). ([Microsoft Learn][3])
* **Auto-inflate (Standard)**: escala **TUs automaticamente para cima** até um teto; **não reduz** automaticamente. ([Microsoft Learn][4])
* **Prática essencial**: planeje **≤ 1 MB/s por partição**. ([Microsoft Learn][5])

## 1.2 100 milhões/dia → dimensionamento (com fórmula)

Carga média:
100.000.000 ÷ 86.400 ≈ **1.157 ev/s**.

Vamos dimensionar por **faixa de tamanho** e **pico p95 = 5×** a média, com **2 consumer groups** simultâneos (produção + reprocessamento). Regra:

* TUs por **entrada** = ceil(Ingress\_MBps)
* TUs por **saída** = ceil(Egress\_MBps/2) (porque 2 MB/s de egress por TU) ([Microsoft Learn][1])

| Tamanho médio | Ingest p95 (MB/s) | Egress p95 (MB/s) c/ 2 CGs | **TUs mín** (max(in, out)) | **Recomendação**                                                                                                       |
| ------------- | ----------------: | -------------------------: | -------------------------: | ---------------------------------------------------------------------------------------------------------------------- |
| 1 KB          |              5,65 |                      11,30 |                      **6** | **Standard**, **auto-inflate 6→10 TUs** p/ folga                                                                       |
| 2 KB          |             11,30 |                      22,61 |                     **12** | **Standard**, **auto-inflate 12→16 TUs**                                                                               |
| 4 KB          |             22,61 |                      45,21 |                     **23** | **Standard** fica no limite (20–40 TUs). Se picos frequentes/latência crítica, **Premium** (PUs) passa a fazer sentido |

Notas:

* Standard atende confortavelmente até \~**12 TUs**; acima de \~20 (picos altos/4 KB), avalie **Premium** por **partições extras (100)**, **retenção 90 dias**, isolamento e **recursos avançados**. ([Microsoft Learn][2])
* Ative **Auto-inflate** no Standard (e defina um teto coerente com picos). Lembre: **só escala para cima**. ([Microsoft Learn][4])

# 2) Partições para ‘topic-a’ e ‘topic-b’ (Event Hubs)

> Em Event Hubs, “tópico” = **event hub** (entidade).

Regras práticas (confirmadas pela Microsoft):

* **≤ 1 MB/s por partição** (não adianta ter TU sobrando se uma partição fica “quente”). ([Microsoft Learn][5])
* Standard: **máx. 32 partições** por hub; Premium: **100**. ([Microsoft Learn][3])

### Cálculo e proposta

Com base nos cenários acima:

* **Se 6–12 TUs (1–2 KB)**:

    * **topic-a** (ingest mais quente): **32 partições** → dá folga para balancear picos, paralelizar consumidores e diluir hot-keys; é o teto do **Standard**. **Pró:** alto paralelismo; **contra:** mais checkpoints/conexões. ([Microsoft Learn][3])
    * **topic-b** (enriquecidos/saída): **24–32 partições** conforme taxa de publicação e necessidade de reprocessamento em paralelo.
* **Se \~23 TUs (4 KB)**: **32 partições** ainda atende (23 MB/s ≤ 32 MB/s se bem distribuído), mas qualquer skew pode estourar os **1 MB/s por partição**; **Premium (100 partições)** reduz risco e dá headroom. ([Microsoft Learn][5])

Regra de bolso que funciona bem: **partições ≥ TUs** e, quando possível, **partições ≥ 2× pods consumidores esperados** (por consumer group) para não “engargalar” paralelismo em hora de pico. (Deriva do limite por partição de 1 MB/s e do fato de uma partição alimentar apenas **1 instância** por consumer group com ordenação garantida.)

# 3) Arquitetura de referência no AKS

## 3.1 Consumer groups

* **cg-main**: pipeline “oficial” (processamento on-line).
* **cg-replay-{data}**: reprocessamento sob demanda (independente).
* **cg-debug**: testes/diagnóstico sem impactar offsets de produção.
  Consumer groups independentes permitem **escalar/reler** sem interferir. ([Azure Documentation][6])

## 3.2 Escala automática por “lag” usando HPA (via KEDA)

O **HPA** não lê lag de Event Hubs nativamente; a abordagem recomendada no AKS é usar **KEDA**, que **expõe métricas externas ao HPA** e **cria/gerencia o HPA** por baixo dos panos. Ele calcula “eventos não processados” comparando **checkpoint (Blob)** vs **seq. atual**. ([Microsoft Learn][7])

### Exemplo (ScaledObject – KEDA v2.17)

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: consumer-topic-a
spec:
  scaleTargetRef:
    name: consumer-topic-a          # Deployment do consumidor
  pollingInterval: 30               # s
  cooldownPeriod: 300               # s
  minReplicaCount: 0
  maxReplicaCount: 200
  triggers:
    - type: azure-eventhub
      metadata:
        eventHubNamespace: "<ns>.servicebus.windows.net"
        eventHubName: "topic-a"
        consumerGroup: "cg-main"
        # Estratégia de checkpoint compatível com SDKs novos:
        checkpointStrategy: "blobMetadata"
        storageAccountName: "<blobAccount>"
        blobContainer: "eh-checkpoints-cg-main"
        # Alvo de backlog médio por partição:
        unprocessedEventThreshold: "1000"
      authenticationRef:
        name: keda-wi-auth          # Workload Identity (RBAC EH + Blob)
```

KEDA só escala corretamente se os **checkpoints** estiverem no **Blob** e com **estratégia compatível** (ex.: `blobMetadata` nas versões novas). ([KEDA][8])

> Observação: KEDA **usa HPA** e o **adapta** (external metrics) automaticamente; você continua vendo um HPA no cluster. ([KEDA][9])

## 3.3 Resiliência

* **Checkpoints**: use `BlobCheckpointStore` (um **container por consumer group**, na **mesma região**) para reduzir latência/conflitos. ([Microsoft Learn][10])
* **Idempotência & deduplicação**: use **chave de partição** determinística e guarde **último offset/ETag** do lado do consumidor quando precisar dedupe lógico.
* **Dead-letter**: Event Hubs **não tem DLQ nativo**; implemente um **hub DLQ** (ex.: `topic-a-dlq`) ou envie para **Azure Service Bus (DLQ nativo)** com metadados do erro para reprocesso. ([Microsoft Learn][11])
* **Auto-inflate (Standard)**: habilite com teto adequado para absorver picos e evitar `ServerBusy`. ([Microsoft Learn][4])

# 4) Implementação Java 21 + Spring Boot 3.5.5

## 4.1 Dependências (Maven)

Compatibilidade **Spring Cloud Azure 5.23.0** com Spring Boot **3.5.x**. ([GitHub][12])

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>3.5.5</spring-boot.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.azure.spring</groupId>
      <artifactId>spring-cloud-azure-dependencies</artifactId>
      <version>5.23.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Binder do Event Hubs (Spring Cloud Stream) -->
  <dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-stream-eventhubs</artifactId>
    <version>5.23.0</version>
  </dependency>

  <!-- SDK low-level (produtor/consumidor e checkpoint em Blob) -->
  <dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-messaging-eventhubs</artifactId>
  </dependency>
  <dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-messaging-eventhubs-checkpointstore-blob</artifactId>
  </dependency>

  <!-- Cache em memória -->
  <dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
  </dependency>

  <!-- Observabilidade -->
  <!-- Use OpenTelemetry Java Agent + Azure Monitor (OTLP) -->
</dependencies>
```

## 4.2 Configuração (application.yml) — binder + 2 hubs

```yaml
spring:
  application.name: ofh-consumer
  cloud:
    azure:
      credential:
        # Preferencial: Managed Identity/Workload Identity no AKS
        client-id: ${AZURE_CLIENT_ID:}
      eventhubs:
        namespace: ${EH_NAMESPACE}                # <ns>.servicebus.windows.net
        processor:
          checkpoint-store:
            container-name: eh-checkpoints-cg-main
            account-name: ${BLOB_ACCOUNT}
        # Consumer tuning (exemplos)
        consumer:
          prefetch: 500
          track-last-enqueued-event-properties: true
    stream:
      binders:
        eh:
          type: azure-eventhubs
          defaultCandidate: true
      bindings:
        topicAIn:                    # consumidor reativo
          destination: topic-a
          contentType: application/json
          group: cg-main
        topicBOut:                   # produtor reativo
          destination: topic-b
          contentType: application/json
      bindings.topicAIn.consumer:
        concurrency: 8               # cria 8 receivers (útil com muitas partições)
        batch-mode: true             # recebe lista de eventos
      bindings.topicBOut.producer:
        partitionKeyExpression: "headers['pk']"
```

Referência geral de propriedades e tutoriais de binder Event Hubs. ([Microsoft Learn][13])

## 4.3 Modelo **imperativo** com Virtual Threads

Usa `EventProcessorClient` (SDK) para ler de todas as partições e **virtual threads** para enriquecer e publicar.

```java
@Configuration
class EhClientsConfig {

  @Bean
  EventHubProducerClient producer(@Value("${eh.fqdn}") String fqdn,
                                  @Value("${eh.topic-b}") String hub,
                                  DefaultAzureCredential credential) {
    return new EventHubClientBuilder()
        .credential(fqdn, hub, credential)
        .buildProducerClient();
  }

  @Bean
  EventProcessorClient processor(@Value("${eh.fqdn}") String fqdn,
                                 @Value("${eh.topic-a}") String hub,
                                 @Value("${eh.consumer-group}") String cg,
                                 BlobContainerAsyncClient blobContainer,
                                 DefaultAzureCredential credential,
                                 ConsumerService service) {

    CheckpointStore store = new BlobCheckpointStore(blobContainer);

    return new EventProcessorClientBuilder()
        .consumerGroup(cg)
        .checkpointStore(store)
        .processEvent(event -> service.handle(event))   // vide abaixo
        .processError(err -> log.error("EH error", err.getThrowable()))
        .credential(fqdn, hub, credential)
        .buildEventProcessorClient();
  }
}

@Service
@RequiredArgsConstructor
class ConsumerService {
  private final EventHubProducerClient producer;
  private final RestClient rest = RestClient.builder().build();
  private final ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor();
  private final Cache<String, JsonNode> cache = Caffeine.newBuilder()
      .maximumSize(100_000)
      .expireAfterWrite(Duration.ofMinutes(10))
      .recordStats()
      .build(); // 

  public void handle(ProcessEvent event) {
    // 1) Decodifica
    byte[] body = event.getData().getBody();
    // 2) Processa em VT (não bloqueia a thread do loop do SDK)
    vthreads.submit(() -> {
      try {
        var pk = event.getPartitionContext().getPartitionId() + ":" + event.getData().getSequenceNumber();
        // cache "read-through"
        var enr = cache.get(pk, k -> enrich(body));
        // 3) Publica no topic-b
        var batch = producer.createBatch();
        batch.tryAdd(new EventData(objectMapper().writeValueAsBytes(enr)));
        producer.send(batch);
        // 4) Checkpoint seguro após side-effects
        event.updateCheckpoint();
      } catch (Exception ex) {
        // Envia para DLQ (hub ou Service Bus)
        sendToDlq(body, ex);
        // opcional: checkpoint mesmo assim para não travar o fluxo de eventos ruins
      }
    });
  }

  private JsonNode enrich(byte[] body) {
    // chamada REST síncrona — VT esconde a latência sem bloquear carriers
    return rest.get().uri("https://api.local/enrich").retrieve().body(JsonNode.class);
  }
}
```

Referências do SDK (`EventHubProducerClient`, `EventProcessorClient`) e checkpoint em Blob. ([Microsoft Learn][14])

## 4.4 Modelo **reativo** (Project Reactor + Spring Cloud Stream)

Consumidor **batch** de `topic-a` → enriquece → publica em `topic-b`:

```java
@Configuration
class StreamFunctions {

  @Bean
  public Function<Flux<Message<byte[]>>, Flux<Message<byte[]>>> topicAIn() {
    return flux -> flux
      .flatMap(batchMsg ->
        Flux.fromIterable(decodeBatch(batchMsg))
            .flatMap(evt -> enrich(evt))       // reativo (WebClient) aqui se preferir
            .map(enriched -> MessageBuilder
                .withPayload(serialize(enriched))
                .setHeader("pk", enriched.key()) // guia a partição no out
                .build()
            )
      );
  }
}
```

Guia e exemplos de Spring + Event Hubs: enviar/receber, propriedades e batch-consumer. ([Microsoft Learn][15])

## 4.5 Estratégias de **cache** (Caffeine)

* **Lookup-heavy** (enriquecimento por REST): `maximumSize`, `expireAfterWrite`, `recordStats()` e **métricas**. Use `Cache.stats()` e exponha via Micrometer. Documentação/uso do Caffeine. ([javadoc.io][16])

# 5) Dead-lettering (padrão)

Como Event Hubs **não** tem DLQ nativo:

1. publique falhas em **`topic-a-dlq`** (ou `topic-b-dlq`) **com headers** `errorCode`, `errorMsg`, `stackHash`, `originalPartition/offset`;
   ou 2) envie para **Azure Service Bus** (DLQ nativo) quando precisar SLA de retenção/inspeção diferenciados. ([Microsoft Learn][11])

# 6) Observabilidade de ponta a ponta

## 6.1 Métricas *chave* do Event Hubs (Azure Monitor)

* **IncomingMessages / OutgoingMessages** e **IncomingBytes / OutgoingBytes** (taxa efetiva).
* **ThrottledRequests**, **ServerErrors/UserErrors**, **Successful/Failed Requests** (saúde e limites).
* **ActiveConnections** e **CaptureBacklog** (se usar Capture). Tabelas/nomes de métricas oficiais. ([Microsoft Learn][17])

## 6.2 Métricas da aplicação

* **Latência** por estágio (ingest → enrich → publish).
* **Taxa de erro** por tipo (HTTP 5xx do enriquecimento, serialização, publish retry).
* **Backpressure** reativo (buffer sizes, dropped).
* **Cache hit-ratio** e evictions (Caffeine).

### Coleta e visualização no Azure

* **Azure Monitor managed Prometheus** (scrape nativo no AKS) + **Managed Grafana** para dashboards/alertas. Suporta **PodMonitor/ServiceMonitor**, grava em **Azure Monitor workspace**, integra com Grafana. ([Microsoft Learn][18])
* **Application Insights (Azure Monitor)** via **OpenTelemetry Java Agent** (zero-code), enviando **traces, métricas e logs** por **OTLP**. ([OpenTelemetry][19])

#### Exemplo de alertas (Prometheus gerenciados)

* **EH ThrottledRequests > 0** por 5 min (Namespace).
* **Lag alto** (usando regra baseada em backlog do KEDA ou métrica custom “unprocessed\_events”).
* **Erro 5xx no enriquecimento > 2%** (métrica da app).
  Configuração de Prometheus gerenciado e regras. ([Microsoft Learn][18])

---

## Resumo das *decisões* recomendadas

* **Tier**: **Standard** com **auto-inflate** atende bem **1–2 KB** (100 M/dia) → comece em **6–12 TUs**; defina **teto** com 30–50% de folga. **Premium** se você precisar de **> 20 TUs** frequentes, **90 dias**, **100 partições** ou isolamento. ([Microsoft Learn][4])
* **Partições**: **topic-a = 32**, **topic-b = 24–32** (Standard). Use **chaves de partição** para distribuição estável e mantenha **partições ≥ TUs** e ≥ paralelismo alvo. ([Microsoft Learn][5])
* **Escala**: **KEDA** no AKS (HPA externo) dirigido por **backlog** (checkpoints no Blob). ([Microsoft Learn][7])
* **Resiliência**: checkpoint pós-efeitos, **DLQ lógico** (hub ou Service Bus), retries exponenciais, idempotência por chave/offset. ([Microsoft Learn][11])
* **Implementação**: forneci **imperativo (Virtual Threads)** + **reativo** (Stream Binder); **cache Caffeine** para enriquecer sem bater sempre no REST. ([Microsoft Learn][15])
* **Observabilidade**: **Managed Prometheus + Managed Grafana** para métricas de infra/app; **Application Insights (OTel)** para traces distribuídos. ([Microsoft Learn][18])

Se quiser, já te entrego um **Helm chart** com o `Deployment` do consumidor, `ScaledObject` do KEDA e `ServiceMonitor` para expor métricas — é só falar o nome dos namespaces e secrets que você usa.

[1]: https://learn.microsoft.com/en-us/azure/event-hubs/event-hubs-faq?utm_source=chatgpt.com "Frequently asked questions - Azure Event Hubs"
[2]: https://learn.microsoft.com/en-us/azure/event-hubs/compare-tiers?utm_source=chatgpt.com "Compare Azure Event Hubs tiers"
[3]: https://learn.microsoft.com/en-us/azure/event-hubs/event-hubs-quotas?utm_source=chatgpt.com "Azure Event Hubs quotas and limits"
[4]: https://learn.microsoft.com/en-us/azure/event-hubs/event-hubs-auto-inflate?utm_source=chatgpt.com "Automatically scale up throughput units in Azure Event Hubs"
[5]: https://learn.microsoft.com/en-us/azure/event-hubs/event-hubs-features?utm_source=chatgpt.com "Overview of features - Azure Event Hubs"
[6]: https://docs.azure.cn/en-us/event-hubs/event-processor-balance-partition-load?utm_source=chatgpt.com "Balance partition load across multiple instances"
[7]: https://learn.microsoft.com/en-us/azure/aks/keda-about?utm_source=chatgpt.com "Kubernetes Event-driven Autoscaling (KEDA) - Azure"
[8]: https://keda.sh/docs/2.17/scalers/azure-event-hub/?utm_source=chatgpt.com "Azure Event Hubs"
[9]: https://keda.sh/docs/1.5/concepts/scaling-deployments/?utm_source=chatgpt.com "Scaling Deployments"
[10]: https://learn.microsoft.com/en-us/java/api/overview/azure/messaging-eventhubs-checkpointstore-blob-readme?view=azure-java-stable&utm_source=chatgpt.com "Azure Event Hubs Checkpoint Store client library for Java"
[11]: https://learn.microsoft.com/en-us/azure/architecture/serverless/event-hubs-functions/resilient-design?utm_source=chatgpt.com "Resilient Event Hubs and Functions design"
[12]: https://github.com/Azure/azure-sdk-for-java/wiki/Spring-Versions-Mapping?utm_source=chatgpt.com "Spring Versions Mapping · Azure/azure-sdk-for-java Wiki"
[13]: https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/configure-spring-cloud-stream-binder-java-app-azure-event-hub?utm_source=chatgpt.com "Spring Cloud Stream with Azure Event Hubs"
[14]: https://learn.microsoft.com/en-us/java/api/com.azure.messaging.eventhubs.eventhubproducerclient?view=azure-java-stable&utm_source=chatgpt.com "EventHubProducerClient Class"
[15]: https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/using-event-hubs-in-spring-applications?utm_source=chatgpt.com "Using Event Hubs in Spring applications - Java on Azure"
[16]: https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/Caffeine.html?utm_source=chatgpt.com "Class Caffeine<K,V>"
[17]: https://learn.microsoft.com/en-us/azure/azure-monitor/reference/supported-metrics/microsoft-eventhub-namespaces-metrics?utm_source=chatgpt.com "Supported metrics - Microsoft.EventHub/Namespaces ..."
[18]: https://learn.microsoft.com/en-us/azure/azure-monitor/metrics/prometheus-metrics-overview?utm_source=chatgpt.com "Overview of Azure Monitor with Prometheus"
[19]: https://opentelemetry.io/docs/zero-code/java/agent/?utm_source=chatgpt.com "Java Agent"
