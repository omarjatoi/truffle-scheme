package scheme.driver;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test: one static check of one java_library (bazel/lint/defs.bzl): the Eclipse formatter would change
 * nothing, Checkstyle, ECJ with GraalVM's JDT settings (warnings are errors), or SpotBugs. Runs the
 * tool's main class ({@code --tool-main}, on {@code --tool-classpath}) in a child JVM.
 *
 * <p>
 * {@code --check=format|checkstyle|ecj|spotbugs --inputs=MANIFEST [--config=FILE]}: the manifest
 * has a {@code kind<TAB>runfiles path} line per file (srcs, classpath, runtime_classpath,
 * class_jars, generated_sources, processor_path).
 */
public final class Lint {

    private Lint() {
    }

    /**
     * The JDK's modules but GraalVM's own (org.graalvm.word, org.graalvm.nativeimage, ...) and those
     * that require them: GraalVM's come from the classpath, at the Truffle version, as javac sees
     * them. Otherwise ECJ finds their packages in two modules, the system one and the classpath's,
     * and refuses to compile an import of them.
     */
    private static String jdkModules() {
        Map<String, ModuleDescriptor> modules = new HashMap<>();
        for (ModuleReference module : ModuleFinder.ofSystem().findAll()) {
            modules.put(module.descriptor().name(), module.descriptor());
        }
        TreeSet<String> names = new TreeSet<>();
        for (String name : modules.keySet()) {
            if (!needsGraalVM(name, modules, new HashSet<>())) {
                names.add(name);
            }
        }
        return String.join(",", names);
    }

    private static boolean needsGraalVM(String name, Map<String, ModuleDescriptor> modules, Set<String> seen) {
        if (name.startsWith("org.graalvm.")) {
            return true;
        }
        ModuleDescriptor module = modules.get(name);
        if (module == null || !seen.add(name)) {
            return false;
        }
        for (ModuleDescriptor.Requires requires : module.requires()) {
            if (needsGraalVM(requires.name(), modules, seen)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        Launch launch = new Launch(args);
        Map<String, List<String>> inputs = new HashMap<>();
        for (String line : Launch.read(Launch.runfile(launch.option("inputs"))).split("\n")) {
            if (!line.isEmpty()) {
                String[] kv = line.split("\t", 2);
                inputs.putIfAbsent(kv[0], new ArrayList<>());
                inputs.get(kv[0]).add(Launch.runfile(kv[1]).toString());
            }
        }
        Path tmp = Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "lint-");
        List<String> jvm = new ArrayList<>();
        List<String> command = new ArrayList<>();
        switch (launch.option("check")) {
            case "format" -> {
                command.addAll(List.of("--check", Launch.runfile(launch.option("config")).toString()));
                command.addAll(get(inputs, "srcs"));
            }
            case "checkstyle" -> {
                jvm.add("-Xmx1g");
                command.addAll(List.of("-c", Launch.runfile(launch.option("config")).toString()));
                command.addAll(get(inputs, "srcs"));
            }
            case "ecj" -> {
                // GraalVM's JDT settings, -failOnWarning; with the Truffle DSL processor, warnings are
                // ignored (ECJ can't ignore them in generated code only).
                String prefs = Launch.read(Launch.runfile(launch.option("config")));
                List<String> processorPath = get(inputs, "processor_path");
                if (!processorPath.isEmpty()) {
                    prefs = prefs.replace("=warning", "=ignore");
                }
                Path prefsFile = tmp.resolve("jdt.prefs");
                Files.writeString(prefsFile, prefs + "\norg.eclipse.jdt.core.compiler.problem.tasks=ignore\n");
                Files.createDirectories(tmp.resolve("generated"));
                command.addAll(List.of("-g", "-d", tmp.resolve("classes").toString(), "-classpath", String.join(":", get(inputs, "classpath")),
                                "-parameters", "--release", "25", "--limit-modules", jdkModules(), "-encoding", "UTF-8", "-failOnWarning", "-properties",
                                prefsFile.toString()));
                if (processorPath.isEmpty()) {
                    command.add("-proc:none");
                } else {
                    command.addAll(List.of("-processorpath", String.join(":", processorPath), "-s", tmp.resolve("generated").toString()));
                }
                command.addAll(get(inputs, "srcs"));
            }
            default -> {
                // SpotBugs as GraalVM runs it: -low -maxRank 15, two known false positives off, and no
                // reports on the classes the Truffle DSL processor generated.
                List<String> classJars = get(inputs, "class_jars");
                if (entries(classJars, ".class").isEmpty()) {
                    Launch.log("no classes to analyze"); // scheme.prelude is resources and a package-info
                    return;
                }
                StringBuilder exclude = new StringBuilder("<FindBugsFilter>\n");
                for (String pattern : List.of("RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", "ASE_ASSERTION_WITH_SIDE_EFFECT",
                                "ASE_ASSERTION_WITH_SIDE_EFFECT_METHOD")) {
                    exclude.append("  <Match><Bug pattern=\"").append(pattern).append("\"/></Match>\n");
                }
                for (String source : entries(get(inputs, "generated_sources"), ".java")) {
                    String cls = source.substring(0, source.length() - ".java".length()).replace('/', '.');
                    exclude.append("  <Match><Class name=\"~").append(cls.replace(".", "[.]")).append(".*\"/></Match>\n");
                }
                exclude.append("</FindBugsFilter>\n");
                Path excludeFile = tmp.resolve("exclude.xml");
                Files.writeString(excludeFile, exclude);
                List<String> aux = new ArrayList<>(get(inputs, "runtime_classpath"));
                aux.removeAll(classJars);
                command.addAll(List.of("-textui", "-low", "-maxRank", "15", "-exclude", excludeFile.toString(), "-auxclasspath",
                                String.join(":", aux), "-exitcode"));
                command.addAll(classJars);
            }
        }
        System.exit(Launch.runTool(jvm, launch.classPath("tool-classpath"), launch.option("tool-main"), command));
    }

    private static List<String> get(Map<String, List<String>> inputs, String kind) {
        return inputs.getOrDefault(kind, List.of());
    }

    /** The names of the jars' entries that end with `suffix`, but for package-info and module-info. */
    private static TreeSet<String> entries(List<String> jars, String suffix) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        for (String jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                    String name = e.nextElement().getName();
                    if (name.endsWith(suffix) && !name.contains("-info")) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }
}
