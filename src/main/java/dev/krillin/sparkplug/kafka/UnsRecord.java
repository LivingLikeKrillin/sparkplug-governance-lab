package dev.krillin.sparkplug.kafka;

/**
 * A flattened UNS record emitted to Kafka as JSON. Carries the current value of a single metric
 * with Sparkplug state fully restored (alias resolved, quality applied).
 * {@code group}/{@code edge} are the Sparkplug group_id / edge_node_id, which in this lab encode
 * ISA-95 paths ("Ent:Site:Area" / "Line:Cell" — see namespace standard §2).
 * The record key (= metric identity) is determined separately by RecordBuilder / TopicMapper.
 */
public record UnsRecord(String group, String edge, String device, String metricPath,
                        Object value, String type, String quality,
                        String contractRef, String contractVersion, long seq, long timestamp) {}
