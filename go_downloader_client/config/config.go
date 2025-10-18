package config

import (
	"encoding/json"
	"log"
	"os"
)

type Config struct {
	Broker      string `json:"broker"`
	DownloadDir string `json:"download_dir"`
	Concurrency int    `json:"concurrency"`
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
