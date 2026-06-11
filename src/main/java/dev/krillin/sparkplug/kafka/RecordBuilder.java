package dev.krillin.sparkplug.kafka;

import java.util.*;

import dev.krillin.sparkplug.kafka.ContractValidator.Conformance;
import dev.krillin.sparkplug.kafka.TopicMapper.UnsAddress;
import dev.krillin.sparkplug.kafka.UnsStateStore.ResolvedMetric;
import dev.krillin.sparkplug.schema.UdtDefinition;
import dev.krillin.sparkplug.schema.Violation;

/**
 * Pure orchestration: maps resolved metrics to ISA-95 Kafka topics/keys and routes by contract conformance.
 * Conforming → main compacted topic (key = metric identity); violation → DLQ topic.
 * contract == null → pass-through (no validation).
 */
public final class RecordBuilder {

    public record Emission(String topic, String key, UnsRecord value, boolean dlq, List<Violation> violations) {}

    private final TopicMapper mapper;
    private final ContractValidator validator;
    private final String dlqTopic;

    public RecordBuilder(TopicMapper mapper, ContractValidator validator, String dlqTopic) {
        this.mapper = mapper; this.validator = validator; this.dlqTopic = dlqTopic;
    }

    public List<Emission> build(UnsAddress addr, UdtDefinition contract,
                                List<ResolvedMetric> metrics, long seq, long ts) {
        String mainTopic = mapper.kafkaTopic(addr);
        List<Emission> out = new ArrayList<>();
        for (ResolvedMetric m : metrics) {
            Conformance c = (contract == null)
                    ? new Conformance(true, List.of())
                    : validator.validateMetric(contract, m);
            UnsRecord rec = new UnsRecord(
                    addr.enterprise() + ":" + addr.site() + ":" + addr.area(),
                    addr.line() + ":" + addr.cell(),
                    addr.device(),
                    m.name(), m.value(), m.type(), m.quality().name(),
                    contract == null ? null : contract.templateRef(),
                    contract == null ? null : contract.version().toString(),
                    seq, ts);
            String key = mapper.recordKey(addr, m.name());
            out.add(c.ok()
                    ? new Emission(mainTopic, key, rec, false, List.of())
                    : new Emission(dlqTopic, key, rec, true, c.violations()));
        }
        return out;
    }
}
