package dev.krillin.sparkplug.bridge;

import java.util.List;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

/**
 * Throwaway cross-version interop pre-flight (spec §8 top risk): a Milo 1.0.0 client against the
 * shared koshei Milo 0.6.12 sim ({@code opc.tcp://localhost:48400}). Writes ns=2;s=Recipe/Temp=123.0
 * via the synchronous 1.0.0 batch {@code writeValues(...).get(0)} and reads it back. Prints
 * {@code INTEROP OK read-back=123.0} on success. OPC-UA is wire-compatible across versions over a
 * SecurityPolicy.None endpoint, so this should PASS with no Milo bump needed.
 *
 * Run: {@code mvn -q compile exec:java -Dexec.mainClass=dev.krillin.sparkplug.bridge.MiloInteropSpike}
 */
public final class MiloInteropSpike {

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0] : "opc.tcp://localhost:48400";
        NodeId node = NodeId.parse("ns=2;s=Recipe/Temp");

        OpcUaClient client = OpcUaClient.create(endpoint);
        client.connect();
        System.out.println("[SPIKE] connected " + endpoint);

        StatusCode sc = client.writeValues(
                List.of(node),
                List.of(new DataValue(new Variant(123.0)))).get(0);
        DataValue dv = client.readValue(0.0, TimestampsToReturn.Neither, node);
        Object v = dv.getValue() != null ? dv.getValue().getValue() : null;

        boolean ok = sc.isGood()
                && dv.getStatusCode() != null && dv.getStatusCode().isGood()
                && "123.0".equals(String.valueOf(v));
        System.out.println((ok ? "INTEROP OK read-back=" : "INTEROP FAIL read-back=") + v
                + " writeStatus=" + sc);

        client.disconnect();
        System.exit(ok ? 0 : 1);
    }

    private MiloInteropSpike() {}
}
