package dev.krillin.sparkplug;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

import dev.krillin.sparkplug.schema.DefinitionStore;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.SemVer;
import dev.krillin.sparkplug.schema.UdtDefinition;
// DriftMonitor is in the same package (dev.krillin.sparkplug) — no import needed

/**
 * Runtime drift detection demo (detect-only). Uses an isolated temporary registry so the shared
 * registry directory is not affected.
 * Scope: detect-only (OT data is never dropped) / node-level / staleness uses real clock with a
 * short threshold / single-process PoC.
 * To run: docker compose up -d, then from the repo root:
 *   mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.DriftMonitorDemo
 */
public class DriftMonitorDemo {
    static final String BROKER = "tcp://localhost:1883";
    static final String GROUP = "Acme:Busan:Press";
    static final SparkplugBPayloadEncoder ENC = new SparkplugBPayloadEncoder();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Runtime Drift Detection Demo (detect-only) ===");
        System.out.println("[scope] detect-only (OT never dropped) / node-level / staleness=real clock+short threshold / single-process PoC\n");

        Path reg = Files.createTempDirectory("drift-registry");   // isolated: shared registry/ directory is not touched
        new DefinitionStore(reg).promote(new UdtDefinition("Motor", SemVer.parse("1.1.0"),
                List.of(new Member("Rpm","Double"), new Member("Running","Boolean"), new Member("Temperature","Double")),
                List.of()));
        System.out.println("[seed] source-of-truth: Motor@1.1.0 {Rpm,Running,Temperature}\n");

        DriftMonitor monitor = new DriftMonitor(reg);
        monitor.connect(BROKER);
        MqttClient pub = new MqttClient(BROKER, "drift-pub", new MemoryPersistence());
        pub.connect();

        nbirth(pub, "L1:GW3", "Motor", motorConformant());  // A: conformant
        nbirth(pub, "L1:GW4", "Motor", motorDrifted());     // B: version + type + missing member drift
        nbirth(pub, "L1:GW5", "Pump", pumpDef());           // C: unregistered
        Thread.sleep(300);

        System.out.println("\n--- inducing silence: C publishes nothing, A and B refresh ---");
        Thread.sleep(700);                                  // B and C accumulate silence
        ndata(pub, "L1:GW3"); ndata(pub, "L1:GW4");         // A and B refresh — only C is a stale candidate
        Thread.sleep(150);

        System.out.println("\n--- health report (threshold 500 ms) ---");
        monitor.report(System.currentTimeMillis(), 500);

        Thread.sleep(200);
        pub.disconnect(); pub.close(); monitor.close();
        Files.walk(reg).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        System.out.println("\n=== DONE ===");
    }

    static Template motorConformant() throws Exception {
        return new TemplateBuilder().version("1.1.0").definition(true)
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, false).createMetric())
                .addMetric(new MetricBuilder("Temperature", MetricDataType.Double, 0.0).createMetric())
                .createTemplate();
    }
    static Template motorDrifted() throws Exception {     // 1.0.0, Running:String (type drift), Temperature absent (missing member)
        return new TemplateBuilder().version("1.0.0").definition(true)
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.String, "off").createMetric())
                .createTemplate();
    }
    static Template pumpDef() throws Exception {
        return new TemplateBuilder().version("1.0.0").definition(true)
                .addMetric(new MetricBuilder("Flow", MetricDataType.Double, 0.0).createMetric())
                .createTemplate();
    }

    static void nbirth(MqttClient pub, String edge, String ref, Template def) throws Exception {
        SparkplugBPayload p = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(0L)
                .addMetric(new MetricBuilder("bdSeq", MetricDataType.Int64, 0L).createMetric())
                .addMetric(new MetricBuilder("_types_/" + ref, MetricDataType.Template, def).createMetric())
                .createPayload();
        pub.publish("spBv1.0/" + GROUP + "/NBIRTH/" + edge, ENC.getBytes(p, false), 0, false);
        System.out.println("[PUB] NBIRTH " + edge + "  _types_/" + ref);
    }
    static void ndata(MqttClient pub, String edge) throws Exception {
        SparkplugBPayload p = new SparkplugBPayloadBuilder().setTimestamp(new Date()).setSeq(1L)
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 1500.0).createMetric())
                .createPayload();
        pub.publish("spBv1.0/" + GROUP + "/NDATA/" + edge, ENC.getBytes(p, false), 0, false);
    }
}
