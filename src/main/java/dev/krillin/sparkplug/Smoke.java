package dev.krillin.sparkplug;

import java.util.Date;

import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;

/**
 * Smoke test — validates the Tahu core API.
 * Builds an NBIRTH-like payload (seq=0, metrics with aliases), encodes to protobuf,
 * and decodes in a round-trip.
 * No broker required. Purpose: verify that the dependency, the core API, and protobuf
 * serialization all work correctly.
 */
public class Smoke {
    public static void main(String[] args) throws Exception {
        SparkplugBPayload payload = new SparkplugBPayloadBuilder()
                .setTimestamp(new Date())
                .setSeq(0L)
                .addMetric(new MetricBuilder("Temperature", MetricDataType.Double, 21.5).alias(1L).createMetric())
                .addMetric(new MetricBuilder("Pump/Running", MetricDataType.Boolean, true).alias(2L).createMetric())
                .createPayload();

        byte[] bytes = new SparkplugBPayloadEncoder().getBytes(payload, false);
        System.out.println("[encode] bytes = " + bytes.length);

        SparkplugBPayload decoded = new SparkplugBPayloadDecoder().buildFromByteArray(bytes, null);
        System.out.println("[decode] seq = " + decoded.getSeq());
        for (Metric m : decoded.getMetrics()) {
            System.out.println("  metric: name=" + m.getName()
                    + " alias=" + m.getAlias()
                    + " type=" + m.getDataType()
                    + " value=" + m.getValue());
        }
        System.out.println("SMOKE OK");
    }
}
