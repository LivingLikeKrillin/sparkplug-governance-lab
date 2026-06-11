package dev.krillin.sparkplug.schema;

import java.util.List;

/** Compatibility check result. compatible=true if and only if violations is empty. */
public record Verdict(CompatMode mode, boolean compatible, List<Violation> violations) {}
