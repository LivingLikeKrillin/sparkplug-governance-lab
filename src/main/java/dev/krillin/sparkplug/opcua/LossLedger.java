package dev.krillin.sparkplug.opcua;
import java.util.List;
public record LossLedger(List<LedgerEntry> entries) {
    public long cleanCount() { return entries.stream().filter(e -> e.lossClass()==LossClass.CLEAN).count(); }
    public long sideChannelCount() { return entries.stream().filter(e -> e.sideChannel().isPresent()).count(); }
    public long identityLossCount() {
        return entries.stream().filter(e -> e.lossClass()==LossClass.TYPE_IDENTITY_LOSS && e.sideChannel().isEmpty()).count();
    }
    public String summary() {
        return String.format("%d members: %d clean / %d side-channel preserved / %d type-identity lost",
            entries.size(), cleanCount(), sideChannelCount(), identityLossCount());
    }
}
