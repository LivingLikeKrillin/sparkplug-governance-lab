package dev.krillin.sparkplug;

import java.util.Date;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

/**
 * One-shot: publishes NCMD "Node Control/Rebirth"=true to an external edge node,
 * causing it to re-publish NBIRTH/DBIRTH with full metric names.
 * Useful when Ignition/Cirrus Link Transmission is silent due to RBE and you need
 * the full metric set (including names) on demand.
 *
 * group/edge are hard-coded to Ignition Quick Start defaults (values contain spaces,
 * making exec.args quoting awkward).
 * Run: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.RebirthCmd
 */
public class RebirthCmd {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String group  = "My MQTT Group";
        String edge   = "Edge Node 99d88f";
        String topic  = "spBv1.0/" + group + "/NCMD/" + edge;

        MqttClient c = new MqttClient(broker, "rebirth-cmd", new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        c.connect(opts);

        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .addMetric(new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, true).createMetric())
                .createPayload();
        c.publish(topic, new SparkplugBPayloadEncoder().getBytes(p, false), 1, false);
        System.out.println(">> NCMD Rebirth -> " + topic);

        c.disconnect();
        c.close();
    }
}
