package com.slice.reactminiospring.controller;

import com.slice.reactminiospring.common.R;
import com.slice.reactminiospring.entity.Files;
import com.slice.reactminiospring.model.FileUploadInfo;
import com.slice.reactminiospring.model.UploadUrlsVO;
import com.slice.reactminiospring.service.DownloadManager;
import com.slice.reactminiospring.service.IFilesService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * 文件表 前端控制器
 */
@RestController
@RequestMapping("/files")
@Slf4j
public class FilesController {

    @Resource
    private IFilesService filesService;
    @Resource
    private DownloadManager downloadManager;

    /**
     * 检查文件是否存在
     */
    @GetMapping("/multipart/check/{md5}")
    public R<FileUploadInfo> checkFileByMd5(@PathVariable String md5) {
        log.info("查询 <{}> 文件是否存在、是否进行断点续传", md5);
        return filesService.checkFileByMd5(md5);
    }

    /**
     * 初始化文件分片地址及相关数据
     */
    @PostMapping("/multipart/init")
    public R<UploadUrlsVO> initMultiPartUpload(@RequestBody FileUploadInfo fileUploadInfo) {
        log.info("通过 <{}> 初始化上传任务", fileUploadInfo);
        return filesService.initMultipartUpload(fileUploadInfo);
    }

    /**
     * 文件合并（单文件不会合并，仅信息入库）
     */
    @PostMapping("/multipart/merge/{md5}")
    public R<String> mergeMultipartUpload(@PathVariable String md5) {
        log.info("通过 <{}> 合并上传任务", md5);
        return filesService.mergeMultipartUpload(md5);
    }

    /**
     * 下载文件（分片）
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> downloadMultipartFile(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws Exception {
        log.info("通过 <{}> 开始分片下载", id);
        return filesService.downloadMultipartFile(id, request, response);
    }

    @GetMapping("/list")
    public R<List<Files>> getFileList() {
        return filesService.getFileList();
    }


    @GetMapping("/downloadForWindows/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String range = request.getHeader("Range");
        long start = 0;
        long end = Long.MAX_VALUE;

        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring("bytes=".length());
            String[] parts = r.split("-");
            try {
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && parts[1].length() > 0) {
                    end = Long.parseLong(parts[1]);
                }
            } catch (NumberFormatException ignored) {}
        }
        if (range == null) {
            // 🔹 客户端没传 Range，返回完整文件 → 状态码 200
            return downloadManager.streamFull(id, response);
        } else {
            // 🔹 客户端带 Range，返回分片 → 状态码 206
            return downloadManager.streamRange(id, start, end == Long.MAX_VALUE ? Long.MAX_VALUE : end, response);
        }

    }
}
