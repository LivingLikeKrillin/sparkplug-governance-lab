package dev.krillin.sparkplug.opcua;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.EUInformation;
import org.eclipse.milo.opcua.stack.core.types.structured.Range;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

/**
 * Live shell (Milo): browse an OPC UA ObjectType into a pure {@link UaTypeSpace}.
 * Walks the SuperType chain (HasSubtype inverse), HasInterface forward refs, and the
 * declared member Variables (HasComponent + HasProperty) of each type/interface node,
 * reading each member's DataType attribute + ValueRank + EURange/EngineeringUnits.
 *
 * Pure business logic stays out of here — this only fills the model {@link UaTypeSpace}.
 */
public final class OpcUaBrowser {
    // OPC UA standard NodeIds (numeric, ns=0)
    private static final NodeId HAS_SUBTYPE   = new NodeId(0, 45);
    private static final NodeId HAS_COMPONENT = new NodeId(0, 47);
    private static final NodeId HAS_PROPERTY  = new NodeId(0, 46);
    private static final NodeId HAS_INTERFACE = new NodeId(0, 17603);
    private static final NodeId BASE_OBJECT_TYPE    = new NodeId(0, 58);
    private static final NodeId BASE_INTERFACE_TYPE = new NodeId(0, 17602);

    private static final UInteger NODECLASS_ALL = Unsigned.uint(0xFF);
    private static final UInteger RESULT_ALL    = Unsigned.uint(0x3F);

    private OpcUaBrowser() {}

    /** Browse {@code objectTypeNode} and all its supertypes + interfaces into a UaTypeSpace. */
    public static UaTypeSpace browse(OpcUaClient client, NodeId objectTypeNode) throws Exception {
        NamespaceTable nt = client.getNamespaceTable();
        Map<String, UaTypeNode> nodes = new LinkedHashMap<>();
        // walk the ObjectType subtype chain (target -> supertypes), recording each + its interfaces
        addTypeChain(client, nt, objectTypeNode, nodes, false);
        return new UaTypeSpace(nodes);
    }

