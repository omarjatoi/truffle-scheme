package scheme.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.launcher.AbstractLanguageLauncher;
import org.graalvm.options.OptionCategory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * The {@code scheme} launcher (design.md D17), on GraalVM's launcher framework:
 *
 * <pre>
 * scheme [option...] [-I dir]... [--end-status=n] [--stack-size=size] [file.scm [arg...]]
 * </pre>
 *
 * <p>With a file, it runs the file as a program (design.md D15), and {@code command-line} returns
 * the file and the arguments after it. Each {@code -I dir} adds a directory to the library path.
 * With no file, it starts a REPL in the interaction environment: it reads lines until they hold a
 * complete input (design.md D13's incomplete-source signal), evaluates it, and writes the values of
 * each of its forms. An error prints a message and the REPL goes on; end of input ends it. It reads
 * its lines from Scheme's console input port, so Scheme code it runs reads the same input. On a
 * terminal, the lines come from JLine, with line editing and a history ({@link TerminalInput},
 * plan.md M7-07), and Ctrl-C interrupts an evaluation and goes back to the prompt
 * ({@link Interrupter}, plan.md M8-06). It evaluates on a thread with a large Java stack, unless a
 * stack size is given ({@link EvaluationThread}): the context is created, used and closed there.
 * The framework adds
 * {@code --help}, {@code --version}, {@code --engine.*}, {@code --log.*} and the other standard
 * options, and the tools' options when they are on the module path.
 *
 * <p>The differential tests compare a program's stdout and exit status with chibi-scheme's (design.md
 * D8), so the exit statuses follow chibi-scheme's:
 *
 * <ul>
 * <li>0 when the program or the REPL's input ends, or the status {@code --end-status} gives: so a
 * caller can tell a program that ends from one that calls {@code (exit 0)} (M7-06; the native
 * conformance runner does, as Larceny's exit tests need).</li>
 * <li>The program's status when it calls {@code exit} or {@code emergency-exit}, in a program or in
 * the REPL.</li>
 * <li>{@value #EXIT_UNCAUGHT_ERROR} (EX_SOFTWARE) for an uncaught error in a program. The message
 * goes to stderr; differential tests never compare its wording.</li>
 * <li>{@value #EXIT_NO_INPUT} (EX_NOINPUT) when the file can't be read, {@value #EXIT_USAGE}
 * (EX_USAGE) for a malformed {@code -I}, {@code --stack-size} or {@code --end-status}.</li>
 * <li>GraalVM's launcher framework's statuses for the options its launchers share (design.md D17,
 * "Options"), whether the framework or this launcher rejects them:
 * {@value #EXIT_UNRECOGNIZED} for one it doesn't recognize, {@value #EXIT_INVALID_OPTION} for one
 * that can't be used (a bad value, an experimental option without
 * {@code --experimental-options}, a {@code --vm.} option the VM wouldn't apply).</li>
 * </ul>
 */
public final class SchemeMain extends AbstractLanguageLauncher {

    public static final String LANGUAGE = "scheme";
    /** chibi-scheme's status for an uncaught error. */
    public static final int EXIT_UNCAUGHT_ERROR = 70;
    /** EX_USAGE. */
    public static final int EXIT_USAGE = 64;
    /** EX_NOINPUT. */
    public static final int EXIT_NO_INPUT = 66;
    /**
     * GraalVM's launcher framework's status for an option that can't be used: an invalid value, an
     * experimental option without {@code --experimental-options} ({@code Launcher.abort}).
     */
    public static final int EXIT_INVALID_OPTION = 1;
    /**
     * The framework's status for an argument it doesn't recognize
     * ({@code Launcher.abortUnrecognizedArgument}).
     */
    public static final int EXIT_UNRECOGNIZED = 2;

    private static final String LIBRARY_PATH_OPTION = "scheme.LibraryPath";
    /** The evaluation thread's stack size, for the runtime (StackBudget). */
    private static final String STACK_SIZE_RUNTIME_OPTION = "scheme.StackSize";
    /** Whether the runtime buffers standard output (design.md D10). */
    private static final String BUFFER_OUTPUT_OPTION = "scheme.BufferOutput";
    private static final String END_STATUS_OPTION = "--end-status=";
    private static final String STACK_SIZE_OPTION = "--stack-size=";
    /** GraalVM's launchers' option for the host VM's options: {@code --vm.<option>}. */
    private static final String VM_OPTION = "--vm.";
    /**
     * The native launcher's stack size options ({@code --vm.Xss}, {@code --vm.XX:StackSize=}, or
     * the same in an argument file), which the isolate also applies to new threads. Only the
     * launcher sees them: a shared library's isolate reports no input arguments
     * ({@link EvaluationThread#givenToTheVM}).
     */
    private static final String STACK_SIZE_VM_OPTION = "Xss";
    private static final String STACK_SIZE_XX_VM_OPTION = "XX:StackSize=";
    /** An option that chooses a collector, which a native image has from its build. */
    private static final Pattern NATIVE_GC_CHOICE = Pattern.compile("XX:[+-]Use\\w*GC");
    /**
     * The {@code --vm.} options the native launcher's isolate applies: the heap's and the stack's
     * sizes, system properties, and {@code -XX:} options, which it checks as it is created (one it
     * doesn't know stops it). The thin launcher's C part also takes an argument file
     * ({@code --vm.@file}, whose options the launcher checks as it checks these:
     * {@link ArgumentFile}) and, on macOS, {@code --vm.XstartOnFirstThread}. The isolate takes any
     * other option and ignores it ({@code -ea}, {@code -Xint}, {@code -verbose:gc},
     * {@code -Xlog:gc}, {@code -Xfoo}, ...), so the launcher rejects those (design.md D17, "VM
     * options").
     */
    private static final Pattern NATIVE_VM_OPTION = Pattern.compile("X(mx|ms|mn|ss).+|D[^=]+(=.*)?|XX:.+");
    /** A VM argument file: {@code --vm.@file}. */
    private static final String VM_ARGUMENT_FILE_OPTION = "--vm.@";
    /**
     * The options the thin launcher's C part takes out of an argument file for its JVM mode
     * ({@code launcher.cc}, {@code expand_vm_arg_file}), which in the native launcher go nowhere.
     */
    private static final Pattern ARGUMENT_FILE_PATH_OPTION = Pattern.compile("--class-path=.*|--module-path=.*|-Djava\\.library\\.path=.*");
    /** The class path and module path options, which only the thin launcher's JVM mode takes. */
    private static final Pattern NATIVE_PATH_OPTION = Pattern.compile("(cp|classpath|p|-module-path)=.*|Djava\\.library\\.path=.*");
    /**
     * The tools the JVM launcher has on its module path (//bazel:tools_modules), which the native
     * launcher is built without.
     */
    private static final Set<String> JVM_TOOLS = Set.of("inspect", "dap", "cpusampler", "cputracer", "memtracer", "heapmonitor", "coverage");
    /**
     * A Truffle option that is the process's on HotSpot: the process's first engine fixes it, and
     * a later engine's value only gets a warning (HotSpotTruffleRuntime: "it was previously
     * initialized"). The framework's first engine is its own, which it creates without the
     * command line's options to check them, so the launcher sets the system property that the
     * runtime also reads, before the framework creates that engine.
     */
    private static final String TRACE_TRANSFER_OPTION = "engine.TraceTransferToInterpreter";
    /**
     * Another option the HotSpot runtime keeps for the process: the last engine created sets it. A
     * warm-up's engine, created after the program's, would set it back.
     */
    private static final String PROFILING_OPTION = "engine.Profiling";
    /**
     * The compiler's instrumentation, which prints its profile as the process ends. On the JVM,
     * GraalVM 25.5's compiler in libgraal fails every compilation it instruments (a
     * ClassCastException, docs/upstream.md), and the native launcher's crashes; Graal compiled
     * for HotSpot ({@code -XX:-UseJVMCINativeLibrary}) instruments them. 25.5 still takes them as
     * {@code engine.} options, deprecated.
     */
    private static final List<String> INSTRUMENT_OPTIONS = List.of("compiler.InstrumentBoundaries", "compiler.InstrumentBranches", "engine.InstrumentBoundaries",
                    "engine.InstrumentBranches");
    /** The JVM option that chooses libgraal (the default) or Graal compiled for HotSpot. */
    private static final Pattern LIBGRAAL_CHOICE = Pattern.compile("-XX:([+-])UseJVMCINativeLibrary");
    /** The framework's help topics besides the languages' and the tools' ids. */
    private static final Set<String> HELP_TOPICS = Set.of("", "vm", "engine", "compiler", "all", "languages", "tools", "internal", "expert");
    /**
     * A host VM option in {@code java}'s form ({@code -Xmx2g}, {@code -XX:+PrintGC},
     * {@code -Dname=value}): the launcher takes it as {@code --vm.<option>}.
     */
    private static final Pattern HOST_VM_OPTION = Pattern.compile("-[XD].+");
    /** The prompts, shown only when stdin and stdout are a terminal. */
    private static final String PROMPT = "> ";
    private static final String CONTINUATION_PROMPT = "  ";

    private final List<String> libraryPath = new ArrayList<>();
    /** The program file, or null for the REPL. */
    private String file;
    private String[] programArguments = new String[0];
    /** The status when the program or the REPL's input ends without {@code exit}. */
    private int endStatus;
    /** In this process, for tests ({@link #run}): the process's state isn't the launcher's. */
    private boolean inProcess;
    /** {@code --version} or {@code --show-version}. */
    private VersionAction versionAction = VersionAction.None;
    /** The evaluation thread's stack ({@link EvaluationThread}): {@code --stack-size}, or null. */
    private Long stackSize;
    /** {@code --vm.Xss} or {@code --vm.XX:StackSize=}, or null. */
    private Long vmStackSize;
    /** False when {@code --engine.Compilation=false} (or its system property) turns the JIT off. */
    private boolean compilation = true;
    /** Whether the command line sets {@value #BUFFER_OUTPUT_OPTION}. */
    private boolean bufferOutputGiven;
    /** Whether the command line sets {@value #PROFILING_OPTION}: no warm-up then. */
    private boolean profilingGiven;
    /** Whether the launcher set {@value #TRACE_TRANSFER_OPTION}'s system property itself. */
    private boolean traceTransferPropertySet;
    /** The last {@code --help:<topic>}'s topic, or null. */
    private String helpTopic;
    /** The argument that gave {@link #helpTopic}. */
    private String helpArgument;

    private static final String COMPILATION_OPTION = "engine.Compilation";
    /** The warm-up takes a core of its own: below this, it would take the program's. */
    private static final int WARM_UP_MIN_PROCESSORS = 4;

    /**
     * What the launcher warms the JIT with, on a thread and in a context of its own, while it runs a
     * program (plan.md M8-03, design.md D17): a procedure of the usual operations (calls, a closure,
     * fixnum and flonum arithmetic and comparisons, pairs, vectors), called often enough to be
     * compiled. A process's first Truffle compilations take 30-140 ms each, against 2-3 ms for the
     * same code later (the compiler parses and caches the interpreter's methods it partially
     * evaluates), and the host code of the interpreter starts in HotSpot's interpreter; both are
     * per process, so this way a program's first compilations and iterations pay less of them.
     */
    public static final String WARM_UP = """
                    (import (scheme base))
                    (define (%launcher-warm-up i v)
                      (let ((x (vector-ref v (remainder i 3)))
                            (f (lambda (y) (cons y i))))
                        (if (and (pair? x) (not (null? (cdr x))))
                            (car (f (+ (car x) i)))
                            (if (< i 0) (* 2.0 i) (- (+ i 1) (* i 2))))))
                    (let loop ((i 0) (v (vector (list 1 2) 0.5 '())))
                      (when (< i 2000)
                        (%launcher-warm-up i v)
                        (vector-set! v 1 (+ (vector-ref v 1) 1.5))
                        (loop (+ i 1) v)))
                    """;

    public static void main(String[] args) {
        new SchemeMain().launch(args);
    }

    @Override
    protected String getLanguageId() {
        return LANGUAGE;
    }

    /**
     * Takes {@code -I dir} (or {@code -Idir}), then the program file and its arguments. Everything
     * else that starts with {@code -} goes to the framework, which knows the standard options and
     * rejects the rest; {@code --} ends the options.
     */
    @Override
    protected List<String> preprocessArguments(List<String> arguments, Map<String, String> polyglotOptions) {
        List<String> unrecognized = new ArrayList<>();
        int i = 0;
        while (i < arguments.size()) {
            String arg = arguments.get(i);
            if (arg.equals("-I")) {
                if (i + 1 >= arguments.size()) {
                    throw abort("-I needs a directory: -I <dir>", EXIT_USAGE);
                }
                libraryPath.add(arguments.get(i + 1));
                i += 2;
            } else if (arg.startsWith("-I")) {
                libraryPath.add(arg.substring(2));
                i++;
            } else if (arg.startsWith(END_STATUS_OPTION)) {
                endStatus = status(arg.substring(END_STATUS_OPTION.length()));
                i++;
            } else if (arg.startsWith(STACK_SIZE_OPTION)) {
                stackSize = stackSize(arg.substring(STACK_SIZE_OPTION.length()));
                i++;
            } else if (arg.startsWith(VM_OPTION) && arg.length() > VM_OPTION.length()) {
                vmOption(arg);
                unrecognized.add(arg);
                i++;
            } else if (HOST_VM_OPTION.matcher(arg).matches()) {
                throw abort(arg + ": the launcher takes the VM's options as --vm." + arg.substring(1) + ", before the program file (docs/usage.md)", EXIT_UNRECOGNIZED);
            } else if (arg.equals("--version")) {
                versionAction = VersionAction.PrintAndExit;
                i++;
            } else if (arg.equals("--show-version")) {
                versionAction = VersionAction.PrintAndContinue;
                i++;
            } else if (arg.equals("--")) {
                i++;
                break;
            } else if (arg.startsWith("-") && arg.length() > 1) {
                polyglotOption(arg);
                unrecognized.add(arg);
                i++;
            } else {
                break;
            }
        }
        if (i < arguments.size()) {
            file = arguments.get(i);
            programArguments = arguments.subList(i + 1, arguments.size()).toArray(new String[0]);
        }
        return unrecognized;
    }

    /**
     * Notes what the launcher needs to know of an option the framework parses: a help topic
     * ({@link #printHelp}), and on HotSpot {@value #TRACE_TRANSFER_OPTION}, whose system property
     * the launcher sets now, before the framework creates the process's first engine.
     */
    private void polyglotOption(String arg) {
        if (arg.startsWith("--help:")) {
            String topic = arg.substring("--help:".length());
            helpTopic = topic.contains(":") ? topic.substring(0, topic.indexOf(':')) : topic;
            helpArgument = arg;
        } else if (arg.equals("--help")) {
            helpTopic = null;
        } else if (!isAOT() && !inProcess && (arg.equals("--" + TRACE_TRANSFER_OPTION) || arg.equals("--" + TRACE_TRANSFER_OPTION + "=true")) &&
                        System.getProperty("polyglot." + TRACE_TRANSFER_OPTION) == null) {
            System.setProperty("polyglot." + TRACE_TRANSFER_OPTION, "true");
            traceTransferPropertySet = true;
        }
    }

    /**
     * An option the framework doesn't know. In the native launcher, a tool's option says that the
     * tools are the JVM launcher's.
     */
    @Override
    protected AbortException abortUnrecognizedArgument(String argument) {
        String name = argument.startsWith("--") ? argument.substring(2).split("[.=]", 2)[0] : "";
        if (isAOT() && JVM_TOOLS.contains(name)) {
            throw abort(argument + ": the native launcher is built without GraalVM's tools; the JVM launcher, bazel-bin/scheme, has them (docs/usage.md, \"Debugging and profiling\")",
                            EXIT_UNRECOGNIZED);
        }
        return super.abortUnrecognizedArgument(argument);
    }

    /**
     * Checks a {@code --vm.<option>} among the launcher's options, as GraalVM's language launchers
     * take them (design.md D17, "VM options"). On the JVM, the script that starts it
     * ({@code bazel-bin/scheme}) has given them to the JVM as its own options and taken them out,
     * so one that reaches the launcher was never applied: an error, rather than ignoring it as
     * the framework would. The native launcher is GraalVM's thin launcher, whose C part created
     * the VM (this shared library's isolate) with the {@code --vm.} options it found on the
     * command line: they go back to the framework, which checks that they are the ones this
     * method kept (otherwise the C part runs the process again with these) and sets none itself.
     * A class path and a collector, which a native image doesn't have at run time, are errors that
     * say what to do instead, and so is an option the isolate would have ignored
     * ({@link #NATIVE_VM_OPTION}). {@code --vm.Xss} is also the evaluation thread's stack. The
     * options in an argument file ({@code --vm.@file}) are checked the same way
     * ({@link #argumentFile}).
     */
    private void vmOption(String arg) {
        String option = arg.substring(VM_OPTION.length());
        if (option.equals("cp") || option.equals("classpath")) {
            throw abort("'" + arg + "' argument must be of the form '" + arg + "=<classpath>', not two separate arguments", EXIT_INVALID_OPTION);
        }
        boolean classPath = option.startsWith("cp=") || option.startsWith("classpath=");
        boolean argumentFile = arg.startsWith(VM_ARGUMENT_FILE_OPTION);
        if (!isAOT()) {
            // java takes an argument file as @file.
            String java = classPath ? "-cp " + option.substring(option.indexOf('=') + 1) : argumentFile ? option : "-" + option;
            throw abort(arg + " is an option of the JVM, which has already started without it: give it to java itself (" + java +
                            ", before -m), or run the launcher with bazel-bin/scheme, which starts the JVM with its --vm. options", EXIT_INVALID_OPTION);
        }
        if (classPath) {
            throw abort(arg + ": the native launcher has no class path; the JVM launcher, bazel-bin/scheme, takes " + arg, EXIT_INVALID_OPTION);
        }
        if (argumentFile) {
            argumentFile(arg, option.substring(1));
        } else if (!option.equals("XstartOnFirstThread") || !System.getProperty("os.name", "").startsWith("Mac")) {
            nativeVmOption(arg, option, VM_OPTION, arg);
        }
    }

    /**
     * Checks the options in an argument file, {@code --vm.@file} (plan.md M11-03, design.md D17,
     * "Options"). The thin launcher's C part has read the file and created the isolate with its
     * options, as if each had been given as a {@code --vm.} one, but the framework gives the
     * launcher only {@code --vm.@file}: so the launcher reads the file as the C part does
     * ({@link ArgumentFile}) and checks each option, in {@code java}'s form ({@code -Xmx1g}), as
     * {@link #vmOption} checks the {@code --vm.} one, with the same errors and statuses. An
     * {@code -Xss} in it is the evaluation thread's stack too. The C part also takes a class path,
     * a module path and {@code -Djava.library.path=} out of the file for its JVM mode, and in the
     * native launcher drops them: errors here. One that isn't an option at all (no {@code -}) is
     * one more the isolate would ignore.
     */
    private void argumentFile(String arg, String path) {
        List<String> options;
        try {
            options = ArgumentFile.read(Path.of(path));
        } catch (IOException | InvalidPathException e) {
            throw abort(arg + ": could not read " + path + ": " + e, EXIT_INVALID_OPTION);
        }
        for (String option : options) {
            String given = "'" + option + "' in " + arg;
            if (ARGUMENT_FILE_PATH_OPTION.matcher(option).matches()) {
                throw abort(given + ": the native launcher has no class or module path and loads no Java libraries; the JVM launcher, bazel-bin/scheme, takes " + arg,
                                EXIT_INVALID_OPTION);
            }
            if (option.length() < 2 || option.charAt(0) != '-') {
                throw abort(given + ": not a VM option, which an argument file has in java's form (-Xmx1g); the native launcher's VM, a native image, would ignore it",
                                EXIT_INVALID_OPTION);
            }
            nativeVmOption(given, option.substring(1), "-", arg);
        }
    }

    /**
     * Checks one of the native launcher's VM options, {@code option} without its prefix
     * ({@code --vm.} on the command line, {@code -} in an argument file), {@code given} as it is
     * named in an error, which the JVM launcher takes as {@code jvm}; and notes a stack size,
     * which is also the evaluation thread's.
     */
    private void nativeVmOption(String given, String option, String prefix, String jvm) {
        if (NATIVE_PATH_OPTION.matcher(option).matches()) {
            throw abort(given + ": the native launcher has no module path and loads no Java libraries; the JVM launcher, bazel-bin/scheme, takes " + jvm, EXIT_INVALID_OPTION);
        }
        if (NATIVE_GC_CHOICE.matcher(option).matches()) {
            // Seldom reached: the isolate's creation rejects an option it doesn't know, before the
            // launcher runs, unless the command line has --jvm (then the thin launcher asks it to
            // ignore them, and the framework rejects --jvm).
            throw abort(given + ": the native launcher's collector is chosen when it is built (bazel build --//:native_gc=serial|epsilon|G1 //:scheme-native, " +
                            "docs/usage.md, \"Choosing a garbage collector\"); the JVM launcher, bazel-bin/scheme, takes " + jvm, EXIT_INVALID_OPTION);
        }
        if (!NATIVE_VM_OPTION.matcher(option).matches()) {
            throw abort(given + ": the native launcher's VM, a native image, would ignore it: it takes " + prefix + "Xmx, " + prefix + "Xms, " + prefix + "Xmn, " + prefix + "Xss, " +
                            prefix + "D<name>=<value> and the " + prefix + "XX: options --help:vm lists. The JVM launcher, bazel-bin/scheme, takes " + jvm, EXIT_INVALID_OPTION);
        }
        if (option.startsWith(STACK_SIZE_VM_OPTION)) {
            vmStackSize = EvaluationThread.parseSize(option.substring(STACK_SIZE_VM_OPTION.length()));
        } else if (option.startsWith(STACK_SIZE_XX_VM_OPTION)) {
            vmStackSize = EvaluationThread.parseSize(option.substring(STACK_SIZE_XX_VM_OPTION.length()));
        }
    }

    /** An {@code --end-status} value: an exit status, 0 to 255. */
    private int status(String value) {
        try {
            int status = Integer.parseInt(value);
            if (status >= 0 && status <= 255) {
                return status;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw abort(END_STATUS_OPTION + " needs an exit status from 0 to 255: " + END_STATUS_OPTION + value, EXIT_USAGE);
    }

    /** A {@code --stack-size} value: a size, as {@code -Xss} takes it, or {@code default}. */
    private long stackSize(String value) {
        Long size = EvaluationThread.parseSize(value);
        if (size == null) {
            throw abort(STACK_SIZE_OPTION + " needs a size (such as 512k, 64m or 1g) or " + EvaluationThread.DEFAULT + ": " + STACK_SIZE_OPTION + value, EXIT_USAGE);
        }
        return size;
    }

    /**
     * The evaluation thread's stack: {@code --stack-size}, else {@code --vm.Xss}, else the
     * launcher's thread if the VM was given a stack size, else a large one.
     */
    private long evaluationStackSize() {
        if (stackSize != null) {
            return stackSize;
        } else if (vmStackSize != null) {
            return vmStackSize;
        } else if (EvaluationThread.givenToTheVM()) {
            return EvaluationThread.LAUNCHER_THREAD;
        }
        return EvaluationThread.LARGE_STACK;
    }

    /**
     * Checks the options that the launcher sets or that can't take effect, once the framework has
     * parsed them; an option is given on the command line or as its {@code polyglot.<name>} system
     * property. {@code --scheme.LibraryPath=...} comes before the {@code -I} directories.
     */
    @Override
    protected void validateArguments(Map<String, String> polyglotOptions) {
        if (traceTransferPropertySet) {
            // The framework's engine, the process's first, has fixed the option for the process
            // (on). The program's engine has it from the command line; the warm-up's goes without.
            System.clearProperty("polyglot." + TRACE_TRANSFER_OPTION);
        }
        compilation = !"false".equals(option(polyglotOptions, COMPILATION_OPTION));
        bufferOutputGiven = option(polyglotOptions, BUFFER_OUTPUT_OPTION) != null;
        profilingGiven = option(polyglotOptions, PROFILING_OPTION) != null;
        if (option(polyglotOptions, STACK_SIZE_RUNTIME_OPTION) != null) {
            throw abort("--" + STACK_SIZE_RUNTIME_OPTION + ": the launcher tells the runtime the size of the stack it evaluates on itself; " + STACK_SIZE_OPTION +
                            "<size> chooses that stack (--help)", EXIT_INVALID_OPTION);
        }
        for (String instrument : INSTRUMENT_OPTIONS) {
            if ("true".equals(option(polyglotOptions, instrument))) {
                if (isAOT()) {
                    throw abort("--" + instrument + ": the native launcher's compiler fails with it (GraalVM 25.5, docs/upstream.md); the JVM launcher, bazel-bin/scheme, takes it",
                                    EXIT_INVALID_OPTION);
                } else if (libgraal()) {
                    throw abort("--" + instrument + ": GraalVM 25.5's compiler in libgraal fails every compilation it instruments (docs/upstream.md), so it would print nothing; " +
                                    "run java with -XX:-UseJVMCINativeLibrary, as bazel-bin/scheme does with this option", EXIT_INVALID_OPTION);
                }
            }
        }
        String given = polyglotOptions.remove(LIBRARY_PATH_OPTION);
        if (given == null) {
            // The launcher sets the option itself, which a system property doesn't override.
            given = System.getProperty("polyglot." + LIBRARY_PATH_OPTION);
        }
        if (given != null && !given.isEmpty()) {
            libraryPath.addAll(0, Arrays.asList(given.split(File.pathSeparator)));
        }
    }

    /** An option's value on the command line, else its system property's, else null. */
    private static String option(Map<String, String> polyglotOptions, String name) {
        String value = polyglotOptions.get(name);
        return value != null ? value : System.getProperty("polyglot." + name);
    }

    /**
     * Whether the JVM compiles with libgraal, its default (the last
     * {@code -XX:±UseJVMCINativeLibrary} among its options, also through {@code JDK_JAVA_OPTIONS}
     * or {@code JAVA_TOOL_OPTIONS}, says otherwise).
     */
    private static boolean libgraal() {
        boolean libgraal = true;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            Matcher m = LIBGRAAL_CHOICE.matcher(arg);
            if (m.matches()) {
                libgraal = m.group(1).equals("+");
            }
        }
        return libgraal;
    }

    @Override
    protected void launch(Context.Builder builder) {
        configure(builder, libraryPath);
        if (versionAction != VersionAction.None) {
            try (Engine engine = Engine.create()) {
                runVersionAction(versionAction, engine);
            } catch (IllegalArgumentException e) {
                // An option given as a polyglot.<name> system property that Truffle rejects.
                throw abort("in the polyglot.<name> system properties (such as --vm.Dpolyglot.<name>=<value>): " + e.getMessage(), EXIT_INVALID_OPTION);
            }
        }
        // The context is created, used and closed on the evaluation thread. An exit status is
        // thrown (AbortException), on to the framework on this thread.
        EvaluationThread.run(evaluationStackSize(), threadStack -> evaluate(builder, threadStack));
    }

    /**
     * Runs the program or the REPL; throws the exit status, unless it is 0. The thread's stack is
     * {@code threadStack} bytes, or of a size the launcher doesn't know if that is
     * {@link EvaluationThread#LAUNCHER_THREAD}.
     */
    private void evaluate(Context.Builder builder, long threadStack) {
        if (threadStack != EvaluationThread.LAUNCHER_THREAD) {
            // The context is created on this thread: the runtime takes the spill limit from its
            // size instead of measuring the stack (design.md "Stack-depth spill").
            builder.option(STACK_SIZE_RUNTIME_OPTION, Long.toString(threadStack));
        }
        boolean tty = isTTY();
        if (tty && !bufferOutputGiven) {
            // On a terminal, each write shows at once; elsewhere the runtime buffers standard
            // output (design.md D10, "Standard output").
            builder.option(BUFFER_OUTPUT_OPTION, "false");
        }
        int status;
        if (file == null) {
            TerminalInput terminal = tty ? TerminalInput.open(CONTINUATION_PROMPT) : null;
            if (terminal != null) {
                builder.in(terminal);
            }
            try {
                status = repl(builder, terminal, System.out, System.err, endStatus);
            } finally {
                closeQuietly(terminal);
            }
        } else {
            boolean warmUp = compilation && !profilingGiven && !isAOT() && Runtime.getRuntime().availableProcessors() >= WARM_UP_MIN_PROCESSORS;
            try {
                status = runProgram(builder, file, programArguments, System.err, endStatus, warmUp ? SchemeMain::startWarmUp : null);
            } finally {
                abandonWarmUp();
            }
        }
        System.out.flush();
        if (status != 0) {
            throw exit(status);
        }
    }

    /**
     * Runs {@link #WARM_UP} on a daemon thread, in a context of its own with no I/O, while this
     * thread reads, expands and runs the program: on another core, so the program's thread does what
     * it did before, and the JVM doesn't wait for it to end. It starts once the program's context is
     * initialized, so that the program's is the process's first context: the one that takes a
     * pre-initialized context, and that reports, on its error stream, what became of the build-time
     * prelude image. Only for a
     * program with the JIT on, on the JVM (a native image's compiler has nothing to parse: its
     * graphs are built with the image) and with a core to spare. Nothing it does can change the
     * program's run: it shares no context or engine with it, nor the options given on the command
     * line, and its failures are dropped.
     */
    private static void startWarmUp() {
        Thread thread = new Thread(() -> warmUp(Context.newBuilder(LANGUAGE)), "scheme-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Runs {@link #WARM_UP} in a context of {@code builder}'s, with no I/O; drops its failures. Closes
     * the context unless the program's run is over ({@link WarmUpClose}).
     */
    public static void warmUp(Context.Builder builder) {
        Context context;
        try {
            context = builder.in(InputStream.nullInputStream()).out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream()).build();
        } catch (RuntimeException e) {
            return; // Only ever a warm-up: the program's run doesn't depend on it.
        }
        try {
            context.eval(Source.newBuilder(LANGUAGE, WARM_UP, "<warm-up>").internal(true).buildLiteral());
        } catch (RuntimeException e) {
            // As above.
        } finally {
            WarmUpClose.close(context);
        }
    }

    /** The program's run is over: a warm-up still running is left open for the JVM's exit to end. */
    private static void abandonWarmUp() {
        WarmUpClose.abandon();
    }

    /**
     * The warm-up's context closes under this class's lock, and not at all once the program has
     * run ({@link #abandon}). Closing a context closes its engine, and Truffle's shutdown hook
     * flushes the log handler of every engine it found open as the JVM began to exit: of one that
     * closed in the meantime, the closed handler fails an assertion, which it prints
     * ("AssertionError: The log handler is closed.", docs/upstream.md, "An engine that closes as
     * the JVM exits"). The warm-up's engine is the only one that can close while the launcher
     * exits, so the program's thread, once the program has run, waits for a close in progress and
     * stops any later one, before the JVM exits. A class of its own, so that SchemeMain's fields
     * aren't shared between threads.
     */
    private static final class WarmUpClose {
        private static boolean abandoned;

        private WarmUpClose() {
        }

        static synchronized void close(Context context) {
            if (!abandoned) {
                try {
                    context.close();
                } catch (RuntimeException e) {
                    // Only ever a warm-up: the program's run doesn't depend on it.
                }
            }
        }

        static synchronized void abandon() {
            abandoned = true;
        }
    }

    private static void closeQuietly(TerminalInput terminal) {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                System.err.println("WARNING: cannot save the REPL history: " + e.getMessage());
            }
        }
    }

    /**
     * The launcher's part of {@code --help}. The framework prints it for {@code --help}, and for a
     * {@code --help:<topic>} it has no help for, in place of an error: the launcher makes that
     * one.
     */
    @Override
    protected void printHelp(OptionCategory maxCategory) {
        if (helpTopic != null && !HELP_TOPICS.contains(helpTopic)) {
            throw abort("Unrecognized help topic: '" + helpArgument + "'. Use --help for the topics (--help:languages, --help:tools, --help:engine, --help:<id>, ...).",
                            EXIT_UNRECOGNIZED);
        }
        println("Usage: scheme [option...] [-I dir]... [--stack-size=<size>] [file.scm [arg...]]");
        println("");
        println("Runs file.scm as an R7RS program; (command-line) returns the file and its arguments.");
        println("With no file, starts a REPL that reads from stdin and writes each value.");
        println("On a terminal, its lines can be edited, and its history is kept in ~/.scheme_history");
        println("(or the file $" + TerminalInput.HISTORY_VARIABLE + " names; empty for none).");
        println("There, Ctrl-C interrupts a running input and goes back to the prompt.");
        println("");
        println("Scheme options:");
        launcherOption("-I <dir>", "Add a directory to the library path (repeatable).");
        launcherOption("--end-status=<n>", "Exit with status n when the program or the REPL's input ends without exit (default 0).");
        launcherOption(STACK_SIZE_OPTION + "<size>", "Evaluate on a thread with this much Java stack (such as 512k, 64m, 1g), or on the launcher's own with " + STACK_SIZE_OPTION +
                        EvaluationThread.DEFAULT + ". Default: " + (EvaluationThread.LARGE_STACK >> 20) +
                        "m, unless the VM was given a stack size (-Xss), which is then the evaluation's. Deep recursion beyond the stack spills to the heap, which is slower.");
        launcherOption("--version", "Print the version and exit.");
        launcherOption("--show-version", "Print the version and continue.");
        launcherOption("--", "End the options: the next argument is the file.");
    }

    @Override
    protected void collectArguments(Set<String> options) {
        options.add("-I");
        options.add(END_STATUS_OPTION);
        options.add(STACK_SIZE_OPTION);
        options.add("--version");
        options.add("--show-version");
        options.add("--");
    }

    /**
     * A context builder with file access, the process's environment variables and this library path
     * (design.md D7, D10).
     */
    public static Context.Builder newContext(List<String> libraryPath) {
        return configure(Context.newBuilder(LANGUAGE), libraryPath);
    }

    private static Context.Builder configure(Context.Builder builder, List<String> libraryPath) {
        return builder.allowIO(IOAccess.ALL).allowEnvironmentAccess(EnvironmentAccess.INHERIT).option(LIBRARY_PATH_OPTION, String.join(File.pathSeparator, libraryPath));
    }

    /**
     * The launcher without the framework's options, in this process: {@code [-I dir]...
     * [--end-status=n] [file.scm [arg...]]}, with no file reading REPL input from {@code in}.
     * Returns the exit status. It evaluates on the caller's thread: {@code --stack-size} is
     * accepted, and does nothing.
     */
    public static int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        SchemeMain launcher = new SchemeMain();
        launcher.inProcess = true;
        try {
            List<String> unrecognized = launcher.preprocessArguments(Arrays.asList(args), new HashMap<>());
            if (!unrecognized.isEmpty()) {
                errors.println("ERROR: unrecognized option: " + unrecognized.get(0));
                return EXIT_USAGE;
            }
        } catch (AbortException e) {
            errors.println("ERROR: " + e.getMessage());
            return e.getExitCode();
        }
        Context.Builder builder = newContext(launcher.libraryPath).in(in).out(out).err(err);
        if (launcher.file == null) {
            return repl(builder, null, out, err, launcher.endStatus);
        }
        return runProgram(builder, launcher.file, launcher.programArguments, errors, launcher.endStatus, null);
    }

    /** {@link #run(String[], InputStream, OutputStream, OutputStream)} with empty stdin. */
    public static int run(String[] args, OutputStream out, OutputStream err) {
        return run(args, InputStream.nullInputStream(), out, err);
    }

    /**
     * Runs {@code file} as a program, with {@code arguments} after it in {@code command-line}.
     * Returns {@code endStatus} if it ends without exit. Unless null, {@code initialized} runs once
     * the context is initialized, before the program is read.
     */
    static int runProgram(Context.Builder builder, String file, String[] arguments, PrintStream errors, int endStatus, Runnable initialized) {
        Source source;
        try {
            source = Source.newBuilder(LANGUAGE, new File(file)).build();
        } catch (IOException e) {
            errors.println("ERROR: cannot read " + file + ": " + e.getMessage());
            return EXIT_NO_INPUT;
        }
        String[] commandLine = new String[arguments.length + 1];
        commandLine[0] = file;
        System.arraycopy(arguments, 0, commandLine, 1, arguments.length);
        try (Context context = builder.arguments(LANGUAGE, commandLine).build()) {
            if (initialized != null) {
                context.initialize(LANGUAGE);
                initialized.run();
            }
            context.eval(source);
            return endStatus;
        } catch (PolyglotException e) {
            if (e.isExit()) {
                return e.getExitStatus();
            }
            report(e, errors);
            return EXIT_UNCAUGHT_ERROR;
        } catch (IllegalArgumentException e) {
            return invalidOption(e, errors);
        }
    }

    /**
     * Creating a context failed with an option Truffle rejects: one given as a
     * {@code polyglot.<name>} system property ({@code --vm.Dpolyglot.scheme.NoSuchOption=1}),
     * which the framework doesn't check, or a constant option given a value other than the one
     * the process fixed (design.md D17, "Constant options"), which Truffle's message explains.
     * Reports it and returns {@value #EXIT_INVALID_OPTION}.
     */
    private static int invalidOption(IllegalArgumentException e, PrintStream errors) {
        boolean constant = e.getMessage().contains(" is constant and is already fixed");
        errors.println("ERROR: " + (constant ? "" : "in the polyglot.<name> system properties (such as --vm.Dpolyglot.<name>=<value>): ") + e.getMessage());
        return EXIT_INVALID_OPTION;
    }

    /**
     * The REPL: reads lines until they hold a complete input, evaluates it in the interaction
     * environment as an interactive source (which writes the values of each of its forms, design.md
     * D17), and goes on until end of input or {@code exit}. Returns the exit status:
     * {@code endStatus} at end of input.
     *
     * <p>It reads its lines with Scheme's {@code read-line} from the context's console input port,
     * the initial {@code current-input-port}, so that it and the Scheme code it runs share one
     * input and its buffering (plan.md M7-07): {@code (read-line)} reads the line after the one
     * that called it, and the REPL goes on with whatever Scheme code leaves. That port reads the
     * builder's standard input, which on a terminal is {@code terminal}, otherwise null.
     */
    static int repl(Context.Builder builder, TerminalInput terminal, OutputStream out, OutputStream err, int endStatus) {
        PrintStream output = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errors = new PrintStream(err, true, StandardCharsets.UTF_8);
        try (Context context = builder.arguments(LANGUAGE, new String[0]).build()) {
            Interrupter interrupter = null;
            if (terminal != null) {
                interrupter = Interrupter.of(context, () -> giveUp(terminal, errors));
                terminal.onInterrupt(interrupter);
            }
            // The primitives themselves: a later definition of read-line in the session doesn't
            // change how the REPL reads.
            Value readLine = context.eval(internal("%read-line"));
            Value stdin = context.eval(internal("(%stdin-port)"));
            StringBuilder input = new StringBuilder();
            int first = 1;
            int lineNumber = 0;
            while (true) {
                String line;
                try {
                    if (terminal != null) {
                        terminal.takeInterrupted();
                        terminal.prompt(input.isEmpty() ? PROMPT : CONTINUATION_PROMPT);
                    }
                    Value read = readLine.execute(stdin);
                    line = read.isString() ? read.asString() : null;
                } catch (PolyglotException e) {
                    if (e.isInterrupted()) {
                        // Not expected (an evaluation's interrupt has finished before the REPL
                        // reads), but if one comes, it drops the unfinished input, like Ctrl-C.
                        input.setLength(0);
                        continue;
                    }
                    errors.println("ERROR: cannot read the input: " + e.getMessage());
                    return EXIT_NO_INPUT;
                } finally {
                    if (terminal != null) {
                        terminal.prompt("");
                    }
                }
                if (terminal != null && terminal.takeInterrupted()) {
                    // Ctrl-C drops the unfinished input.
                    input.setLength(0);
                    continue;
                }
                if (line == null) {
                    if (!input.toString().isBlank()) {
                        // End of input inside a datum: report what the reader says about it.
                        Integer status = evaluate(context, input, first, true, errors, terminal, interrupter);
                        if (status != null) {
                            return status;
                        }
                    }
                    if (terminal != null) {
                        output.println();
                    }
                    return endStatus;
                }
                lineNumber++;
                if (input.isEmpty()) {
                    first = lineNumber;
                }
                input.append(line).append('\n');
                if (input.toString().isBlank()) {
                    input.setLength(0);
                    continue;
                }
                Integer status = evaluate(context, input, first, false, errors, terminal, interrupter);
                if (status != null) {
                    return status;
                }
            }
        } catch (PolyglotException e) {
            // Creating the context failed, or closing it after exit or cancellation.
            if (e.isExit()) {
                return e.getExitStatus();
            }
            report(e, errors);
            return EXIT_UNCAUGHT_ERROR;
        } catch (IllegalArgumentException e) {
            return invalidOption(e, errors);
        }
    }

    /** Launcher code evaluated in the interaction environment, hidden from tools. */
    private static Source internal(String text) {
        return Source.newBuilder(LANGUAGE, text, "<repl>").internal(true).cached(false).buildLiteral();
    }

    /**
     * Evaluates one REPL input, and clears it unless it is incomplete and more may come
     * ({@code atEnd} is false); on a terminal, a complete input goes in the history, as one entry.
     * Returns the exit status if the input exited, otherwise null. An interrupt (Ctrl-C on a
     * terminal, {@code interrupter}) prints a message, and the REPL goes on.
     */
    private static Integer evaluate(Context context, StringBuilder input, int firstLine, boolean atEnd, PrintStream errors,
                    TerminalInput terminal, Interrupter interrupter) {
        String name = "<repl:" + firstLine + ">";
        Source source = Source.newBuilder(LANGUAGE, input.toString(), name).interactive(true).cached(false).buildLiteral();
        if (terminal != null) {
            terminal.running(input.toString());
        }
        if (interrupter != null) {
            interrupter.begin();
        }
        try {
            context.eval(source);
        } catch (PolyglotException e) {
            if (e.isIncompleteSource() && !atEnd) {
                return null;
            }
            done(input, terminal);
            if (e.isExit()) {
                return e.getExitStatus();
            }
            if (e.isInterrupted()) {
                // On a new line: after the terminal's ^C, or the output the evaluation left.
                errors.println();
                errors.println("Interrupted");
                return null;
            }
            report(e, errors);
            if (e.isCancelled()) {
                return EXIT_UNCAUGHT_ERROR;
            }
            return null;
        } finally {
            if (interrupter != null) {
                interrupter.end();
            }
            if (terminal != null) {
                terminal.running(null);
            }
        }
        done(input, terminal);
        return null;
    }

    /** A complete input: into the history, then cleared. */
    private static void done(StringBuilder input, TerminalInput terminal) {
        if (terminal != null) {
            terminal.addHistory(input.toString());
        }
        input.setLength(0);
    }

    /**
     * A second Ctrl-C finds the first one's interrupt still waiting (plan.md M8-06): ends the
     * process, as SIGINT does by default, after saving the history (the running input with it)
     * and giving the terminal back.
     * It halts: the evaluation still runs, and a shutdown hook could wait for it.
     */
    private static void giveUp(TerminalInput terminal, PrintStream errors) {
        errors.println();
        errors.println("Interrupt failed: exiting");
        closeQuietly(terminal);
        System.out.flush();
        Runtime.getRuntime().halt(Interrupter.EXIT_INTERRUPTED);
    }

    private static void report(PolyglotException e, PrintStream errors) {
        if (e.isInternalError()) {
            // Internal errors are bugs and are never turned into Scheme errors (design.md D8).
            errors.println("INTERNAL ERROR: " + e.getMessage());
            e.printStackTrace(errors);
        } else {
            errors.println("ERROR: " + e.getMessage());
        }
    }
}
