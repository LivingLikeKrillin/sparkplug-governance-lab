package dev.krillin.sparkplug;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Broker connectivity smoke test — plain MQTT (Paho v3) connect → subscribe → publish → receive.
 * Not a Sparkplug session (no birth/death/STATE). Purpose: verify that HiveMQ accepts
 * connections and routes messages at the MQTT level.
 * Prerequisite: docker compose up -d (tcp://localhost:1883).
 */
public class MqttSmoke {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String topic = "krillin/smoke";
        MqttClient client = new MqttClient(broker, "krillin-mqtt-smoke", new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        client.connect(opts);
        System.out.println("[connect] connected=" + client.isConnected() + " -> " + broker);

        CountDownLatch latch = new CountDownLatch(1);
        client.subscribe(topic, (t, msg) -> {
            System.out.println("[receive] topic=" + t + " payload=" + new String(msg.getPayload()));
            latch.countDown();
        });

        client.publish(topic, new MqttMessage("hello-sparkplug".getBytes()));
        System.out.println("[publish] sent to " + topic);

        boolean got = latch.await(5, TimeUnit.SECONDS);
        client.disconnect();
        client.close();
        System.out.println(got ? "MQTT SMOKE OK" : "MQTT SMOKE FAILED (no message in 5s)");
        if (!got) System.exit(1);
    }
}
