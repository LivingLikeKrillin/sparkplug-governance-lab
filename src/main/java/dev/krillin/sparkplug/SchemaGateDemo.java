package dev.krillin.sparkplug;

import java.nio.file.*;
import java.util.Optional;

import org.eclipse.tahu.message.model.*;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;

import dev.krillin.sparkplug.schema.*;

/**
 * End-to-end UDT schema gate demo: every proposed UDT change must pass the gate before publishing.
 * Breaking changes are blocked before they reach the broker. No broker required.
 * Run: mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.SchemaGateDemo
 */
public class SchemaGateDemo {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("udt-registry");
        Files.writeString(root.resolve("policy.json"), "{\"mode\":\"FORWARD\"}");
        DefinitionStore store = new DefinitionStore(root);
        CompatibilityChecker checker = new CompatibilityChecker();

        // step 1: seed Motor v1.0.0 (initial registration)
        store.promote(TemplateAdapter.fromTahuTemplate("Motor", motorDef("1.0.0", false, false)));
        System.out.println("[SEED] Motor 1.0.0 registered {Rpm, Running}");

        // step 2: +Temperature (additive) v1.1.0 → FORWARD PASS → publish
        attemptPublish(store, checker, "Motor", motorDef("1.1.0", true, false));

        // step 3: drop Running v1.2.0 → FORWARD FAIL → publish blocked
        attemptPublish(store, checker, "Motor", motorDef("1.2.0", false, true));

        // step 4: breaking change handled correctly — new ref Motor2 v2.0.0 → PASS (new registration) → publish
        attemptPublish(store, checker, "Motor2", motor2Def("2.0.0"));

        System.out.println("\n>>> The gate caught the breaking member removal before it reached the broker.");
        System.out.println(">>> The governance gap (ADR-0005) is now enforced at the gate (ADR-0007).");
        System.exit(0);
    }

    /** Prints "publish" on gate pass, or "blocked" with violation details on gate failure. */
    static void attemptPublish(DefinitionStore store, CompatibilityChecker checker,
                               String ref, Template def) throws Exception {
        UdtDefinition proposed = TemplateAdapter.fromTahuTemplate(ref, def);
        Optional<UdtDefinition> current = store.latest(ref);
        System.out.println("\n=== edge proposal: " + ref + " " + proposed.version()
                + " members=" + proposed.members().stream().map(Member::name).toList() + " ===");

        if (current.isEmpty()) {
            System.out.println("[GATE] new templateRef — initial registration allowed ✅ → NBIRTH publish (simulated)");
            store.promote(proposed);
            return;
        }
        Verdict v = checker.check(current.get(), proposed, store.policyMode());
        if (v.compatible()) {
            System.out.println("[GATE] PASS ✅ → NBIRTH publish (simulated)");
            store.promote(proposed);
        } else {
            System.out.println("[GATE] FAIL ❌ → publish blocked:");
            v.violations().forEach(x -> System.out.println("  - [" + x.rule() + "] " + x.detail()));
        }
    }

    static Template motorDef(String version, boolean withTemp, boolean dropRunning) throws Exception {
        TemplateBuilder b = new TemplateBuilder().version(version).definition(true)
                .addParameter(new Parameter("Location", ParameterDataType.String, "PlantA/Line1"))
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric());
        if (!dropRunning) b.addMetric(new MetricBuilder("Running", MetricDataType.Boolean, false).createMetric());
        if (withTemp)     b.addMetric(new MetricBuilder("Temperature", MetricDataType.Double, 0.0).createMetric());
        return b.createTemplate();
    }

    static Template motor2Def(String version) throws Exception {
        return new TemplateBuilder().version(version).definition(true)
                .addParameter(new Parameter("Location", ParameterDataType.String, "PlantA/Line1"))
                .addMetric(new MetricBuilder("Rpm", MetricDataType.Double, 0.0).createMetric())
                .createTemplate();
    }
}
