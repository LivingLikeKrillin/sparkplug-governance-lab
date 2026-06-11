package dev.krillin.sparkplug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * Protobuf opacity vs JSON UNS bridge demo.
 * Edge (Sparkplug protobuf) → Bridge (decode + retained JSON republish) → a late plain-JSON
 * consumer subscribes to uns/# only and immediately reads retained current state
 * (contrast with the late-joiner blind scenario — JSON + retained gives state-on-connect).
 * Prerequisite: docker compose up -d.
 */
public class JsonBridgeDemo {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group = "Krillin", edge = "Edge1";

        SparkplugToJsonBridge bridge = new SparkplugToJsonBridge(group);
        bridge.connect(broker);
        Thread.sleep(300);

        EdgeNode node = new EdgeNode(group, edge);
        node.connect(broker);
        node.birth();           // name+alias definitions — bridge learns the mapping and republishes as JSON
        node.publishData();     // alias-only — bridge resolves alias→name and republishes as JSON
        node.publishData();
        Thread.sleep(800);

        System.out.println("\n--- Late plain-JSON consumer subscribes to uns/# (no Sparkplug knowledge, cannot read protobuf) ---");
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        MqttClient jsonConsumer = new MqttClient(broker, "json-consumer", new MemoryPersistence());
        jsonConsumer.setCallback(new MqttCallback() {
            public void connectionLost(Throwable c) { }
            public void deliveryComplete(IMqttDeliveryToken t) { }
            public void messageArrived(String topic, MqttMessage m) {
                got.add(topic);
                System.out.println("[JSON-CONSUMER] << " + topic + " = " + new String(m.getPayload()));
            }
        });
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        jsonConsumer.connect(o);
        jsonConsumer.subscribe("uns/" + group + "/#", 1);   // retained — current state delivered immediately on subscribe
        Thread.sleep(1500);

        System.out.println("\n>>> [Observation] JSON consumer receives current state immediately from retained JSON");
        System.out.println(">>> without any Sparkplug/protobuf knowledge (" + got.size() + " message(s)).");
        System.out.println(">>> Trade-off: two namespaces (spBv1.0 protobuf + uns JSON) — bridge maintenance and schema drift.");
        System.out.println(">>> The bridge must be Sparkplug-stateful (tracking NBIRTH name<->alias mappings) — not a simple republisher.");

        jsonConsumer.disconnect(); jsonConsumer.close();
        node.close(); bridge.close();
        System.out.println("\n=== JSON BRIDGE DEMO DONE ===");
        System.exit(0);
    }
}
