"""
OPC UA sim server (asyncua 2.0) for the OPC UA -> Sparkplug UDT mapping PoC.
Single endpoint opc.tcp://127.0.0.1:4840/freeopcua/server/ (None security, anonymous).

Type hierarchy:
  BaseObjectType(i=58)
    EquipmentType            : OperationalState(Int32), LastMaintenance(DateTime)
      MotorType  (subtype of EquipmentType, HasInterface -> 2 interfaces)
        Rpm(Double, EngineeringUnits="rpm" + EURange 0..3000), Running(Boolean), Torque(Float)
  BaseInterfaceType(i=17602)
    IVendorNameplateType     : Manufacturer(String), SerialNumber(String)
    ITagNameplateType        : AssetId(String), Location(String)   <- 2nd interface = multiple inheritance

Instance Motor1 (of MotorType) under Objects. Rpm's DataValue StatusCode is periodically
written Uncertain (0x40000000) to demo "Uncertain != STALE" (loss boundary) live.

Stable NodeId addressing (string identifiers so the Milo client can target deterministically):
  ObjectTypes:  ns=<idx>;s=EquipmentType, ns=<idx>;s=MotorType,
                ns=<idx>;s=IVendorNameplateType, ns=<idx>;s=ITagNameplateType
  Instance:     ns=<idx>;s=Motor1     (member Variables auto-assigned numeric NodeIds; browse by name)

A simple standalone Temperature node is also exposed for quick scalar-read experiments.
Run: python opcua-sim-server.py    -> prints OPCUA_READY when up.
"""
import asyncio
from datetime import datetime, timezone

from asyncua import Server, ua

# OPC UA standard NodeIds
BASE_OBJECT_TYPE = ua.NodeId(58)
BASE_INTERFACE_TYPE = ua.NodeId(17602)
HAS_INTERFACE = ua.NodeId(17603)

UNCERTAIN = 0x40000000  # StatusCode severity = Uncertain


async def _mandatory_prop(node, idx, name, default, vtype):
    """Add a Mandatory property (modelling rule) of an explicit VariantType."""
    p = await node.add_property(ua.NodeId(f"{node.nodeid.Identifier}.{name}", idx), name, default,
                                varianttype=vtype)
    await p.set_modelling_rule(True)
    return p


async def _mandatory_var(node, idx, name, default, vtype):
    v = await node.add_variable(ua.NodeId(f"{node.nodeid.Identifier}.{name}", idx), name, default,
                                varianttype=vtype)
    await v.set_modelling_rule(True)
    return v


