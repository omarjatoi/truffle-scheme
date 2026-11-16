import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Builds a named module's jar from class jars, like an mx distribution with "moduleInfo": merges
 * the jars, derives {@code provides} from their {@code META-INF/services} files (as mx does), and
 * compiles a {@code module-info.java} from the given exports, opens, requires and uses against the
 * merged classes. The output is deterministic (sorted entries, fixed timestamps), so it caches.
 *
 * <pre>
 * ModuleJar --out out.jar --name scheme --module-path a.jar:b.jar
 *           [--requires m]... [--exports p]... [--opens "p to m"]... [--uses s]... in.jar...
 * </pre>
 */
public final class ModuleJar {

    private static final long TIME = 315532800000L; // 1980-01-01, as Bazel's own jars

    private ModuleJar() {
    }

    public static void main(String[] args) throws IOException {
        Path out = null;
        String name = null;
        String modulePath = "";
        List<String> requires = new ArrayList<>();
        List<String> exports = new ArrayList<>();
        List<String> opens = new ArrayList<>();
        List<String> uses = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--name" -> name = args[++i];
                case "--module-path" -> modulePath = args[++i];
                case "--requires" -> requires.add(args[++i]);
                case "--exports" -> exports.add(args[++i]);
                case "--opens" -> opens.add(args[++i]);
                case "--uses" -> uses.add(args[++i]);
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (out == null || name == null) {
            throw new IllegalArgumentException("ModuleJar: --out and --name are required");
        }

        // Merge: classes and resources must not collide; service files are concatenated.
        Map<String, byte[]> entries = new TreeMap<>();
        Map<String, Set<String>> services = new TreeMap<>();
        for (Path jar : jars) {
            try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
                for (JarEntry e = in.getNextJarEntry(); e != null; e = in.getNextJarEntry()) {
                    String path = e.getName();
                    if (e.isDirectory() || path.equals("META-INF/MANIFEST.MF") || path.equals("module-info.class")) {
                        continue;
                    }
                    byte[] data = in.readAllBytes();
                    if (path.startsWith("META-INF/services/")) {
                        Set<String> providers = services.computeIfAbsent(path.substring("META-INF/services/".length()), k -> new LinkedHashSet<>());
                        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                            String provider = line.replaceAll("#.*", "").strip();
                            if (!provider.isEmpty()) {
                                providers.add(provider);
                            }
                        }
                    } else if (entries.put(path, data) != null) {
                        throw new IllegalStateException("ModuleJar: " + path + " is in more than one input jar");
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> s : services.entrySet()) {
            entries.put("META-INF/services/" + s.getKey(), (String.join("\n", s.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        // module-info.java, compiled against the merged classes (--patch-module).
        StringBuilder info = new StringBuilder("module ").append(name).append(" {\n");
        requires.forEach(r -> info.append("    requires ").append(r).append(";\n"));
        exports.forEach(e -> info.append("    exports ").append(e).append(";\n"));
        opens.forEach(o -> info.append("    opens ").append(o).append(";\n"));
        uses.forEach(u -> info.append("    uses ").append(u).append(";\n"));
        services.forEach((service, providers) -> info.append("    provides ").append(service).append(" with ").append(String.join(", ", providers)).append(";\n"));
        info.append("}\n");

        Path work = Files.createTempDirectory("module-jar");
        Path classes = work.resolve("classes");
        Path src = work.resolve("src");
        Files.createDirectories(classes);
        Files.createDirectories(src);
        Path merged = work.resolve("merged.jar");
        writeJar(merged, entries);
        Path moduleInfo = src.resolve("module-info.java");
        Files.writeString(moduleInfo, info);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "--module-path", modulePath, "--patch-module", name + "=" + merged, "-d", classes.toString(), "-implicit:none",
                        "-Xlint:all,-module,-exports,-requires-automatic,-requires-transitive-automatic", "-Werror", moduleInfo.toString());
        if (rc != 0) {
            throw new IllegalStateException("ModuleJar: compiling module-info.java for " + name + " failed:\n" + info);
        }
        entries.put("module-info.class", Files.readAllBytes(classes.resolve("module-info.class")));
        writeJar(out, entries);
        deleteTree(work);
    }

    private static void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        manifest.write(m);
        Map<String, byte[]> all = new TreeMap<>(entries);
        try (OutputStream file = Files.newOutputStream(path); JarOutputStream out = new JarOutputStream(file)) {
            put(out, "META-INF/", null);
            put(out, "META-INF/MANIFEST.MF", m.toByteArray());
            Set<String> dirs = new TreeSet<>();
            for (String name : all.keySet()) {
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                    dirs.add(name.substring(0, i + 1));
                }
            }
            dirs.remove("META-INF/");
            for (String dir : dirs) {
                put(out, dir, null);
            }
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                put(out, e.getKey(), e.getValue());
            }
        }
    }

    private static void put(JarOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(TIME);
        out.putNextEntry(entry);
        if (data != null) {
            out.write(data);
        }
        out.closeEntry();
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
