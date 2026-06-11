package dev.krillin.sparkplug.schema;

import static org.junit.jupiter.api.Assertions.*;
import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;
import org.junit.jupiter.api.Test;

class TemplateAdapterTest {

    @Test void extractsMembersParamsAndVersion() throws Exception {
        Template def = new TemplateBuilder().version("1.0.0").definition(true)
                .addParameter(new Parameter("Location", ParameterDataType.String, "PlantA/Line1"))
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric())
                .addMetric(new MetricBuilder("Running", MetricDataType.Boolean, false).createMetric())
                .createTemplate();

        UdtDefinition d = TemplateAdapter.fromTahuTemplate("Motor", def);

        assertEquals("Motor", d.templateRef());
        assertEquals(SemVer.parse("1.0.0"), d.version());
        assertEquals(2, d.members().size());
        assertTrue(d.members().contains(new Member("Rpm", "Double")));
        assertTrue(d.members().contains(new Member("Running", "Boolean")));
        assertEquals(1, d.params().size());
        assertEquals(new Param("Location", "String"), d.params().get(0));
    }
}
