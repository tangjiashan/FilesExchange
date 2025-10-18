package downloader

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"
)

// 下载状态回调（GUI 或日志）
type ProgressCallback func(percent float64, speedKBs float64, downloaded, total int64)

// DownloadFile 分片并发下载 + 断点续传 + 日志
func DownloadFile(ctx context.Context, url, dest string, concurrency int, fileSize int64, cb ProgressCallback) error {
	os.MkdirAll(filepath.Dir(dest), 0755)
	log.Printf("🚀 Start downloading %s → %s", url, dest)

	total := fileSize // 已从MQTT获得，不再请求HEAD

	partCount, partSize := calcPartSize(total)
	log.Printf("📦 动态分片: %d 个分片, 每片 ~ %.2f MB", partCount, float64(partSize)/1024.0/1024.0)

	// 小文件直接下载
	if partCount == 1 {
		log.Println("📥 文件较小，使用单线程直接下载")
		return downloadSingle(ctx, url, dest)
	}
	tmpDir := dest + ".parts"
	os.MkdirAll(tmpDir, 0755)

	var downloaded int64
	for i := 0; i < partCount; i++ {
		partPath := filepath.Join(tmpDir, fmt.Sprintf("part-%02d", i))
		if fi, err := os.Stat(partPath); err == nil {
			atomic.AddInt64(&downloaded, fi.Size())
		}
	}

	var wg sync.WaitGroup
	errCh := make(chan error, partCount)
	client := &http.Client{Timeout: 5 * time.Minute}

	startTime := time.Now()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	for i := 0; i < partCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			start := int64(idx) * partSize
			end := start + partSize - 1
			if end >= total {
				end = total - 1
			}

			partPath := filepath.Join(tmpDir, fmt.Sprintf("part-%02d", idx))
			const maxRetries = 3
			for retry := 0; retry < maxRetries; retry++ {
				err := downloadPart(ctx, client, url, partPath, start, end, &downloaded)
				if err == nil {
					return
				}
				log.Printf("⚠️ 分片 %d 下载失败 (尝试 %d/%d): %v", idx, retry+1, maxRetries, err)
				time.Sleep(time.Second * 2)
			}
			errCh <- fmt.Errorf("分片 %d 下载失败超过重试次数", idx)
			cancel()
		}(i)
	}

	// 进度监控
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				curr := atomic.LoadInt64(&downloaded)
				percent := float64(curr) / float64(total) * 100
				speed := float64(curr) / 1024.0 / time.Since(startTime).Seconds()
				if cb != nil {
					cb(percent, speed, curr, total)
				}
			case <-done:
				return
			case <-ctx.Done():
				return
			}
		}
	}()

	wg.Wait()
	close(done)

	select {
	case e := <-errCh:
		return e
	default:
		// 合并
		if err := mergeParts(tmpDir, dest, partCount); err != nil {
			return err
		}
		_ = os.RemoveAll(tmpDir)
		log.Printf("✅ 完成: %s", dest)
		return nil
	}
}

// 断点续传分片下载
func downloadPart(ctx context.Context, client *http.Client, url, path string, start, end int64, downloaded *int64) error {
	var existing int64
	if fi, err := os.Stat(path); err == nil {
		existing = fi.Size()
		if existing >= end-start+1 {
			atomic.AddInt64(downloaded, existing)
			return nil
		}
	}

	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start+existing, end))
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	defer f.Close()

	buf := make([]byte, 64*1024)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			atomic.AddInt64(downloaded, int64(n))
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// 根据文件大小动态决定分片大小
func calcPartSize(fileSize int64) (int, int64) {
	switch {
	case fileSize < 10*1024*1024: // 小于10MB
		return 1, fileSize
	case fileSize < 100*1024*1024: // 10~100MB
		partSize := int64(5 * 1024 * 1024)
		return int((fileSize + partSize - 1) / partSize), partSize
	case fileSize < 1024*1024*1024: // 100MB~1GB
		partSize := int64(10 * 1024 * 1024)
		return int((fileSize + partSize - 1) / partSize), partSize
	default:
		partSize := int64(20 * 1024 * 1024)
		return int((fileSize + partSize - 1) / partSize), partSize
	}
}

// 合并所有分片
func mergeParts(tmpDir, dest string, parts int) error {
	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer out.Close()

	for i := 0; i < parts; i++ {
		partPath := filepath.Join(tmpDir, fmt.Sprintf("part-%02d", i))
		in, err := os.Open(partPath)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			in.Close()
			return err
		}
		in.Close()
	}
	return nil
}

func downloadSingle(ctx context.Context, url, dest string) error {
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return fmt.Errorf("unexpected status: %s", resp.Status)
	}

	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()

	_, err = io.Copy(f, resp.Body)
	return err
}
