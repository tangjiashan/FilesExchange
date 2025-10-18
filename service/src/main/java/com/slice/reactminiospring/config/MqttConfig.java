package com.slice.reactminiospring.config;

import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
public class MqttConfig {

    @Value("${mqtt.server.uri}")
    private String mqttServerUri;

    @Value("${mqtt.client.id}")
    private String clientId;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    private ScheduledExecutorService scheduler;

    @Bean
    public MqttClient mqttClient() throws MqttException {
        MqttClient client = new MqttClient(mqttServerUri, clientId, new MemoryPersistence());

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setAutomaticReconnect(true);

        if (username != null && !username.isEmpty()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }

        // ✅ 添加连接回调
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("⚠️ MQTT连接丢失: " + cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // 这里通常是订阅者使用
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                System.out.println(reconnect
                        ? "🔄 MQTT已重新连接: " + serverURI
                        : "✅ MQTT初始连接成功: " + serverURI);
            }
        });

        // ✅ 启动时连接
        connectWithRetry(client, options);

        // ✅ 每隔30秒检测连接状态，断开则自动重连
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!client.isConnected()) {
                    System.out.println("🚨 MQTT掉线，正在尝试重新连接...");
                    connectWithRetry(client, options);
                }
            } catch (Exception e) {
                System.err.println("❌ MQTT健康检查异常: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        return client;
    }

    private void connectWithRetry(MqttClient client, MqttConnectOptions options) {
        int retry = 0;
        while (!client.isConnected() && retry < 5) {
            try {
                client.connect(options);
                System.out.println("✅ MQTT已连接到: " + mqttServerUri);
            } catch (Exception e) {
                retry++;
                System.err.println("⚠️ MQTT连接失败(" + retry + ")次, 2秒后重试...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