async def main():
    server = Server()
    await server.init()
    server.set_endpoint("opc.tcp://127.0.0.1:4840/freeopcua/server/")
    server.set_server_name("Krillin OPC UA Sim (type-hierarchy)")

    idx = await server.register_namespace("urn:krillin:demo")
    objects = server.nodes.objects
    base_iface = server.get_node(BASE_INTERFACE_TYPE)
    base_obj = server.get_node(BASE_OBJECT_TYPE)

    # ---- Interfaces (BaseInterfaceType subtypes) ----
    ivendor = await base_iface.add_object_type(ua.NodeId("IVendorNameplateType", idx), "IVendorNameplateType")
    await _mandatory_prop(ivendor, idx, "Manufacturer", "", ua.VariantType.String)
    await _mandatory_prop(ivendor, idx, "SerialNumber", "", ua.VariantType.String)

    itag = await base_iface.add_object_type(ua.NodeId("ITagNameplateType", idx), "ITagNameplateType")
    await _mandatory_prop(itag, idx, "AssetId", "", ua.VariantType.String)
    await _mandatory_prop(itag, idx, "Location", "", ua.VariantType.String)

    # ---- EquipmentType (BaseObjectType subtype) ----
    equipment = await base_obj.add_object_type(ua.NodeId("EquipmentType", idx), "EquipmentType")
    await _mandatory_var(equipment, idx, "OperationalState", 0, ua.VariantType.Int32)
    await _mandatory_var(equipment, idx, "LastMaintenance",
                         datetime(2026, 1, 1, tzinfo=timezone.utc), ua.VariantType.DateTime)

    # ---- MotorType (EquipmentType subtype + HasInterface -> both interfaces) ----
    motor_type = await equipment.add_object_type(ua.NodeId("MotorType", idx), "MotorType")
    await motor_type.add_reference(ivendor.nodeid, HAS_INTERFACE, forward=True, bidirectional=True)
    await motor_type.add_reference(itag.nodeid, HAS_INTERFACE, forward=True, bidirectional=True)

    rpm = await _mandatory_var(motor_type, idx, "Rpm", 0.0, ua.VariantType.Double)
    # EngineeringUnits + EURange properties on Rpm (#607 / #603 semantics)
    eu = ua.EUInformation()
    eu.DisplayName = ua.LocalizedText("rpm")
    eu.Description = ua.LocalizedText("revolutions per minute")
    eu_prop = await rpm.add_property(ua.NodeId("MotorType.Rpm.EngineeringUnits", idx),
                                     "EngineeringUnits", eu, varianttype=ua.VariantType.ExtensionObject)
    await eu_prop.set_modelling_rule(True)
    eur_prop = await rpm.add_property(ua.NodeId("MotorType.Rpm.EURange", idx),
                                      "EURange", ua.Range(Low=0.0, High=3000.0),
                                      varianttype=ua.VariantType.ExtensionObject)
    await eur_prop.set_modelling_rule(True)

    await _mandatory_var(motor_type, idx, "Running", True, ua.VariantType.Boolean)
    await _mandatory_var(motor_type, idx, "Torque", 0.0, ua.VariantType.Float)

    # ---- Instance Motor1 (of MotorType) ----
    # add_object auto-instantiates only the subtype-chain Mandatory members
    # (Rpm/Running/Torque/OperationalState/LastMaintenance). HasInterface members are NOT
    # auto-instantiated by asyncua, so we add the 4 nameplate members explicitly so the
    # instance carries values for every flattened UDT member.
    motor1 = await objects.add_object(ua.NodeId("Motor1", idx), "Motor1", objecttype=motor_type.nodeid)

    # Auto-instantiated members get NodeId ns=idx;s=Motor1.<member> (deterministic), but their
    # browse-name namespace is reset to 0 by asyncua. Address members by their stable NodeId.
    def inst(name):
        return server.get_node(ua.NodeId(f"Motor1.{name}", idx))

    async def add_inst_prop(name, val, vtype):
        return await motor1.add_property(ua.NodeId(f"Motor1.{name}", idx), name, val, varianttype=vtype)

    # interface (nameplate) members — added directly on the instance (not auto-instantiated)
    await add_inst_prop("Manufacturer", "Acme Drives", ua.VariantType.String)
    await add_inst_prop("SerialNumber", "SN-00042", ua.VariantType.String)
    await add_inst_prop("AssetId", "ASSET-Motor1", ua.VariantType.String)
    await add_inst_prop("Location", "Line3/Cell2", ua.VariantType.String)

    # subtype-chain members (auto-instantiated) — seed values by NodeId
    await inst("OperationalState").write_value(2, ua.VariantType.Int32)
    await inst("LastMaintenance").write_value(
        datetime(2026, 5, 20, 8, 30, 0, tzinfo=timezone.utc), ua.VariantType.DateTime)
    await inst("Running").write_value(True, ua.VariantType.Boolean)
    await inst("Torque").write_value(12.5, ua.VariantType.Float)
    rpm_inst = inst("Rpm")
    await rpm_inst.write_value(1535.0, ua.VariantType.Double)

    # ---- Simple standalone Temperature node (for quick scalar-read experiments) ----
    dyn = await objects.add_object(ua.NodeId("Dynamic", idx), "Dynamic")
    temp = await dyn.add_variable(ua.NodeId("Temperature", idx), "Temperature", 20.0)
    await temp.set_writable()

    async with server:
        print(f"OPCUA_READY endpoint=opc.tcp://127.0.0.1:4840/freeopcua/server/ "
              f"namespace_index={idx} "
              f"MotorType=ns={idx};s=MotorType Motor1=ns={idx};s=Motor1", flush=True)
        v = 1535.0
        t = 20.0
        tick = 0
        while True:
            await asyncio.sleep(1)
            tick += 1
            v = round(v + 5.0, 1)
            t = round(t + 0.5, 1)
            # every 3rd tick, mark Rpm Uncertain (loss-boundary demo); else Good
            if tick % 3 == 0:
                dv = ua.DataValue(
                    Value=ua.Variant(v, ua.VariantType.Double),
                    StatusCode=ua.StatusCode(UNCERTAIN),
                    SourceTimestamp=datetime.now(timezone.utc))
                await rpm_inst.write_value(dv)
                print(f"[SIM] Rpm={v} status=Uncertain(0x{UNCERTAIN:08X})", flush=True)
            else:
                await rpm_inst.write_value(v, ua.VariantType.Double)
                print(f"[SIM] Rpm={v} status=Good", flush=True)
            await temp.write_value(t)


if __name__ == "__main__":
    asyncio.run(main())
