package dev.phibus.s3;

import java.util.concurrent.Executor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    @Bean(name = "testExecutor")
    Executor testExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("s3-test-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "clickHouseWorkflowExecutor")
    Executor clickHouseWorkflowExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("clickhouse-workflow-");
        executor.initialize();
        return executor;
    }
}
