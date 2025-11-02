package com.slice.reactminiospring.service;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.jcraft.jsch.ChannelSftp;
import com.slice.reactminiospring.config.MinioConfigInfo;
import com.slice.reactminiospring.entity.Files;
import com.slice.reactminiospring.entity.SftpServerConfigs;
import com.slice.reactminiospring.mapper.FilesMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Vector;
import java.util.logging.Logger;

@Service
public class SftpUploadGateway {
    private static final Logger log = Logger.getLogger("SftpUploadGateway");

    @Resource private SftpClientPool clientPool;
    @Resource private DownloadManager downloadManager;
    @Resource
    private FilesMapper filesMapper;
    @Resource
    private MinioConfigInfo minioConfigInfo;

    public void syncFilesToMinio(SftpServerConfigs cfg) {
        try {
            ChannelSftp sftp = clientPool.get(cfg);
            Vector<ChannelSftp.LsEntry> files = sftp.ls(cfg.getSourceDir());

            for (ChannelSftp.LsEntry entry : files) {
                if (entry.getAttrs().isDir()) continue;
                if (entry.getAttrs().isDir()) {
                    continue;
                }
                String remotePath = cfg.getSourceDir() + "/" + entry.getFilename();
                log.info("📤 拉取文件: " + remotePath);

                // 对文件重命名，并以年月日文件夹格式存储
                String nestFile = DateUtil.format(LocalDateTime.now(), "yyyy/MM/dd");
                String object = nestFile + "/" + entry.getFilename();
                long fileSize = entry.getAttrs().getSize();
                try (InputStream input = sftp.get(remotePath)) {
                    String md5 = DigestUtil.md5Hex(input);
                    log.info("📑 文件: "+entry.getFilename()+" MD5={}"+ md5);

                    // ⚠️ 重置流（DigestUtil.md5Hex 已读取 InputStream）
//                    input.close();


                    // ✅ 2️⃣ 检查数据库中是否存在相同MD5
                    Files exists = filesMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Files>()
                                    .eq(Files::getMd5, md5)
                    );
                    if (exists == null) {
//                        InputStream newInput = sftp.get(remotePath);
                        downloadManager.uploadStreamToMinio(object, input);
                        // ✅ 构建 MinIO URL
                        String url = String.format("%s/%s/%s",
                                minioConfigInfo.getEndpoint(),
                                minioConfigInfo.getBucket(),
                                object);
                        // ✅ 保存数据库记录
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
                        log.info("✅ 上传成功 → " + object);

                    }else {
                        input.close();
                        log.info("⏭️ 文件已存在 (MD5匹配)，跳过上传:"+entry.getFilename());
                    }

                } catch (Exception e) {
                    log.info("❌ 上传失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.info("⚠️ SFTP同步失败: " + e.getMessage());
        }
    }
}
