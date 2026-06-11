package dev.krillin.sparkplug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

/**
 * Late-joiner state-on-connect A/B experiment.
 *
 * Measures: while the edge is silent after NBIRTH, does a host that subscribes late
 * immediately receive current state?
 *   - A (non-aware broker): NO. NBIRTH is not retained → late host sees nothing.
 *     Recovery only via a Rebirth NCMD.
 *   - B (aware broker): YES. Broker mirrors NBIRTH as retained under
 *     $sparkplug/certificates/# → received immediately on subscribe.
 *
 * Run the same code against both brokers to observe the difference.
 * (Scenario B requires the HiveMQ Sparkplug-aware extension to be installed.)
 */
public class LateJoinerExperiment {

    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group = "Krillin";
        String edge = "Edge1";

        // 1) Edge comes online, publishes NBIRTH, then goes silent
        EdgeNode node = new EdgeNode(group, edge);
        node.connect(broker);
        node.birth();
        System.out.println("--- Edge: NBIRTH published, now silent (no NDATA) ---");
        Thread.sleep(2000);

        // 2) LATE host subscribes now — NBIRTH has already passed
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        MqttClient late = new MqttClient(broker, "late-host", new MemoryPersistence());
        late.setCallback(new MqttCallback() {
            public void connectionLost(Throwable c) { }
            public void deliveryComplete(IMqttDeliveryToken t) { }
            public void messageArrived(String topic, MqttMessage m) {
                received.add(topic);
                System.out.println("[LATE] << " + topic + "  (" + m.getPayload().length + " bytes)");
            }
        });
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        late.connect(o);
        late.subscribe("spBv1.0/" + group + "/#", 1);
        late.subscribe("$sparkplug/certificates/#", 1);   // aware broker delivers retained birth here
        System.out.println("--- LATE host subscribed (spBv1.0/#, $sparkplug/certificates/#). Observing for 3 s ---");
        Thread.sleep(3000);

        boolean gotOnConnect = !received.isEmpty();
        System.out.println(">>> [Verdict] Received current state on subscribe = "
                + (gotOnConnect ? "YES → aware broker" : "NO → late joiner blind (non-aware broker)")
                + "   topics received=" + received);

        // 3) Workaround: late host sends a Rebirth command directly
        received.clear();
        System.out.println("--- Workaround: LATE host publishing NCMD Rebirth ---");
        SparkplugBPayload reb = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .addMetric(new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, true).createMetric())
                .createPayload();
        late.publish("spBv1.0/" + group + "/NCMD/" + edge,
                new SparkplugBPayloadEncoder().getBytes(reb, false), 1, false);
        Thread.sleep(2000);
        System.out.println(">>> [Workaround result] LATE received after rebirth = " + received
                + (received.isEmpty() ? "" : "  <- current state now available"));

        late.disconnect();
        late.close();
        node.close();
        System.out.println("=== LATE JOINER EXPERIMENT DONE ===");
        System.exit(0);
    }
}
