package dev.krillin.sparkplug.opcua;
import java.util.Optional;
public record LedgerEntry(String memberName, UaDataType uaType,
                          org.eclipse.tahu.message.model.MetricDataType metricType,
                          LossClass lossClass, Optional<SideChannelKind> sideChannel, String note) {}
