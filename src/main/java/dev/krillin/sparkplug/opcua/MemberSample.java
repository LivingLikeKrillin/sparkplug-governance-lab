package dev.krillin.sparkplug.opcua;
import java.util.Optional;
// value = Tahu-compatible value (Double/Boolean/String/Date, etc.);
// uaTicks = original 100ns DateTime ticks since 1601 for DateTime members (empty otherwise)
public record MemberSample(String name, Object value, long statusCode, Optional<Long> uaTicks) {}
