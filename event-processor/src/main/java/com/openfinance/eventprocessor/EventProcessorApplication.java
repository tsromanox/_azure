package com.openfinance.eventprocessor;

import com.openfinance.eventprocessor.consumer.EventConsumer;
import com.openfinance.eventprocessor.metrics.CustomMetrics;
import com.openfinance.eventprocessor.service.EventProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.lang.management.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class EventProcessorApplication {

    public static void main(String[] args) {
        // Configura propriedades do sistema antes do Spring iniciar
        configureSystemProperties();

        // Processa argumentos de linha de comando
        if (processCommandLineArgs(args)) {
            return; // Sai se foi um comando especial
        }

        try {
            // Inicia a aplicação Spring Boot
            SpringApplication app = new SpringApplication(EventProcessorApplication.class);
            app.setRegisterShutdownHook(false); // Vamos gerenciar nosso próprio shutdown
            app.run(args);
        } catch (Exception e) {
            log.error("Falha fatal ao iniciar aplicação", e);
            System.exit(1);
        }
    }

    private static void configureSystemProperties() {
        // Virtual Threads
        System.setProperty("jdk.virtualThreadScheduler.parallelism",
                System.getProperty("jdk.virtualThreadScheduler.parallelism", "1000"));
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize",
                System.getProperty("jdk.virtualThreadScheduler.maxPoolSize", "10000"));

        // JVM
        System.setProperty("java.awt.headless", "true");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

        // Netty (para RestClient/WebClient)
        System.setProperty("io.netty.tryReflectionSetAccessible", "true");
        System.setProperty("io.netty.allocator.type", "pooled");
    }

    private static boolean processCommandLineArgs(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "--version", "-v" -> {
                    printVersion();
                    return true;
                }
                case "--help", "-h" -> {
                    printHelp();
                    return true;
                }
                case "--info" -> {
                    printSystemInfo();
                    return true;
                }
            }
        }
        return false;
    }

    @Bean
    public CommandLineRunner applicationRunner(
            ApplicationContext ctx,
            EventConsumer consumer,
            CustomMetrics metricsBean,
            EventProcessingService processingServiceBean) {

        return args -> {
            this.applicationContext = (ConfigurableApplicationContext) ctx;
            this.eventConsumer = consumer;
            this.metrics = metricsBean;
            this.processingService = processingServiceBean;
            this.startupTime = Instant.now();

            // Inicialização
            printStartupBanner();
            printRuntimeInfo();

            // Cria arquivo de health
            createInitialHealthFile();

            // Registra shutdown hooks
            registerShutdownHooks();

            // Inicia monitoramento
            startMonitoring();

            // Log de beans carregados (modo debug)
            if (log.isDebugEnabled()) {
                logLoadedBeans(ctx);
            }

            log.info("✅ Event Processor iniciado com sucesso!");
            log.info("Processando eventos... (Ctrl+C para parar)");

            // Mantém aplicação rodando até shutdown
            try {
                keepAliveLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Thread principal interrompida");
            }

            log.info("Aplicação finalizada após {}", formatUptime());
        };
    }

    private void printStartupBanner() {
        String banner = """
            
            ╔════════════════════════════════════════════════════════════╗
            ║                    EVENT PROCESSOR                        ║
            ║                  Azure Event Hubs Edition                 ║
            ║------------------------------------------------------------║
            ║  Version: %-48s ║
            ║  Profile: %-48s ║
            ║  Started: %-48s ║
            ╚════════════════════════════════════════════════════════════╝
            """.formatted(
                VERSION,
                getActiveProfiles(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );

        System.out.println(banner);
    }

    private void printRuntimeInfo() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        log.info("═══════════════════════════════════════════");
        log.info("Runtime Information:");
        log.info("  Java: {} ({})",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"));
        log.info("  JVM: {} {}",
                runtime.getVmName(),
                runtime.getVmVersion());
        log.info("  Virtual Threads: {}",
                checkVirtualThreadsSupport() ? "✅ ENABLED" : "❌ DISABLED");
        log.info("  OS: {} {} ({})",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        log.info("  CPUs: {}", os.getAvailableProcessors());
        log.info("  Max Heap: {} MB",
                memory.getHeapMemoryUsage().getMax() / (1024 * 1024));
        log.info("  PID: {}", ProcessHandle.current().pid());
        log.info("  User: {}", System.getProperty("user.name"));
        log.info("  Working Dir: {}", System.getProperty("user.dir"));
        log.info("═══════════════════════════════════════════");
    }

    private void createInitialHealthFile() {
        try {
            Path healthPath = Paths.get("/tmp/event-processor-health");
            String content = String.format(
                    "status=healthy\npid=%d\nstarted=%s\nversion=%s\n",
                    ProcessHandle.current().pid(),
                    startupTime,
                    VERSION
            );
            Files.writeString(healthPath, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            log.debug("Health file criado: {}", healthPath);
        } catch (IOException e) {
            log.warn("Não foi possível criar health file: {}", e.getMessage());
        }
    }

    private void updateHealthFile(boolean healthy, String reason) {
        try {
            Path healthPath = Paths.get("/tmp/event-processor-health");
            String status = healthy ? "healthy" : "unhealthy";
            String content = String.format(
                    "status=%s\npid=%d\nuptime=%s\ntimestamp=%s\nreason=%s\n",
                    status,
                    ProcessHandle.current().pid(),
                    formatUptime(),
                    Instant.now(),
                    reason != null ? reason : "OK"
            );
            Files.writeString(healthPath, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("Erro ao atualizar health file", e);
        }
    }

    private void registerShutdownHooks() {
        // Hook principal de shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (isRunning.compareAndSet(true, false)) {
                performGracefulShutdown();
            }
        }, "shutdown-hook"));

        // Handlers de sinais específicos
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), sig -> {
                log.info("📍 Sinal SIGTERM recebido - iniciando graceful shutdown");
                initiateShutdown();
            });

            sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> {
                log.info("📍 Sinal SIGINT recebido (Ctrl+C) - iniciando graceful shutdown");
                initiateShutdown();
            });

            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sig -> {
                log.info("📍 Sinal SIGHUP recebido - recarregando configurações");
                reloadConfiguration();
            });
        } catch (Exception e) {
            log.warn("Não foi possível registrar signal handlers: {}", e.getMessage());
        }
    }

    private void initiateShutdown() {
        if (isRunning.compareAndSet(true, false)) {
            new Thread(this::performGracefulShutdown, "shutdown-executor").start();
        }
    }

    private void performGracefulShutdown() {
        log.info("════════════════════════════════════════");
        log.info("🛑 Iniciando Graceful Shutdown...");
        log.info("════════════════════════════════════════");

        try {
            // 1. Para de aceitar novos eventos
            isHealthy.set(false);
            updateHealthFile(false, "SHUTTING_DOWN");

            // 2. Para o consumer
            if (eventConsumer != null) {
                log.info("Parando consumer de eventos...");
                eventConsumer.stop();
            }

            // 3. Aguarda processamento em andamento
            log.info("Aguardando conclusão de eventos em processamento...");
            Thread.sleep(5000); // Aguarda 5 segundos

            // 4. Para monitoring
            if (monitoringExecutor != null && !monitoringExecutor.isShutdown()) {
                monitoringExecutor.shutdown();
                monitoringExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }

            // 5. Log de estatísticas finais
            logFinalStatistics();

            // 6. Remove health file
            Files.deleteIfExists(Paths.get("/tmp/event-processor-health"));

            // 7. Fecha contexto Spring
            if (applicationContext != null) {
                applicationContext.close();
            }

        } catch (Exception e) {
            log.error("Erro durante shutdown", e);
        } finally {
            keepAliveLatch.countDown();
            log.info("✅ Shutdown concluído");
        }
    }

    private void startMonitoring() {
        monitoringExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().name("monitor").unstarted(r);
            t.setDaemon(true);
            return t;
        });

        // Monitoramento de recursos a cada 30 segundos
        monitoringExecutor.scheduleAtFixedRate(this::monitorResources, 30, 30, TimeUnit.SECONDS);

        // Atualização de health a cada 10 segundos
        monitoringExecutor.scheduleAtFixedRate(this::checkHealth, 10, 10, TimeUnit.SECONDS);
    }

    private void monitorResources() {
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();

            long heapUsed = memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long heapMax = memory.getHeapMemoryUsage().getMax() / (1024 * 1024);
            double heapPercent = (double) heapUsed / heapMax * 100;

            int threadCount = threads.getThreadCount();
            int peakThreads = threads.getPeakThreadCount();

            if (heapPercent > 80) {
                log.warn("⚠️ Uso alto de memória: {:.1f}% ({} MB / {} MB)",
                        heapPercent, heapUsed, heapMax);
            }

            if (threadCount > 500) {
                log.warn("⚠️ Número alto de threads: {} (pico: {})",
                        threadCount, peakThreads);
            }

            if (log.isDebugEnabled()) {
                log.debug("📊 Recursos: Heap {:.1f}% ({}/{}MB), Threads: {}",
                        heapPercent, heapUsed, heapMax, threadCount);
            }

        } catch (Exception e) {
            log.error("Erro no monitoramento", e);
        }
    }

    private void checkHealth() {
        try {
            boolean healthy = true;
            String reason = "OK";

            // Verifica lag do consumer
            if (metrics != null) {
                long lag = metrics.getCurrentLag();
                if (lag > 100000) {
                    healthy = false;
                    reason = "HIGH_LAG:" + lag;
                }

                // Verifica taxa de erro
                double errorRate = metrics.getErrorRate();
                if (errorRate > 0.10) {
                    healthy = false;
                    reason = "HIGH_ERROR_RATE:" + String.format("%.2f%%", errorRate * 100);
                }
            }

            // Verifica memória
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            long heapUsed = memory.getHeapMemoryUsage().getUsed();
            long heapMax = memory.getHeapMemoryUsage().getMax();
            if (heapUsed > heapMax * 0.95) {
                healthy = false;
                reason = "MEMORY_CRITICAL";
            }

            boolean previousHealth = isHealthy.getAndSet(healthy);
            if (previousHealth != healthy) {
                if (healthy) {
                    log.info("✅ Sistema recuperado - status: HEALTHY");
                } else {
                    log.error("❌ Sistema degradado - status: UNHEALTHY - Razão: {}", reason);
                }
            }

            updateHealthFile(healthy, reason);

        } catch (Exception e) {
            log.error("Erro ao verificar health", e);
        }
    }

    private void logFinalStatistics() {
        log.info("────────────────────────────────────────");
        log.info("📊 Estatísticas Finais:");
        log.info("  Uptime: {}", formatUptime());
        log.info("  PID: {}", ProcessHandle.current().pid());

        if (processingService != null) {
            EventProcessingService.ServiceStatistics stats = processingService.getStatistics();
            log.info("  Eventos processados: {}", stats.getTotalProcessed());
            log.info("  Eventos com falha: {}", stats.getTotalFailed());
            log.info("  Taxa de cache hit: {:.2f}%", stats.getCacheHitRate());
        }

        // GC stats
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcs) {
            log.info("  GC {}: {} coletas, {}ms total",
                    gc.getName(),
                    gc.getCollectionCount(),
                    gc.getCollectionTime());
        }

        log.info("────────────────────────────────────────");
    }

    private void reloadConfiguration() {
        log.info("♻️ Recarregando configurações...");
        // Implementar recarga de configurações se necessário
    }

    private void logLoadedBeans(ApplicationContext ctx) {
        String[] beanNames = ctx.getBeanDefinitionNames();
        long customBeans = Arrays.stream(beanNames)
                .filter(name -> name.contains("eventprocessor"))
                .count();

        log.debug("📦 Beans carregados: {} total, {} customizados",
                beanNames.length, customBeans);
    }

    private boolean checkVirtualThreadsSupport() {
        try {
            Thread vt = Thread.ofVirtual().unstarted(() -> {});
            return true;
        } catch (Exception | Error e) {
            return false;
        }
    }

    private String getActiveProfiles() {
        String[] profiles = applicationContext != null ?
                applicationContext.getEnvironment().getActiveProfiles() : new String[0];
        return profiles.length > 0 ? String.join(",", profiles) : "default";
    }

    private String formatUptime() {
        if (startupTime == null) return "N/A";

        Duration uptime = Duration.between(startupTime, Instant.now());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();

        if (days > 0) {
            return String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private static void printVersion() {
        System.out.printf("""
            Event Processor
            Version: %s
            Build Date: %s
            Java: %s
            JVM: %s
            """,
                VERSION,
                BUILD_DATE,
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"));
    }

    private static void printHelp() {
        System.out.println("""
            Usage: java -jar event-processor.jar [options]
            
            Options:
              --version, -v     Show version information
              --help, -h        Show this help message
              --info            Show detailed system information
            
            Environment Variables:
              AZURE_EVENTHUB_NAMESPACE    Azure Event Hub namespace
              AZURE_STORAGE_ACCOUNT       Storage account for checkpoints
              CONSUMER_GROUP              Consumer group name (default: $Default)
              API_BASE_URL                External API base URL
              ENVIRONMENT                 Environment (dev/staging/prod)
            
            JVM Options (recommended):
              --enable-preview            Enable preview features (Virtual Threads)
              -XX:+UseZGC                 Use ZGC garbage collector
              -XX:MaxRAMPercentage=75.0   Use up to 75% of available RAM
            
            Examples:
              java -jar event-processor.jar
              java --enable-preview -XX:+UseZGC -jar event-processor.jar
              java -jar event-processor.jar --version
            
            For more information, see: https://github.com/empresa/event-processor
            """);
    }

    private static void printSystemInfo() {
        System.out.println("System Information:");
        System.getProperties().forEach((key, value) ->
                System.out.printf("  %s = %s%n", key, value));
    }

}
