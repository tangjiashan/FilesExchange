package com.slice.reactminiospring.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slice.reactminiospring.entity.Files;
import com.slice.reactminiospring.mapper.FilesMapper;
import com.slice.reactminiospring.model.CachedObjectStat;
import com.slice.reactminiospring.util.MinioUtil;
import com.slice.reactminiospring.util.RedisUtil;
import io.minio.GetObjectResponse;
import io.minio.StatObjectResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DownloadManager (完整版本)
 * - 双层缓存：Caffeine (local) + Redis (shared)
 * - 分片 & 整文件支持（streamRange + streamFull）
 * - in-flight dedupe（单节点）
 * - 重试、原子写入 tmp -> rename
 *
 * 注意：
 * - Redis 存储的是轻量 DTO CachedObjectStat，而不是 MinIO SDK 对象
 * - 若需跨节点 in-flight 去重，请在 fetchAndCacheChunk 前加分布式锁（Redisson）
 */
@Service
public class DownloadManager {
    private static final Logger log = Logger.getLogger("DownloadManager");

    @Resource private MinioUtil minioUtil;
    @Resource private FilesMapper filesMapper;
    @Resource private RedisUtil redisUtil;

    private ExecutorService downloadPool;
    private Cache<String, byte[]> chunkCache;           // key: object:start-end
    private Cache<Long, Files> fileMetaCache;           // key: fileId
    private Cache<String, CachedObjectStat> objectStatCache; // key: objectKey
    private final ConcurrentMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();

    private final int POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    // 若文件大于该阈值（bytes），streamFull 将采取流式转发而不是一次性加载到内存
    private final long STREAM_DIRECT_THRESHOLD = 64L * 1024L * 1024L; // 64 MB

