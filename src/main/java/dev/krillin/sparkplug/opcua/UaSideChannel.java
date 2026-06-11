package dev.krillin.sparkplug.opcua;

import dev.krillin.sparkplug.spb40.Quality;

public final class UaSideChannel {
    private UaSideChannel() {}
    public static final String QUALITY = dev.krillin.sparkplug.spb40.ThinCodec.QUALITY; // same key as the spb40 Definition codec (ADR-0008, #603) to prevent drift
    public static final String UA_STATUSCODE = "ua_statuscode";
    public static final String UA_TICKS = "ua_ticks";
    public static final String UA_LOCALE = "ua_locale";

    public static Quality toQuality(long statusCode) {
        long severity = statusCode & 0xC0000000L;
        if (severity == 0x00000000L) return Quality.GOOD;
        if (severity == 0x80000000L) return Quality.BAD;
        if (severity == 0xC0000000L) return Quality.BAD;   // #3: reserved severity → BAD
        if ((statusCode & 0xFFFF0000L) == 0x40900000L) return Quality.STALE; // #4: UncertainLastUsableValue (including InfoBits)
        return Quality.GOOD; // generic Uncertain projects to GOOD (projection loss; truth is in ua_statuscode)
    }
}
