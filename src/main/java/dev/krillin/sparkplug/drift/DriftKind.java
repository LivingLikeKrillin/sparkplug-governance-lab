package dev.krillin.sparkplug.drift;
/** Classification of a schema or liveness drift observation. */
public enum DriftKind { UNREGISTERED, VERSION_DRIFT, UNKNOWN_MEMBER, MISSING_MEMBER, TYPE_DRIFT, STALE }
