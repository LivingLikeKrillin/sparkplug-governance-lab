package dev.krillin.sparkplug.spb40;

import static org.junit.jupiter.api.Assertions.*;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.ParameterDataType;
import org.junit.jupiter.api.Test;

class TahuTypesTest {
    @Test void metricType_resolvesByToString() {
        assertEquals(MetricDataType.Double, TahuTypes.metricType("Double"));
        assertEquals(MetricDataType.Boolean, TahuTypes.metricType("Boolean"));
        assertEquals(MetricDataType.Int32, TahuTypes.metricType("Int32"));
    }
    @Test void metricType_inverseOfToString() {
        assertEquals("Double", TahuTypes.metricType("Double").toString());
    }
    @Test void parameterType_resolvesByToString() {
        assertEquals(ParameterDataType.String, TahuTypes.parameterType("String"));
    }
    @Test void metricType_rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> TahuTypes.metricType("Nope"));
    }
}