    @PostConstruct
    public void init() {
        downloadPool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });

        chunkCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();

        fileMetaCache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(Duration.ofHours(12))
                .build();

        objectStatCache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(Duration.ofHours(6))
                .build();

        log.info("DownloadManager initialized. poolSize=" + POOL_SIZE);
    }

    // -------- 双层缓存：Files meta --------
    private Files getFileMeta(Long fileId) {
        Files cached = fileMetaCache.getIfPresent(fileId);
        if (cached != null) return cached;

        Object redisObj = redisUtil.get("file:" + fileId);
        if (redisObj instanceof Files) {
            Files r = (Files) redisObj;
            fileMetaCache.put(fileId, r);
            return r;
        }

        Files db = filesMapper.selectById(fileId);
        if (db != null) {
            redisUtil.set("file:" + fileId, db, 1, TimeUnit.DAYS);
            fileMetaCache.put(fileId, db);
        }
        return db;
    }

    // -------- 双层缓存：MinIO object stat (use CachedObjectStat) --------
    private CachedObjectStat getObjectStat(String objectKey) throws Exception {
        CachedObjectStat cached = objectStatCache.getIfPresent(objectKey);
        if (cached != null) return cached;

        Object redisObj = redisUtil.get("stat:" + objectKey);
        if (redisObj instanceof CachedObjectStat) {
            CachedObjectStat r = (CachedObjectStat) redisObj;
            objectStatCache.put(objectKey, r);
            return r;
        }

        // fetch from MinIO
        StatObjectResponse stat = minioUtil.statObject(objectKey);
        if (stat == null) throw new IOException("statObject returned null for " + objectKey);

        CachedObjectStat cs = new CachedObjectStat(
                objectKey,
                stat.size(),
                stat.etag(),
                stat.lastModified().toString()
        );
        // save to redis + local cache
        redisUtil.set("stat:" + objectKey, cs, 6, TimeUnit.HOURS);
        objectStatCache.put(objectKey, cs);
        return cs;
    }

    // -------- streamRange（分片下载，返回 206） --------
    public ResponseEntity<byte[]> streamRange(Long fileId, long start, long end, HttpServletResponse response) throws IOException {
        Files file = getFileMeta(fileId);
        if (file == null) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        String object = file.getObject();
        CachedObjectStat stat;
        try {
            stat = getObjectStat(object);
        } catch (Exception e) {
            log.log(Level.WARNING, "statObject failed: " + e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        long total = stat.getSize();
        if (start < 0) start = 0;
        if (end >= total) end = total - 1;
        if (start > end) return new ResponseEntity<>(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);

        String chunkKey = object + ":" + start + "-" + end;

        // 1) 本地内存缓存
        byte[] cached = chunkCache.getIfPresent(chunkKey);
        if (cached != null) {
            log.info("chunkCache HIT " + chunkKey);
            writeRangeHeaders(response, file.getOriginFileName(), start, end, total, stat);
            try {
                response.getOutputStream().write(cached);
                response.flushBuffer();
            } catch (IOException ioe) {
                log.log(Level.WARNING, "client write aborted: " + ioe.getMessage());
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new ResponseEntity<>(cached, HttpStatus.PARTIAL_CONTENT);
        }

        // 2) 磁盘缓存
        File diskPart = diskPartFile(object, start, end);
        if (diskPart.exists() && diskPart.length() == (end - start + 1)) {
            log.info("disk cache HIT " + diskPart.getAbsolutePath());
            byte[] buf = readAllBytes(diskPart);
            chunkCache.put(chunkKey, buf);
            writeRangeHeaders(response, file.getOriginFileName(), start, end, total, stat);
            response.getOutputStream().write(buf);
            response.flushBuffer();
            return new ResponseEntity<>(buf, HttpStatus.PARTIAL_CONTENT);
        }

        // 3) in-flight dedupe + fetch
        long finalStart = start, finalEnd = end;
        CompletableFuture<byte[]> future = inFlight.computeIfAbsent(chunkKey, k -> {
            CompletableFuture<byte[]> cf = new CompletableFuture<>();
            downloadPool.submit(() -> {
                try {
                    byte[] data = fetchAndCacheChunk(object, finalStart, finalEnd, diskPart);
                    cf.complete(data);
                } catch (Throwable t) {
                    cf.completeExceptionally(t);
                } finally {
                    inFlight.remove(chunkKey);
                }
            });
            return cf;
        });

        try {
            byte[] data = future.get(6, TimeUnit.MINUTES);
            writeRangeHeaders(response, file.getOriginFileName(), start, end, total, stat);
            response.getOutputStream().write(data);
            response.flushBuffer();
            return new ResponseEntity<>(data, HttpStatus.PARTIAL_CONTENT);
        } catch (TimeoutException te) {
            log.log(Level.WARNING, "fetch chunk timeout: " + te.getMessage(), te);
            return new ResponseEntity<>(HttpStatus.GATEWAY_TIMEOUT);
        } catch (ExecutionException ee) {
            log.log(Level.WARNING, "fetch chunk failed: " + ee.getMessage(), ee);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // -------- streamFull（整文件下载，返回 200） --------
    public ResponseEntity<byte[]> streamFull(Long fileId, HttpServletResponse response) throws IOException {
        Files file = getFileMeta(fileId);
        if (file == null) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        String object = file.getObject();
        CachedObjectStat stat;
        try {
            stat = getObjectStat(object);
        } catch (Exception e) {
            log.log(Level.WARNING, "statObject failed: " + e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        long total = stat.getSize();
        String fullKey = object + ":FULL";

        // 1) try memory cache for full
        byte[] memFull = chunkCache.getIfPresent(fullKey);
        if (memFull != null) {
            log.info("full memory cache HIT " + fullKey);
            writeFullHeaders(response, file.getOriginFileName(), total, stat);
            response.getOutputStream().write(memFull);
            response.flushBuffer();
            return new ResponseEntity<>(memFull, HttpStatus.OK);
        }

        // 2) try disk cache for full
        File fullFile = diskFullFile(object);
        if (fullFile.exists() && fullFile.length() == total) {
            log.info("full disk cache HIT " + fullFile.getAbsolutePath());
            // stream file to response (avoid reading entire file into mem)
            writeFullHeaders(response, file.getOriginFileName(), total, stat);
            try (InputStream in = new FileInputStream(fullFile);
                 OutputStream out = response.getOutputStream()) {
                copyWithClientAbortHandling(in, out);
                response.flushBuffer();
            } catch (IOException ioe) {
                log.log(Level.WARNING, "client aborted or write failed: " + ioe.getMessage());
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new ResponseEntity<>(HttpStatus.OK);
        }

        // 3) in-flight dedupe for full
        CompletableFuture<byte[]> futureFull = inFlight.computeIfAbsent(fullKey, k -> {
            CompletableFuture<byte[]> cf = new CompletableFuture<>();
            downloadPool.submit(() -> {
                try {
                    // If file small enough, fetch as chunk into memory
                    if (total <= STREAM_DIRECT_THRESHOLD) {
                        // reuse fetchAndCacheChunk for full range
                        File diskPart = diskFullFile(object); // temp path
                        byte[] data = fetchAndCacheChunk(object, 0, total - 1, diskPart);
                        cf.complete(data);
                    } else {
                        // large file: stream from MinIO to disk, then stream to clients
                        File tmp = new File(diskFullFile(object).getAbsolutePath() + ".tmp");
                        tmp.getParentFile().mkdirs();
                        GetObjectResponse resp = null;
                        try (OutputStream out = new FileOutputStream(tmp)) {
                            resp = minioUtil.getObject(object, Long.valueOf(0), total);
                            byte[] buf = new byte[64 * 1024];
                            int r;
                            while ((r = resp.read(buf)) != -1) {
                                out.write(buf, 0, r);
                            }
                            out.flush();
                        } finally {
                            if (resp != null) {
                                try { resp.close(); } catch (Exception ignore) {}
                            }
                        }
                        File finalFull = diskFullFile(object);
                        if (finalFull.exists()) finalFull.delete();
                        if (!tmp.renameTo(finalFull)) {
                            // fallback copy
                            try (InputStream in = new FileInputStream(tmp);
                                 OutputStream out = new FileOutputStream(finalFull)) {
                                byte[] b = new byte[32 * 1024];
                                int rr;
                                while ((rr = in.read(b)) != -1) out.write(b, 0, rr);
                            }
                            tmp.delete();
                        }
                        // don't load entire into memory for big file; return empty byte[] and rely on disk streaming
                        cf.complete(new byte[0]);
                    }
                } catch (Throwable t) {
                    cf.completeExceptionally(t);
                } finally {
                    inFlight.remove(fullKey);
                }
            });
            return cf;
        });

        try {
            byte[] res = futureFull.get(15, TimeUnit.MINUTES);
            if (res != null && res.length > 0) {
                // small file data already in memory
                writeFullHeaders(response, file.getOriginFileName(), total, stat);
                response.getOutputStream().write(res);
                response.flushBuffer();
                // save memory-full into chunkCache as FULL key
                chunkCache.put(fullKey, res);
                return new ResponseEntity<>(res, HttpStatus.OK);
            } else {
                // large file: now diskFullFile should exist; stream it
                File finalFull = diskFullFile(object);
                if (!finalFull.exists()) {
                    log.warning("expected full file after fetch but not found: " + finalFull.getAbsolutePath());
                    return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                writeFullHeaders(response, file.getOriginFileName(), total, stat);
                try (InputStream in = new FileInputStream(finalFull)) {
                    copyWithClientAbortHandling(in, response.getOutputStream());
                    response.flushBuffer();
                } catch (IOException ioe) {
                    log.log(Level.WARNING, "client aborted/write failed: " + ioe.getMessage());
                    return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                return new ResponseEntity<>(HttpStatus.OK);
            }
        } catch (TimeoutException te) {
            log.log(Level.WARNING, "fetch full timeout: " + te.getMessage(), te);
            return new ResponseEntity<>(HttpStatus.GATEWAY_TIMEOUT);
        } catch (ExecutionException ee) {
            log.log(Level.WARNING, "fetch full failed: " + ee.getMessage(), ee);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // -------- fetchAndCacheChunk: fetch from MinIO, write tmp -> rename, cache memory if small --------
    private byte[] fetchAndCacheChunk(String object, long start, long end, File diskPart) throws IOException {
        final int MAX_RETRIES = 3;
        final long RETRY_BACKOFF_MS = 1000L;
        Exception lastEx = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (!diskPart.getParentFile().exists()) diskPart.getParentFile().mkdirs();

            GetObjectResponse resp = null;
            try {
                long length = end - start + 1;
                log.info(String.format("Fetching object=%s range=%d-%d attempt=%d", object, start, end, attempt));
                resp = minioUtil.getObject(object, start, length);

                File tmp = new File(diskPart.getAbsolutePath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = resp.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                    out.flush();
                }

                if (diskPart.exists()) diskPart.delete();
                if (!tmp.renameTo(diskPart)) {
                    try (InputStream in = new FileInputStream(tmp);
                         OutputStream out = new FileOutputStream(diskPart)) {
                        byte[] buf2 = new byte[32 * 1024];
                        int rr;
                        while ((rr = in.read(buf2)) != -1) out.write(buf2, 0, rr);
                    }
                    tmp.delete();
                }

                byte[] data = readAllBytes(diskPart);
                if (data.length <= 16 * 1024 * 1024) { // small chunk keep in mem
                    String chunkKey = object + ":" + start + "-" + end;
                    chunkCache.put(chunkKey, data);
                }
                return data;
            } catch (Exception ex) {
                lastEx = ex;
                log.log(Level.WARNING, "fetch attempt failed: " + ex.getMessage(), ex);
                try { Thread.sleep(RETRY_BACKOFF_MS * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("interrupted", ie); }
            } finally {
                if (resp != null) {
                    try { resp.close(); } catch (Exception ignore) {}
                }
            }
        }
        throw new IOException("fetch failed after retries", lastEx);
    }

    // -------- 工具：读全部字节（谨慎：用于小文件/分片） --------
    private static byte[] readAllBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[32 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    // -------- 磁盘路径生成器 --------
    private static File diskPartFile(String object, long start, long end) {
        String safe = Integer.toHexString(Objects.hashCode(object));
        String folder = "cache" + File.separator + safe;
        String fileName = String.format("part-%d-%d", start, end);
        return new File(folder, fileName);
    }
    private static File diskFullFile(String object) {
        String safe = Integer.toHexString(Objects.hashCode(object));
        String folder = "cache" + File.separator + safe;
        String fileName = "full";
        return new File(folder, fileName);
    }

    // -------- Headers helpers --------
    private static void writeRangeHeaders(HttpServletResponse response, String fileName, long start, long end, long total, CachedObjectStat stat) {
        try {
            response.setHeader("Accept-Ranges", "bytes");
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Last-Modified", stat.getLastModified());
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            String asciiName = new String(fileNameBytes, 0, fileNameBytes.length, StandardCharsets.ISO_8859_1);
            response.setHeader("Content-Disposition", "attachment;filename=" + asciiName);
            response.setHeader("Content-Length", String.valueOf(end - start + 1));
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
            response.setHeader("ETag", "\"".concat(stat.getEtag()).concat("\""));
            response.setContentType("application/octet-stream;charset=UTF-8");
        } catch (Exception e) { /* ignore */ }
    }

    private static void writeFullHeaders(HttpServletResponse response, String fileName, long total, CachedObjectStat stat) {
        try {
            response.setHeader("Accept-Ranges", "bytes");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("Last-Modified", stat.getLastModified());
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            String asciiName = new String(fileNameBytes, 0, fileNameBytes.length, StandardCharsets.ISO_8859_1);
            response.setHeader("Content-Disposition", "attachment;filename=" + asciiName);
            response.setHeader("Content-Length", String.valueOf(total));
            response.setHeader("ETag", "\"".concat(stat.getEtag()).concat("\""));
            response.setContentType("application/octet-stream;charset=UTF-8");
        } catch (Exception e) { /* ignore */ }
    }

    // -------- 安全复制：处理客户端中断 (Broken pipe) --------
    private static void copyWithClientAbortHandling(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        try {
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        } catch (IOException ioe) {
            // client may have aborted — log and rethrow to upper layer to handle gracefully
            throw ioe;
        }
    }
}
