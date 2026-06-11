package dev.krillin.sparkplug.spb40;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.ParameterDataType;

/**
 * MetricDataType and ParameterDataType in Tahu 1.0.14 are NOT enums: there is no valueOf(String).
 * This utility resolves a type-name string (where Member.type stores MetricDataType.toString())
 * back to the type object by matching public static constant fields via toString() using reflection.
 */
public final class TahuTypes {
    private TahuTypes() {}

    public static MetricDataType metricType(String name) {
        for (Field f : MetricDataType.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && MetricDataType.class.isAssignableFrom(f.getType())) {
                try {
                    MetricDataType v = (MetricDataType) f.get(null);
                    if (v != null && v.toString().equals(name)) return v;
                } catch (IllegalAccessException ignored) { }
            }
        }
        throw new IllegalArgumentException("Unknown MetricDataType: " + name);
    }

    public static ParameterDataType parameterType(String name) {
        for (Field f : ParameterDataType.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && ParameterDataType.class.isAssignableFrom(f.getType())) {
                try {
                    ParameterDataType v = (ParameterDataType) f.get(null);
                    if (v != null && v.toString().equals(name)) return v;
                } catch (IllegalAccessException ignored) { }
            }
        }
        throw new IllegalArgumentException("Unknown ParameterDataType: " + name);
    }
}
