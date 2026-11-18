package scheme.driver;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Test: the language's modules export nothing to embedders (ModuleMigration step 9, design.md D11
 * "Modules"). Reads the built jars' module descriptors from {@code --module-path} and fails on an
 * open module, an unqualified {@code opens}, or an unqualified {@code exports}, but those
 * {@code --unqualified} names: {@code module/package.Class}, a package exported to everyone that
 * may hold only that class and its nested and generated classes (its name followed by {@code $} or
 * {@code Gen}).
 *
 * <p>
 * {@code --modules=scheme,scheme.launcher [--unqualified=module/package.Class,...]}
 */
public final class Modules {

    private Modules() {
    }

    public static void main(String[] args) throws IOException {
        Launch launch = new Launch(args);
        ModuleFinder finder = ModuleFinder.of(Arrays.stream(launch.modulePath.split(":")).map(Path::of).toArray(Path[]::new));
        Map<String, String> unqualified = new TreeMap<>();
        for (String allowed : launch.options.getOrDefault("unqualified", "").split(",")) {
            if (!allowed.isEmpty()) {
                int dot = allowed.lastIndexOf('.');
                unqualified.put(allowed.substring(0, dot), allowed.substring(dot + 1));
            }
        }
        List<String> errors = new ArrayList<>();
        for (String name : launch.option("modules").split(",")) {
            Optional<ModuleReference> found = finder.find(name);
            if (found.isEmpty()) {
                errors.add(name + ": not on the module path");
                continue;
            }
            ModuleDescriptor descriptor = found.get().descriptor();
            if (descriptor.isOpen()) {
                errors.add(name + ": an open module (every package open to everyone)");
            }
            for (ModuleDescriptor.Opens opens : descriptor.opens()) {
                if (!opens.isQualified()) {
                    errors.add(name + ": opens " + opens.source() + " to everyone");
                }
            }
            for (ModuleDescriptor.Exports exports : descriptor.exports()) {
                String key = name + "/" + exports.source();
                if (exports.isQualified()) {
                    Launch.log(name + ": exports " + exports.source() + " to " + String.join(", ", new java.util.TreeSet<>(exports.targets())));
                } else if (!unqualified.containsKey(key)) {
                    errors.add(name + ": exports " + exports.source() + " to everyone; qualify it (`to <module>`)");
                } else {
                    String cls = unqualified.remove(key);
                    errors.addAll(others(found.get(), exports.source(), cls));
                    Launch.log(name + ": exports " + exports.source() + " to everyone, as allowed: " + cls + " alone");
                }
            }
        }
        unqualified.forEach((key, cls) -> errors.add(key + "." + cls + ": allowed to be exported to everyone, but isn't (remove it from --unqualified)"));
        if (!errors.isEmpty()) {
            errors.forEach(e -> Launch.log("FAIL " + e));
            System.exit(1);
        }
        Launch.log("ok: no unqualified exports or opens but those allowed");
    }

    /** The classes of {@code pkg} in the module that are neither {@code cls} nor its nested or generated classes. */
    private static List<String> others(ModuleReference module, String pkg, String cls) throws IOException {
        String dir = pkg.replace('.', '/') + "/";
        List<String> others = new ArrayList<>();
        try (ModuleReader reader = module.open(); Stream<String> entries = reader.list()) {
            entries.filter(e -> e.startsWith(dir) && e.endsWith(".class") && e.indexOf('/', dir.length()) < 0).forEach(e -> {
                String simple = e.substring(dir.length(), e.length() - ".class".length());
                if (!simple.equals(cls) && !simple.startsWith(cls + "$") && !simple.startsWith(cls + "Gen")) {
                    others.add(module.descriptor().name() + ": " + pkg + "." + simple + " is in " + pkg + ", which is exported to everyone for " + cls + " alone");
                }
            });
        }
        return others;
    }
}
