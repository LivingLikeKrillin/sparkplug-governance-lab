package dev.krillin.sparkplug.opcua;
import java.util.*;
public final class TypeFlattener {
    private TypeFlattener() {}
    public static FlattenResult flatten(UaTypeSpace space, String target) {
        LinkedHashMap<String, FlatMember> out = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        // 1) own members of the target, then the supertype chain in order
        for (String t = target; t != null; ) {
            UaTypeNode n = space.node(t).orElse(null);
            if (n == null) break;
            Origin origin = t.equals(target) ? Origin.OWN : Origin.SUPERTYPE;
            for (UaMember m : n.declaredMembers()) accept(out, conflicts, m, origin, t);
            t = n.superType().orElse(null);
        }
        // 2) interface members in HasInterface order, then each interface's supertype chain
        UaTypeNode tn = space.node(target).orElse(null);
        if (tn != null) for (String iface : tn.interfaces())
            for (String it = iface; it != null; ) {
                UaTypeNode in = space.node(it).orElse(null);
                if (in == null) break;
                for (UaMember m : in.declaredMembers()) accept(out, conflicts, m, Origin.INTERFACE, it);
                it = in.superType().orElse(null);
            }
        return new FlattenResult(new ArrayList<>(out.values()), conflicts);
    }
    private static void accept(LinkedHashMap<String,FlatMember> out, List<Conflict> conflicts,
                               UaMember m, Origin origin, String src) {
        FlatMember cur = out.get(m.browseName());
        Provenance p = new Provenance(origin, src);
        if (cur == null) {
            List<Provenance> prov = new ArrayList<>(); prov.add(p);
            out.put(m.browseName(), new FlatMember(m.browseName(), m.dataType(), m.valueRank(), m.eng(), prov));
            return;
        }
        cur.provenance().add(p);  // keep the most-derived (existing) member; add the new provenance source
        if (cur.dataType() != m.dataType()) {
            boolean objectChainInvolved = cur.provenance().stream().anyMatch(x -> x.origin()!=Origin.INTERFACE)
                                          || origin != Origin.INTERFACE;
            if (!objectChainInvolved)  // only interface-vs-interface type conflicts produce a Conflict record
                conflicts.add(new Conflict(m.browseName(),
                    "type conflict: kept " + cur.dataType() + ", ignored " + m.dataType() + " from " + src,
                    List.copyOf(cur.provenance())));
        }
    }
}
