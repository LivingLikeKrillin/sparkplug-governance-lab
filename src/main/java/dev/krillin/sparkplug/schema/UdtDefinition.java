package dev.krillin.sparkplug.schema;

import java.util.List;

/** UDT data contract (the unit of source of truth in the registry). Protocol-independent. */
public record UdtDefinition(String templateRef, SemVer version,
                            List<Member> members, List<Param> params) {}