    /** Add a type node and recurse up its supertype chain. {@code isInterface} stops at the right base. */
    private static void addTypeChain(OpcUaClient client, NamespaceTable nt, NodeId typeNode,
                                     Map<String, UaTypeNode> nodes, boolean isInterface) throws Exception {
        String browseName = readBrowseName(client, typeNode);
        if (browseName == null || nodes.containsKey(browseName)) return;

        // supertype: inverse HasSubtype -> parent type (stop at the base type)
        Optional<NodeId> superType = Optional.empty();
        String superName = null;
        BrowseResult supRes = client.browse(new BrowseDescription(
                typeNode, BrowseDirection.Inverse, HAS_SUBTYPE, true, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(supRes)) {
            NodeId parent = r.getNodeId().toNodeId(nt).orElse(null);
            if (parent == null) continue;
            if (parent.equals(BASE_OBJECT_TYPE) || parent.equals(BASE_INTERFACE_TYPE)) break; // stop at base
            superName = r.getBrowseName().getName();
            superType = Optional.of(parent);
            break;
        }

        // interfaces: forward HasInterface (only meaningful for ObjectTypes, harmless otherwise)
        List<String> ifaceNames = new ArrayList<>();
        List<NodeId> ifaceNodes = new ArrayList<>();
        BrowseResult ifRes = client.browse(new BrowseDescription(
                typeNode, BrowseDirection.Forward, HAS_INTERFACE, true, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(ifRes)) {
            NodeId in = r.getNodeId().toNodeId(nt).orElse(null);
            if (in == null) continue;
            ifaceNames.add(r.getBrowseName().getName());
            ifaceNodes.add(in);
        }

        // declared members: HasComponent + HasProperty children that are Variables
        List<UaMember> members = readMembers(client, nt, typeNode);

        nodes.put(browseName, new UaTypeNode(browseName, Optional.ofNullable(superName), ifaceNames, members));

        // recurse supertype chain + interface chains
        if (superType.isPresent()) addTypeChain(client, nt, superType.get(), nodes, isInterface);
        for (NodeId in : ifaceNodes) addTypeChain(client, nt, in, nodes, true);
    }

    /** Read the declared member Variables (HasComponent + HasProperty) of a type node. */
    private static List<UaMember> readMembers(OpcUaClient client, NamespaceTable nt, NodeId typeNode)
            throws Exception {
        // ordered, de-duplicated by browseName (HasComponent first, then HasProperty)
        LinkedHashMap<String, UaMember> out = new LinkedHashMap<>();
        for (NodeId refType : new NodeId[]{HAS_COMPONENT, HAS_PROPERTY}) {
            BrowseResult res = client.browse(new BrowseDescription(
                    typeNode, BrowseDirection.Forward, refType, false, NODECLASS_ALL, RESULT_ALL));
            for (ReferenceDescription r : safeRefs(res)) {
                if (r.getNodeClass() != NodeClass.Variable) continue;
                String name = r.getBrowseName().getName();
                if (out.containsKey(name)) continue;
                NodeId memberNode = r.getNodeId().toNodeId(nt).orElse(null);
                if (memberNode == null) continue;
                out.put(name, buildMember(client, nt, name, memberNode));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static UaMember buildMember(OpcUaClient client, NamespaceTable nt, String name, NodeId memberNode)
            throws Exception {
        // DataType attribute -> NodeId -> numeric id -> UaDataType
        DataValue dtDv = readAttribute(client, memberNode, AttributeId.DataType);
        UaDataType dataType = UaDataType.UNKNOWN; // default; surfaced as TYPE_IDENTITY_LOSS ledger entry
        Object dtVal = dtDv != null ? dtDv.getValue().getValue() : null;
        if (dtVal instanceof NodeId dtNode && dtNode.getIdentifier() instanceof UInteger uid) {
            dataType = UaTypeIds.of(uid.intValue()).orElse(dataType);
        }
        // ValueRank attribute
        int valueRank = -1;
        DataValue vrDv = readAttribute(client, memberNode, AttributeId.ValueRank);
        if (vrDv != null && vrDv.getValue().getValue() instanceof Integer vr) valueRank = vr;

        // EURange / EngineeringUnits (HasProperty children of the member Variable)
        Optional<UaEngInfo> eng = readEngInfo(client, nt, memberNode);

        return new UaMember(name, dataType, valueRank, eng, "Mandatory");
    }

    /** Read EURange + EngineeringUnits properties of a member Variable, if present. */
    private static Optional<UaEngInfo> readEngInfo(OpcUaClient client, NamespaceTable nt, NodeId memberNode)
            throws Exception {
        String unit = null;
        Double low = null, high = null;
        BrowseResult props = client.browse(new BrowseDescription(
                memberNode, BrowseDirection.Forward, HAS_PROPERTY, false, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(props)) {
            String pn = r.getBrowseName().getName();
            NodeId pNode = r.getNodeId().toNodeId(nt).orElse(null);
            if (pNode == null) continue;
            if ("EURange".equals(pn)) {
                Object v = decode(client, readValue(client, pNode));
                if (v instanceof Range range) { low = range.getLow(); high = range.getHigh(); }
            } else if ("EngineeringUnits".equals(pn)) {
                Object v = decode(client, readValue(client, pNode));
                if (v instanceof EUInformation eu) {
                    LocalizedText dn = eu.getDisplayName();
                    if (dn != null && dn.getText() != null) unit = dn.getText();
                }
            }
        }
        if (unit == null && low == null && high == null) return Optional.empty();
        return Optional.of(new UaEngInfo(unit, low, high));
    }

    // ---- low-level helpers ----

    static String readBrowseName(OpcUaClient client, NodeId node) throws Exception {
        DataValue dv = readAttribute(client, node, AttributeId.BrowseName);
        if (dv == null || dv.getValue() == null) return null;
        Object v = dv.getValue().getValue();
        if (v instanceof org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName qn) return qn.getName();
        return null;
    }

    static DataValue readAttribute(OpcUaClient client, NodeId node, AttributeId attr) throws Exception {
        var rvid = new org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId(
                node, attr.uid(), null, org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName.NULL_VALUE);
        var resp = client.read(0.0, TimestampsToReturn.Neither, List.of(rvid));
        DataValue[] results = resp.getResults();
        return (results != null && results.length > 0) ? results[0] : null;
    }

    static DataValue readValue(OpcUaClient client, NodeId node) throws Exception {
        return client.readValue(0.0, TimestampsToReturn.Both, node);
    }

    /** Decode a Variant value, unwrapping an ExtensionObject (e.g. Range, EUInformation) if present. */
    static Object decode(OpcUaClient client, DataValue dv) {
        if (dv == null || dv.getValue() == null) return null;
        Variant var = dv.getValue();
        Object v = var.getValue();
        if (v instanceof ExtensionObject eo) {
            try {
                return eo.decode(client.getDynamicEncodingContext());
            } catch (Exception e) {
                return null;
            }
        }
        return v;
    }

    private static ReferenceDescription[] safeRefs(BrowseResult res) {
        ReferenceDescription[] refs = res != null ? res.getReferences() : null;
        return refs != null ? refs : new ReferenceDescription[0];
    }
}
