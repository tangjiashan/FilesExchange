package com.slice.reactminiospring.service;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jcraft.jsch.ChannelSftp;
import com.slice.reactminiospring.config.MinioConfigInfo;
import com.slice.reactminiospring.entity.Files;
import com.slice.reactminiospring.entity.SftpServerConfigs;
import com.slice.reactminiospring.mapper.FilesMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Vector;
import java.util.logging.Logger;

@Service
public class SftpUploadGateway {

    private static final Logger log = Logger.getLogger("SftpUploadGateway");

    @Resource
    private SftpClientPool clientPool;
    @Resource
    private DownloadManager downloadManager;
    @Resource
    private FilesMapper filesMapper;
    @Resource
    private MinioConfigInfo minioConfigInfo;

    /**
     * 从 SFTP 同步文件到 MinIO
     */
    public void syncFilesToMinio(SftpServerConfigs cfg) {
        try {
            ChannelSftp sftp = clientPool.get(cfg);
            Vector<ChannelSftp.LsEntry> files = sftp.ls(cfg.getSourceDir());

            for (ChannelSftp.LsEntry entry : files) {
                if (entry.getAttrs().isDir()) continue;

                String remotePath = cfg.getSourceDir() + "/" + entry.getFilename();
                long fileSize = entry.getAttrs().getSize();

                log.info("开始拉取文件: " + remotePath + " (size=" + fileSize + ")");

                // 目标路径：yyyy/MM/dd/filename
                String nestFile = DateUtil.format(LocalDateTime.now(), "yyyy/MM/dd");
                String object = nestFile + "/" + entry.getFilename();

                try (InputStream input = sftp.get(remotePath)) {

                    // 1️⃣ 读取成字节数组（可重复使用）
                    byte[] bytes = input.readAllBytes();

                    // 2️⃣ 计算 MD5
                    String md5 = DigestUtil.md5Hex(bytes);
                    log.info("文件: " + entry.getFilename() + " 的 MD5=" + md5);

                    // 3️⃣ 检查数据库是否已有相同 MD5
                    Files exists = filesMapper.selectOne(
                            new LambdaQueryWrapper<Files>().eq(Files::getMd5, md5)
                    );

                    if (exists != null) {
                        log.info("文件已存在 (MD5匹配)，跳过上传: " + entry.getFilename());
                        continue;
                    }

                    // 4️⃣ 上传到 MinIO
                    try (InputStream uploadInput = new ByteArrayInputStream(bytes)) {
                        downloadManager.uploadStreamToMinio(object, uploadInput);
                    }

                    // 5️⃣ 构建 MinIO URL
                    String url = String.format("%s/%s/%s",
                            minioConfigInfo.getEndpoint(),
                            minioConfigInfo.getBucket(),
                            object);

                    // 6️⃣ 保存文件记录到数据库
                    Files f = new Files();
                    f.setOriginFileName(entry.getFilename());
                    f.setObject(object);
                    f.setBucket(minioConfigInfo.getBucket());
                    f.setUrl(url);
                    f.setMd5(md5);
                    f.setSize(fileSize);
                    f.setType("application/octet-stream");
                    f.setCreateTime(DateTime.now().toLocalDateTime());
                    filesMapper.insert(f);

                    log.info("文件上传成功 → " + object + " ✅");

                } catch (Exception e) {
                    log.warning("文件上传失败 (" + entry.getFilename() + "): " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            log.warning("SFTP 同步失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
