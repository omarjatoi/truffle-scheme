package scheme.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.Test;

import scheme.runtime.BuiltinLibrary;
import scheme.runtime.PrimitiveNode;
import scheme.runtime.PrimitiveSpec;

/**
 * The primitives' registry (design.md D17, "The primitives' registry"): each area's enum gives every
 * primitive its name, arity and node, as the lambdas in its spec list did before.
 * {@code primitives.txt} lists the primitives as they were registered then; each must still be.
 */
public class PrimitiveRegistryTest {

    private static List<PrimitiveSpec> primitives() {
        PrimitiveRegistryTest.class.getModule().addUses(BuiltinLibrary.class);
        List<PrimitiveSpec> all = new ArrayList<>();
        for (BuiltinLibrary library : ServiceLoader.load(BuiltinLibrary.class, BuiltinLibrary.class.getClassLoader())) {
            all.addAll(library.primitives());
        }
        return all;
    }

    /** What primitives.txt has for a primitive: name, arity, returnsValue, allocates, node class. */
    private static String describe(PrimitiveSpec spec) {
        return spec.name() + " " + spec.minArgs() + " " + spec.maxArgs() + " " + spec.returnsValue() + " " + spec.allocates() + " " + spec.factory().get().getClass().getName();
    }

    @Test
    public void everyPrimitiveIsRegisteredAsBefore() throws IOException {
        Map<String, String> now = new HashMap<>();
        for (PrimitiveSpec spec : primitives()) {
            assertFalse("registered twice: " + spec.name(), now.containsKey(spec.name()));
            now.put(spec.name(), describe(spec));
        }
        int before = 0;
        try (InputStream in = PrimitiveRegistryTest.class.getResourceAsStream("primitives.txt")) {
            assertNotNull("primitives.txt", in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty() || line.startsWith("# ")) {
                    continue;
                }
                String name = line.substring(0, line.indexOf(' '));
                assertEquals(name, line, now.get(name));
                before++;
            }
        }
        assertEquals(283, before);
    }

    /** A primitive's factory makes a new node each time: a call site runs a node of its own. */
    @Test
    public void everyPrimitiveMakesANewNode() {
        for (PrimitiveSpec spec : primitives()) {
            PrimitiveNode node = spec.factory().get();
            assertNotNull(spec.name(), node);
            assertNotSame(spec.name(), node, spec.factory().get());
            assertNotNull(spec.name(), spec.createNode());
        }
    }
}
