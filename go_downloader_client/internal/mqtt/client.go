package mqtt

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"log"
	"time"

	"mqtt_downloader_go/config"
	"mqtt_downloader_go/internal/downloader"

	MQTT "github.com/eclipse/paho.mqtt.golang"
)

type DownloadTask struct {
	URL      string `json:"url"`
	File     string `json:"fileName"`
	FileSize int64  `json:"fileSize"`
}

func StartMQTTClient(cfg config.Config) {
	var clientId = uuid.NewString()
	var donwload_topic = "FilesDownload"
	var ack_topic = "FilesAck"
	opts := MQTT.NewClientOptions().AddBroker(cfg.Broker)
	opts.SetClientID(clientId)
	opts.SetAutoReconnect(true)

	opts.OnConnect = func(c MQTT.Client) {
		log.Println("✅ Connected to MQTT broker")

		if token := c.Subscribe(donwload_topic, 1, func(client MQTT.Client, msg MQTT.Message) {
			var task DownloadTask
			if err := json.Unmarshal(msg.Payload(), &task); err != nil {
				log.Printf("❌ Invalid message: %v", err)
				return
			}

			go func() {
				log.Printf("📥 Download task: %s", task.File)
				ctx := context.Background()

				err := downloader.DownloadFile(ctx, task.URL, cfg.DownloadDir+"/"+task.File, cfg.Concurrency, task.FileSize, func(p, s float64, d, t int64) {
					log.Printf("⬇️ %.2f%% %.2fKB/s (%d/%d)", p, s, d, t)
				})

				if err != nil {
					log.Printf("❌ Download failed: %v", err)
					client.Publish(ack_topic, 1, false, fmt.Sprintf(`{"clientId":"%s","file":"%s","status":"failed"}`, clientId, task.File))
					return
				}

				client.Publish(ack_topic, 1, false, fmt.Sprintf(`{"clientId":"%s","file":"%s","status":"done"}`, clientId, task.File))
				log.Printf("✅ Sent ACK for %s", task.File)
			}()
		}); token.Wait() && token.Error() != nil {
			log.Fatal(token.Error())
		}
	}

	client := MQTT.NewClient(opts)
	if token := client.Connect(); token.Wait() && token.Error() != nil {
		log.Fatal(token.Error())
	}

	for {
		time.Sleep(10 * time.Second)
	}
}
