package dev.krillin.sparkplug.kafka;

/**
 * Maps a Sparkplug topic to an ISA-95 address and Kafka topic/key (pure, no I/O).
 * Follows namespace-standard §2: group_id = "Ent:Site:Area", edge_node_id = "Line:Cell", device optional.
 * Key = metric identity → log-compacted topic = last-known-value store (RBE ↔ compaction isomorphism).
 */
public final class TopicMapper {

    public record UnsAddress(String enterprise, String site, String area,
                             String line, String cell, String device) {}

    private static final String ILLEGAL = " +#";   // characters forbidden in identifiers ('/' is already split out)

    public UnsAddress map(String sparkplugTopic) {
        String[] p = sparkplugTopic.split("/");
        if (p.length < 4) throw new IllegalArgumentException("Malformed Sparkplug topic: " + sparkplugTopic);
        String[] g = p[1].split(":");
        if (g.length != 3) throw new IllegalArgumentException("group_id must be Ent:Site:Area format: " + p[1]);
        String[] e = p[3].split(":");
        if (e.length != 2) throw new IllegalArgumentException("edge_node_id must be Line:Cell format: " + p[3]);
        String device = (p.length >= 5) ? p[4] : null;
        UnsAddress a = new UnsAddress(g[0], g[1], g[2], e[0], e[1], device);
        validate(a);
        return a;
    }

    public String kafkaTopic(UnsAddress a) {
        return "uns." + a.enterprise() + "." + a.site() + "." + a.area() + "." + a.line() + "." + a.cell();
    }

    public String recordKey(UnsAddress a, String metricPath) {
        String head = (a.device() != null) ? a.device() : a.cell();
        return head + "/" + metricPath;
    }

    private void validate(UnsAddress a) {
        for (String s : new String[]{a.enterprise(), a.site(), a.area(), a.line(), a.cell()}) {
            if (s.isEmpty()) throw new IllegalArgumentException("Empty identifier");
            for (int i = 0; i < ILLEGAL.length(); i++)
                if (s.indexOf(ILLEGAL.charAt(i)) >= 0)
                    throw new IllegalArgumentException("Identifier contains forbidden character: '" + s + "'");
        }
    }
}
