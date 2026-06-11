package dev.krillin.sparkplug.opcua;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import dev.krillin.sparkplug.schema.Member;
import dev.krillin.sparkplug.schema.UdtDefinition;

/**
 * Live shell (Milo): read an OPC UA instance's member Variables into {@link MemberSample}s,
 * in UDT member order. Converts each value to a Tahu-compatible Java value, captures the
 * unsigned 32-bit StatusCode as a long, and preserves the original 100ns DateTime ticks
 * (since 1601) in {@code uaTicks} for the lossless side-channel.
 */
public final class OpcUaInstanceReader {
    private static final NodeId HAS_COMPONENT = new NodeId(0, 47);
    private static final NodeId HAS_PROPERTY  = new NodeId(0, 46);
    private static final UInteger NODECLASS_ALL = Unsigned.uint(0xFF);
    private static final UInteger RESULT_ALL    = Unsigned.uint(0x3F);

    private OpcUaInstanceReader() {}

    /** Read all UDT members of {@code instanceNode}, in udt member order. */
    public static List<MemberSample> read(OpcUaClient client, NodeId instanceNode, UdtDefinition udt)
            throws Exception {
        NamespaceTable nt = client.getNamespaceTable();
        Map<String, NodeId> byName = memberNodesByName(client, nt, instanceNode);

        List<MemberSample> out = new ArrayList<>();
        for (Member m : udt.members()) {
            NodeId memberNode = byName.get(m.name());
            if (memberNode == null) continue; // member not present on instance (skip, surfaced by demo)
            DataValue dv = client.readValue(0.0, org.eclipse.milo.opcua.stack.core.types.enumerated
                    .TimestampsToReturn.Both, memberNode);
            out.add(toSample(m.name(), dv));
        }
        return out;
    }

    private static Map<String, NodeId> memberNodesByName(OpcUaClient client, NamespaceTable nt, NodeId instanceNode)
            throws Exception {
        Map<String, NodeId> byName = new LinkedHashMap<>();
        for (NodeId refType : new NodeId[]{HAS_COMPONENT, HAS_PROPERTY}) {
            BrowseResult res = client.browse(new BrowseDescription(
                    instanceNode, BrowseDirection.Forward, refType, false, NODECLASS_ALL, RESULT_ALL));
            ReferenceDescription[] refs = res != null ? res.getReferences() : null;
            if (refs == null) continue;
            for (ReferenceDescription r : refs) {
                if (r.getNodeClass() != NodeClass.Variable) continue;
                NodeId n = r.getNodeId().toNodeId(nt).orElse(null);
                if (n != null) byName.putIfAbsent(r.getBrowseName().getName(), n);
            }
        }
        return byName;
    }

    /** Convert a DataValue to a MemberSample (Tahu-compatible value + unsigned statusCode + ua_ticks). */
    static MemberSample toSample(String name, DataValue dv) {
        long statusCode = dv.getStatusCode() != null ? dv.getStatusCode().getValue() : 0L;
        Object raw = dv.getValue() != null ? dv.getValue().getValue() : null;
        Object value = raw;
        Optional<Long> uaTicks = Optional.empty();

        if (raw instanceof DateTime dt) {
            uaTicks = Optional.of(dt.getUtcTime());      // original 100ns ticks since 1601 (lossless)
            value = new Date(dt.getJavaTime());          // Tahu DateTime metric accepts java.util.Date
        } else if (raw instanceof UUID g) {
            value = g.toString();                        // Guid -> UUID string (type-identity loss)
        } else if (raw instanceof Float f) {
            value = f;                                   // Tahu Float metric accepts Float
        } else if (raw instanceof Integer || raw instanceof Boolean
                || raw instanceof Double || raw instanceof String) {
            value = raw;                                 // clean pass-through
        } else if (raw instanceof UInteger ui) {
            value = ui.longValue();                      // UInt32 -> long carrier
        } else if (raw instanceof ULong ul) {
            value = ul.longValue();
        }
        return new MemberSample(name, value, statusCode, uaTicks);
    }
}
