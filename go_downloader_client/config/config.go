package config

import (
	"encoding/json"
	"log"
	"os"
)

type Config struct {
	Broker       string `json:"broker"`
	Topic        string `json:"topic"`
	AckTopic     string `json:"ack_topic"`
	DownloadDir  string `json:"download_dir"`
	Concurrency  int    `json:"concurrency"`
	ComputerName string `json:"computer_name"`
}

func LoadConfig() Config {
	file, err := os.Open("config.json")
	if err != nil {
		log.Fatalf("Failed to open config.json: %v", err)
	}
	defer file.Close()
	var cfg Config
	if err := json.NewDecoder(file).Decode(&cfg); err != nil {
		log.Fatalf("Failed to parse config.json: %v", err)
	}
	return cfg
}
