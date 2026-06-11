package dev.krillin.sparkplug.spb40;

/** SpB 4.0 #603 metric quality codes (aligned with OPC UA StatusCode semantics). */
public enum Quality {
    GOOD(0), STALE(1), BAD(2);

    private final int code;
    Quality(int code) { this.code = code; }

    public int code() { return code; }
    public boolean isUsable() { return this == GOOD; }

    public static Quality fromCode(int code) {
        for (Quality q : values()) if (q.code == code) return q;
        throw new IllegalArgumentException("Unknown quality code: " + code);
    }
}
