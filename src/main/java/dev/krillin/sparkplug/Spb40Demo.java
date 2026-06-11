package dev.krillin.sparkplug;

import java.util.*;

import dev.krillin.sparkplug.schema.*;
import dev.krillin.sparkplug.spb40.*;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;

/**
 * End-to-end demo against a live HiveMQ broker: SpB 4.0 #608 schema-data separation + #607 engUnit + #603 quality.
 * Honesty: Sparkplug 4.0 is unreleased (TCK 0/30, wire format not finalized) — this is a concept prototype built with Sparkplug 3.0 primitives (Tahu Template/PropertySet).
 * Prerequisites: docker start sparkplug-hivemq-ce. Run: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.Spb40Demo
 */
public class Spb40Demo {

    static final String BROKER = "tcp://localhost:1883", GROUP = "Krillin", EDGE = "Spb40Edge";

    public static void main(String[] args) throws Exception {
        System.out.println(">>> [NOTICE] Sparkplug 4.0 is unreleased (TCK 0/30, wire format not finalized). This is a concept prototype of #608/#607/#603 built with Sparkplug 3.0 primitives (Tahu Template/PropertySet).\n");

        UdtDefinition def = new UdtDefinition("Motor", SemVer.parse("1.1.0"),
                List.of(new Member("Rpm", "Double"), new Member("Running", "Boolean"), new Member("Temperature", "Double")),
                List.of(new Param("Location", "String")));
        Map<String, String> units = Map.of("Rpm", "rpm", "Temperature", "degC");

        SchemaResolvingConsumer consumer = new SchemaResolvingConsumer(GROUP);
        consumer.connect(BROKER);
        Thread.sleep(400);

        DefinitionPublisher defPub = new DefinitionPublisher(GROUP, EDGE);
        defPub.connect(BROKER);
        int defBytes = defPub.publishDefinition(def, units);
        Thread.sleep(600);

        Spb40Edge edge = new Spb40Edge(GROUP, EDGE);
        edge.connect(BROKER);
        Map<String, Integer> good = Map.of("Rpm", 0, "Running", 0, "Temperature", 0);
        edge.publishThin("NBIRTH", def, Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), good, 0L);
        Thread.sleep(400);
        edge.publishThin("NDATA", def, Map.of("Rpm", 1520.0, "Running", true, "Temperature", 66.1), good, 1L);
        Thread.sleep(400);

        Map<String, Integer> bad = new HashMap<>(good); bad.put("Temperature", Quality.BAD.code());
        edge.publishThin("NDATA", def, Map.of("Rpm", 1525.0, "Running", true, "Temperature", -999.0), bad, 2L);
        Thread.sleep(600);

        SparkplugBPayloadEncoder enc = new SparkplugBPayloadEncoder();
        int fat = enc.getBytes(ThinCodec.buildFatBirth(def, units, Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), 0L), false).length;
        int thin = enc.getBytes(ThinCodec.buildThin(def, Map.of("Rpm", 1500.0, "Running", true, "Temperature", 65.4), good, 0L), false).length;
        System.out.println("\n>>> [#608 size] 3.0-style fat NBIRTH (inline _types_)=" + fat + "B  vs  thin NBIRTH (schemaRef)=" + thin + "B"
                + "  -> " + (fat - thin) + "B saved per rebirth (schema paid once as retained Definition " + defBytes + "B). Reduces BIRTH-storm overhead.");
        System.out.println(">>> [contrast] Sparkplug 3.0 retransmits the full schema in every NBIRTH. #608 sends only a schemaRef — the schema lives outside the data path.");

        Thread.sleep(300);
        edge.close(); defPub.close(); consumer.close();
        System.out.println("\n=== SPB40 DEMO DONE ===");
        System.exit(0);
    }
}
