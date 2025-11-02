package com.slice.reactminiospring.job;

import com.slice.reactminiospring.entity.SftpServerConfigs;
import com.slice.reactminiospring.service.ISftpServerConfigService;
import com.slice.reactminiospring.service.SftpUploadGateway;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SftpDownloadTask {
    @Resource 
    private ISftpServerConfigService iSftpServerConfigService;
    
    @Resource 
    private SftpUploadGateway uploadGateway;

    private static final int TIMEOUT_MINUTES = 25; // 设置超时时间为25分钟(小于调度间隔)

    @Scheduled(cron = "0 */3 * * * ?")
    public void runSftpSync() {
        log.info("Starting SFTP sync task...");
        try {
            List<SftpServerConfigs> configs = iSftpServerConfigService.getSftpServerConfigList();
            if (configs.isEmpty()) {
                log.warn("No SFTP server configurations found");
                return;
            }

            // 并发处理每个SFTP服务器的同步任务
            List<CompletableFuture<Void>> futures = configs.stream()
                .map(cfg -> CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Starting sync for SFTP server: {}", cfg.getHost());
                        uploadGateway.syncFilesToMinio(cfg);
                        log.info("Completed sync for SFTP server: {}", cfg.getHost());
                    } catch (Exception e) {
                        log.error("Error syncing files from SFTP server: " + cfg.getHost(), e);
                    }
                }))
                .toList();

            // 等待所有任务完成或超时
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .exceptionally(throwable -> {
                    log.error("SFTP sync task failed or timed out", throwable);
                    return null;
                })
                .join();

            log.info("SFTP sync task completed successfully");
        } catch (Exception e) {
            log.error("Unexpected error in SFTP sync task", e);
        }
    }
}
