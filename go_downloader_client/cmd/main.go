package main

import (
	"io"
	"log"
	"mqtt_downloader_go/internal/gui"
	"mqtt_downloader_go/internal/mqtt"
	"os"
	"path/filepath"

	"mqtt_downloader_go/config"
)

func main() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("❌ Panic: %v", r)
		}
	}()

	// 📁 确保日志目录存在
	logDir := "logs"
	_ = os.MkdirAll(logDir, 0755)

	// 📝 打开日志文件（追加写）
	logPath := filepath.Join(logDir, "downloader.log")
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		panic(err)
	}

	// 🖥️ 同时输出到控制台 + 文件
	log.SetOutput(io.MultiWriter(os.Stdout, logFile))
	log.SetFlags(log.LstdFlags | log.Lshortfile) // 显示时间 + 文件名行号

	// 载入配置
	cfg := config.LoadConfig()
	go mqtt.StartMQTTClient(cfg)

	// ✅ 只保留这一个 systray.Run
	gui.Run()

}
