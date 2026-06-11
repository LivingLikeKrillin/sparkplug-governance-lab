package dev.krillin.sparkplug.drift;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.UdtDefinition;

/** Detects raw deviations between the registered source-of-truth and the definition observed in an NBIRTH,
 *  producing DriftEvents. These are structural deviations, not CompatibilityMode semantics. */
public final class SchemaDriftDetector {

    public List<DriftEvent> detect(String nodeKey, Optional<UdtDefinition> registered,
                                   UdtDefinition observed, long ts) {
        List<DriftEvent> events = new ArrayList<>();
        if (registered.isEmpty()) {
            events.add(new DriftEvent(nodeKey, DriftKind.UNREGISTERED,
                    "templateRef '" + observed.templateRef() + "' not found in registry", ts));
            return events;
        }
        UdtDefinition reg = registered.get();
        if (!reg.version().equals(observed.version())) {
            events.add(new DriftEvent(nodeKey, DriftKind.VERSION_DRIFT,
                    observed.templateRef() + " registered=" + reg.version() + " observed=" + observed.version(), ts));
        }
        Set<String> regNames = new LinkedHashSet<>();
        for (Member m : reg.members()) regNames.add(m.name());
        Set<String> obsNames = new LinkedHashSet<>();
        for (Member m : observed.members()) obsNames.add(m.name());

        for (Member o : observed.members())
            if (!regNames.contains(o.name()))
                events.add(new DriftEvent(nodeKey, DriftKind.UNKNOWN_MEMBER, o.name() + " is not a member of the registered contract", ts));
        for (Member r : reg.members())
            if (!obsNames.contains(r.name()))
                events.add(new DriftEvent(nodeKey, DriftKind.MISSING_MEMBER, r.name() + " is required by the registered contract but absent from NBIRTH", ts));
        for (Member o : observed.members())
            for (Member r : reg.members())
                if (o.name().equals(r.name()) && !o.type().equals(r.type()))
                    events.add(new DriftEvent(nodeKey, DriftKind.TYPE_DRIFT,
                            o.name() + " registered=" + r.type() + " observed=" + o.type(), ts));
        return events;
    }
}
