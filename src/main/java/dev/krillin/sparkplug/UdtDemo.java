package dev.krillin.sparkplug;

import java.util.Date;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.Parameter;
import org.eclipse.tahu.message.model.ParameterDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

/**
 * UDT (Template) demo + schema version drift.
 * - Publishes a "Motor" UDT definition and an instance (Motors/Motor1) in NBIRTH;
 *   the host decodes and prints the schema and values.
 * - Then publishes v2 (adds a Temperature member) to show that the schema is silently
 *   replaced with no registry check or compatibility enforcement.
 * Prerequisite: docker compose up -d.
 */
public class UdtDemo {

    static final String GROUP = "Krillin", EDGE = "UdtEdge";
    static final String T_NBIRTH = "spBv1.0/" + GROUP + "/NBIRTH/" + EDGE;
    static final SparkplugBPayloadEncoder ENC = new SparkplugBPayloadEncoder();

    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";

        // host: subscribe and print Template decode
        MqttClient host = new MqttClient(broker, "udt-host", new MemoryPersistence());
        host.setCallback(new MqttCallback() {
            final SparkplugBPayloadDecoder dec = new SparkplugBPayloadDecoder();
            public void connectionLost(Throwable c) { }
            public void deliveryComplete(IMqttDeliveryToken t) { }
            public void messageArrived(String topic, MqttMessage m) throws Exception {
                SparkplugBPayload p = dec.buildFromByteArray(m.getPayload(), null);
                System.out.println("[HOST] << NBIRTH seq=" + p.getSeq());
                for (Metric metric : p.getMetrics()) {
                    if (metric.getDataType() == MetricDataType.Template) {
                        Template t = (Template) metric.getValue();
                        System.out.println("  [Template] metric='" + metric.getName() + "'  definition=" + t.isDefinition()
                                + "  ref=" + t.getTemplateRef() + "  version=" + t.getVersion());
                        for (Parameter pa : t.getParameters())
                            System.out.println("      param  " + pa.getName() + " = " + pa.getValue());
                        for (Metric mm : t.getMetrics())
                            System.out.println("      member " + mm.getName() + " : " + mm.getDataType() + " = " + mm.getValue());
                    }
                }
            }
        });
        MqttConnectOptions o = new MqttConnectOptions(); o.setCleanSession(true);
        host.connect(o);
        host.subscribe("spBv1.0/" + GROUP + "/#", 1);
        System.out.println("[HOST] subscribed spBv1.0/" + GROUP + "/#");
        Thread.sleep(400);

        MqttClient pub = new MqttClient(broker, "udt-pub", new MemoryPersistence());
        pub.connect(o);

        // ---- v1: Motor {Rpm, Running} ----
        System.out.println("\n=== Publishing: Motor UDT v1.0 (definition + instance) ===");
        publishBirth(pub, motorDef("1.0", false), motorInstance("1.0", false));
        Thread.sleep(1200);

        // ---- v2: Motor {Rpm, Running, +Temperature} ----
        System.out.println("\n=== Publishing: Motor UDT v2.0 (adds Temperature member) ===");
        publishBirth(pub, motorDef("2.0", true), motorInstance("2.0", true));
        Thread.sleep(1200);

        System.out.println("\n>>> [Governance observation] The schema is replaced from v1.0 to v2.0 with no enforcement.");
        System.out.println(">>> The broker and protocol perform no (1) compatibility check, (2) registry lookup, or");
        System.out.println(">>> (3) migration of existing instances. Consumers built against the v1 schema may break");
        System.out.println(">>> silently when a member is added or changed.");

        pub.disconnect(); pub.close(); host.disconnect(); host.close();
        System.out.println("\n=== UDT DEMO DONE ===");
        System.exit(0);
    }

    static Template motorDef(String version, boolean withTemp) throws Exception {
        TemplateBuilder b = new TemplateBuilder().version(version).definition(true)
                .addParameter(new Parameter("Location", ParameterDataType.String, "PlantA/Line1"))
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, false).createMetric());
        if (withTemp) b.addMetric(new MetricBuilder("Temperature", MetricDataType.Double, 0.0).createMetric());
        return b.createTemplate();
    }

    static Template motorInstance(String version, boolean withTemp) throws Exception {
        TemplateBuilder b = new TemplateBuilder().version(version).templateRef("Motor").definition(false)
                .addParameter(new Parameter("Location", ParameterDataType.String, "PlantA/Line1"))
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 1500.0).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, true).createMetric());
        if (withTemp) b.addMetric(new MetricBuilder("Temperature", MetricDataType.Double, 65.4).createMetric());
        return b.createTemplate();
    }

    static void publishBirth(MqttClient pub, Template def, Template inst) throws Exception {
        SparkplugBPayload p = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .setSeq(0L)
                .addMetric(new MetricBuilder("_types_/Motor", MetricDataType.Template, def).createMetric())
                .addMetric(new MetricBuilder("Motors/Motor1", MetricDataType.Template, inst).createMetric())
                .createPayload();
        pub.publish(T_NBIRTH, ENC.getBytes(p, false), 0, false);
        System.out.println("[PUB] >> NBIRTH with _types_/Motor (def) + Motors/Motor1 (instance)");
    }
}
