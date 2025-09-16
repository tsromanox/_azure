package com.openfinance.eventprocessor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;

@Slf4j
@Configuration
@EnableAsync
public class ThreadConfig {

    @Value("${thread-pool.virtual-threads.enabled}")
    private boolean virtualThreadsEnabled;

    @Value("${thread-pool.core-size}")
    private int corePoolSize;

    @Value("${thread-pool.max-size}")
    private int maxPoolSize;

    @Value("${thread-pool.queue-capacity}")
    private int queueCapacity;

    /**
     * Configura Virtual Threads para o Tomcat (HTTP requests)
     */
    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadCustomizer() {
        if (virtualThreadsEnabled) {
            log.info("Habilitando Virtual Threads para Tomcat");
            return protocolHandler -> {
                protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            };
        }
        return protocolHandler -> {};
    }

    /**
     * Task executor com Virtual Threads para processamento assíncrono
     */
    @Bean(name = "virtualThreadExecutor")
    public AsyncTaskExecutor virtualThreadExecutor() {
        if (virtualThreadsEnabled) {
            log.info("Criando executor com Virtual Threads");
            return new TaskExecutorAdapter(
                    Executors.newThreadPerTaskExecutor(
                            Thread.ofVirtual()
                                    .name("virtual-", 0)
                                    .factory()
                    )
            );
        } else {
            log.info("Virtual Threads desabilitadas, usando ThreadPool tradicional");
            return traditionalThreadPoolExecutor();
        }
    }

    /**
     * Fallback para thread pool tradicional se VT não estiver habilitada
     */
    private ThreadPoolTaskExecutor traditionalThreadPoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("traditional-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Monitora e loga estatísticas de threads periodicamente
     */
    @Bean
    public ThreadMonitor threadMonitor() {
        return new ThreadMonitor();
    }

    public static class ThreadMonitor {
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
        public void logThreadStats() {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

            log.info("Thread Stats - Total: {}, Peak: {}, Daemon: {}, Started: {}",
                    threadMXBean.getThreadCount(),
                    threadMXBean.getPeakThreadCount(),
                    threadMXBean.getDaemonThreadCount(),
                    threadMXBean.getTotalStartedThreadCount());

            // Se virtual threads estiverem habilitadas
            if (Thread.currentThread().isVirtual()) {
                log.info("Virtual Threads estão ativas");
            }
        }
    }
}
