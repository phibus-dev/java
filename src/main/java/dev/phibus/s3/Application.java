package dev.phibus.s3;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class Application {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        // This application is always a servlet web application. Pinning the type prevents
        // deployment environment/classpath differences from starting a non-web context.
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.run(args);
    }

    @Bean(name = "testExecutor", destroyMethod = "shutdown")
    ExecutorService testExecutor(
            @Value("${s3perf.runtime.executor.core-size:2}") int coreSize,
            @Value("${s3perf.runtime.executor.max-size:8}") int maxSize,
            @Value("${s3perf.runtime.executor.queue-capacity:32}") int queueCapacity) {
        int safeMax = Math.max(1, maxSize);
        int safeCore = Math.max(1, Math.min(coreSize, safeMax));
        return new ThreadPoolExecutor(safeCore, safeMax, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                Thread.ofPlatform().name("load-test-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "workloadExecutor", destroyMethod = "shutdown")
    ExecutorService workloadExecutor(@Value("${s3perf.runtime.max-worker-tasks:64}") int maxWorkerTasks) {
        int safeMax = Math.max(1, maxWorkerTasks);
        return new ThreadPoolExecutor(Math.min(4, safeMax), safeMax, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().name("workload-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "clickHouseWorkflowExecutor", destroyMethod = "shutdown")
    ExecutorService clickHouseWorkflowExecutor() {
        return new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100), Thread.ofPlatform().name("clickhouse-workflow-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
