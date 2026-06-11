package dev.krillin.sparkplug.opcua;
import java.util.Optional;
public record MappedType(org.eclipse.tahu.message.model.MetricDataType metricType,
                         LossClass lossClass, Optional<SideChannelKind> sideChannel, String note) {}
