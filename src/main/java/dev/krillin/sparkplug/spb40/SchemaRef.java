package dev.krillin.sparkplug.spb40;

import dev.krillin.sparkplug.schema.SemVer;

/** SpB 4.0 #608 handle by which thin data references its schema definition: e.g. "Motor@1.1.0". */
public record SchemaRef(String templateRef, SemVer version) {

    public String format() { return templateRef + "@" + version; }

    public static SchemaRef parse(String s) {
        int at = s.lastIndexOf('@');
        if (at <= 0 || at == s.length() - 1)
            throw new IllegalArgumentException("Invalid schemaRef format (expected 'ref@x.y.z'): " + s);
        return new SchemaRef(s.substring(0, at), SemVer.parse(s.substring(at + 1)));
    }
}
