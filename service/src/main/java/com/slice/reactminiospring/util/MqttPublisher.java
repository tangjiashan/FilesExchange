package com.slice.reactminiospring.util;


import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

@Component
public class MqttPublisher {

    private final MqttClient mqttClient;

    public MqttPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }


    /**
     * 发布消息到指定主题
     * @param topic MQTT 主题
     * @param payload 消息内容
     */
    public void publish(String topic, String payload) {
        publish(topic, payload, 1, false);
    }

    /**
     * 发布消息（可选QoS和保留）
     */
    public void publish(String topic, String payload, int qos, boolean retained) {
        try {
            if (!mqttClient.isConnected()) {
                System.out.println("⚠️ MQTT未连接，尝试重新连接...");
                mqttClient.reconnect();
            }

            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            message.setRetained(retained);

            mqttClient.publish(topic, message);
            System.out.printf("📤 已发布消息到 [%s]: %s%n", topic, payload);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
