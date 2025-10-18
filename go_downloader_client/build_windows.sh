#!/bin/bash
set -e

echo "🧩 Generating Windows resources..."
go-winres make --in winres/winres.json

echo "🔧 Building Windows executable..."
GOOS=windows GOARCH=amd64 go build -o dist/mqtt_downloader.exe ./cmd


echo "✅ Build complete: dist/mqtt_downloader.exe"
