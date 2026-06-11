package dev.krillin.sparkplug.drift;
/** A single drift observation (unit of the audit log). */
public record DriftEvent(String nodeKey, DriftKind kind, String detail, long timestamp) {}
