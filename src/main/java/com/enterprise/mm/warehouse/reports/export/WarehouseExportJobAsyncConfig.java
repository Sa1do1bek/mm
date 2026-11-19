package com.enterprise.mm.warehouse.reports.export;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class WarehouseExportJobAsyncConfig {

    @Bean(name = "warehouseExportJobExecutor")
    public ThreadPoolTaskExecutor warehouseExportJobExecutor(
        @Value("${mm.warehouse.export.executor.core-pool-size:2}") int corePoolSize,
        @Value("${mm.warehouse.export.executor.max-pool-size:4}") int maxPoolSize,
        @Value("${mm.warehouse.export.executor.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("wh-export-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
