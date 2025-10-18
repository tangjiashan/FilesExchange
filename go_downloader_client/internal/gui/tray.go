package gui

import (
	"github.com/getlantern/systray"
	"log"
)

func onReady() {
	systray.SetTitle("MQTT Downloader")
	systray.SetTooltip("MQTT File Downloader Running")
	quit := systray.AddMenuItem("Quit", "Exit the application")
	go func() {
		<-quit.ClickedCh
		log.Println("👋 Exiting...")
		systray.Quit()
	}()
}

func onExit() {}

func Run() {
	systray.Run(onReady, onExit)
}
