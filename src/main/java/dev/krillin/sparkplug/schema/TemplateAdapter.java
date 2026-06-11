package dev.krillin.sparkplug.schema;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Parameter;
import org.eclipse.tahu.message.model.Template;

/**
 * Extracts a UdtDefinition from a Tahu Template (the _types_ definition in NBIRTH = wire truth).
 * The templateRef is typically null on a definition Template, so it is passed explicitly
 * from the _types_/&lt;Name&gt; metric name.
 */
public final class TemplateAdapter {

    private TemplateAdapter() {}

    public static UdtDefinition fromTahuTemplate(String templateRef, Template def) {
        List<Member> members = new ArrayList<>();
        for (Metric m : def.getMetrics()) {
            members.add(new Member(m.getName(), m.getDataType().toString()));
        }
        List<Param> params = new ArrayList<>();
        for (Parameter p : def.getParameters()) {
            params.add(new Param(p.getName(), p.getType().toString()));
        }
        return new UdtDefinition(templateRef, SemVer.parse(def.getVersion()), members, params);
    }
}
