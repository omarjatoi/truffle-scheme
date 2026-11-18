package scheme.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.CRC32;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.BytecodeDeserializer;
import com.oracle.truffle.api.bytecode.serialization.BytecodeSerializer;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import scheme.compiler.SyntacticEnvironment.Entry;
import scheme.compiler.SyntacticEnvironment.Macro;
import scheme.reader.Scope;
import scheme.reader.ScopeSet;
import scheme.reader.Syntax;
import scheme.runtime.GlobalCell;
import scheme.runtime.GlobalEnvironment;
import scheme.runtime.SchemeContext;
import scheme.runtime.SchemeLanguage;
import scheme.runtime.SchemeLogs;
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
import scheme.runtime.value.LiteralPair;
import scheme.runtime.value.SBigInteger;
import scheme.runtime.value.SBytevector;
import scheme.runtime.value.SChar;
import scheme.runtime.value.SComplex;
import scheme.runtime.value.SConstant;
import scheme.runtime.value.SPair;
import scheme.runtime.value.SRational;
import scheme.runtime.value.SString;
import scheme.runtime.value.SSymbol;
import scheme.runtime.value.SVector;

/**
 * The standard libraries, expanded and compiled ahead of time (design.md D13): a context restores
 * them from this image instead of reading, expanding and compiling the prelude again.
 *
 * <p>The image has two parts. The library bodies are serialized with the Bytecode DSL
 * ({@link SchemeRootNodeGen#serialize}), and each context deserializes them into roots of its own
 * and runs them, as instantiating the libraries would. The expander's state after the standard
 * libraries are instantiated (scopes, namespaces and their cells, syntactic bindings, macros, and
 * each library's exports) is saved as a graph of records that refer to each other by index; a
 * context rebuilds it with its own symbols, scopes and cells. Every value is a record of bytes, so
 * the image can be written to a file; only the prelude's {@link Source}s are objects, made once per
 * image from the bundled resources and shared by the contexts that restore from it.
 *
 * <p>The image comes from the build (M7-05): the build runs a context that writes it
 * ({@code scheme.PreludeImageWrite}, a constant option) into scheme.jar, beside the prelude, as
 * {@value #RESOURCE}. It records the text of every prelude file the standard libraries read, and
 * the primitives and core forms they were expanded with; an image whose files, primitives or core
 * forms differ from the running implementation's is stale and isn't used, nor is one whose checksum
 * doesn't match. In a native image the build-time image is read and checked when the image is built
 * (the language's classes are initialized then, design.md D17), and lives in its heap.
 *
 * <p>Without a usable build-time image, the image is built per JVM: the first context initializes
 * the normal way and builds nothing, so a program run once pays nothing for it; the second builds
 * it, and the ones after restore from it. The option {@code scheme.PreludeImage=false} turns both
 * off for a context (for every context of the process as {@code -Dpolyglot.scheme.PreludeImage=false}),
 * and the constant option {@code scheme.PreludeImageFile} names an image file to use instead of the
 * bundled one (tests).
 */
final class PreludeImage {

    /** Where a context writes the image it builds (the build step), or null. */
    private static final String WRITE = orNull(SchemeLanguage.constant(SchemeLanguage.PRELUDE_IMAGE_WRITE));
    /** A file to use as the build-time image instead of the bundled one (tests), or null. */
    private static final String FILE = orNull(SchemeLanguage.constant(SchemeLanguage.PRELUDE_IMAGE_FILE));
    /** The build-time image's name among the prelude's resources (SourceDir.Resources). */
    static final String RESOURCE = "prelude.image";

    private static final TruffleLogger LOG = SchemeLogs.PRELUDE;

    private static final int MAGIC = 0x52375049;
    /** The format's version: change it whenever what the image holds or how it is written changes. */
    private static final int FORMAT = 3;

    /** Contexts initialized the normal way so far. */
    private static final AtomicInteger NORMAL_INITIALIZATIONS = new AtomicInteger();
    /** The image built in this JVM, or null. */
    private static volatile PreludeImage image;
    /**
     * The bundled build-time image, read and checked when this class is initialized: when the
     * language is built into a native image, at image build time.
     */
    private static final Loaded BUNDLED = WRITE == null && FILE == null ? Loaded.read(SourceDir.Resources.bytes(RESOURCE)) : null;
    /** The image of {@link #FILE}, once read. */
    private static volatile Loaded fromFile;
    /** Whether the build-time image's state has been logged. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    /** A file option's value: null for none (its default, ""). */
    private static String orNull(String file) {
        return file.isEmpty() ? null : file;
    }

    /** What every library body root is named (CodeGenerator); no procedure of the prelude is. */
    private static final String PROGRAM = "<program>";

    // Value records. To save a new kind of value: give it a number here, its children (the values
    // its record refers to) in Writer.children, its record in Writer.record and Reader.readValue,
    // and change FORMAT.
    private static final byte SYMBOL = 1;
    private static final byte STRING = 2;
    private static final byte PAIR = 3;
    private static final byte VECTOR = 4;
    private static final byte BYTEVECTOR = 5;
    private static final byte SYNTAX = 6;
    private static final byte CELL = 7;
    /** A vector with fixnum or flonum storage (M5-06): its elements are written in place. */
    private static final byte VECTOR_LONGS = 8;
    private static final byte VECTOR_DOUBLES = 9;
    private static final byte FIXNUM = 10;
    private static final byte FLONUM = 11;
    private static final byte BOOLEAN = 12;
    private static final byte CHAR = 13;
    /** One of {@link SConstant}'s values, by its printed name. */
    private static final byte CONSTANT = 14;
    private static final byte BIG_INTEGER = 15;
    private static final byte RATIONAL = 16;
    private static final byte COMPLEX = 17;
    /** A Java string or array of them (root names, captured variables' names). */
    private static final byte JAVA_STRING = 18;
    private static final byte JAVA_STRINGS = 19;
    /** A {@link Source}, by its index in the image's sources. */
    private static final byte SOURCE = 20;
    private static final byte SECTION = 21;
    /**
     * A {@link CompilerState.TopLevel}: the library a root was compiled in (its scope and namespace,
     * by index), which code a debugger evaluates in the root's frames sees (design.md D13).
     */
    private static final byte TOP_LEVEL = 22;
    /** A literal's pair ({@link LiteralPair}, R7RS §3.4): a pair record, of the immutable class. */
    private static final byte LITERAL_PAIR = 23;

    // Bindings.
    private static final byte CORE_FORM = 0;
    private static final byte GLOBAL = 1;
    private static final byte MACRO = 2;

    // Bytecode constants.
    private static final byte ROOT = 0;
    private static final byte VALUE = 1;

    // Sources.
    private static final byte RESOURCE_SOURCE = 0;
    private static final byte LITERAL_SOURCE = 1;

    /** The primitives the image was made with, by name, sorted. */
    private final String[] primitives;
    /** The prelude files the standard libraries read, by name: their Source, or null if there was no such file. */
    private final Map<String, Source> resources;
    private final Source[] sources;
    private final byte[] state;
    private final byte[] bytecode;
    private final int bodies;

    private PreludeImage(String[] primitives, Map<String, Source> resources, Source[] sources, byte[] state, byte[] bytecode, int bodies) {
        this.primitives = primitives;
        this.resources = resources;
        this.sources = sources;
        this.state = state;
        this.bytecode = bytecode;
        this.bodies = bodies;
    }

    /** A build-time image, or why it can't be used. */
    record Loaded(PreludeImage image, String problem) {

        @TruffleBoundary
        static Loaded read(byte[] bytes) {
            if (bytes == null) {
                return new Loaded(null, "there is none");
            }
            try {
                return fromBytes(bytes, SourceDir.Resources::bytes);
            } catch (IOException | RuntimeException e) {
                return new Loaded(null, "it is corrupt (" + e + ")");
            }
        }
    }

    /**
     * The image this context can restore from, or null if it should initialize the normal way: the
     * one built in this JVM, else the build-time image if it is up to date.
     */
    @TruffleBoundary
    static PreludeImage get(SchemeContext context) {
        if (WRITE != null || !context.getEnv().getOptions().get(SchemeLanguage.PRELUDE_IMAGE)) {
            return null;
        }
        PreludeImage built = image;
        if (built != null) {
            return built;
        }
        Loaded loaded = buildTime(context);
        String problem = loaded.problem();
        if (loaded.image() != null) {
            problem = loaded.image().primitivesProblem(context);
            if (problem == null) {
                LOG.fine("the standard libraries are restored from the build-time prelude image");
                return loaded.image();
            }
        }
        if (REPORTED.compareAndSet(false, true)) {
            LOG.fine("the build-time prelude image isn't used: " + problem + "; the standard libraries are expanded");
        }
        return null;
    }

    private static Loaded buildTime(SchemeContext context) {
        if (FILE == null) {
            return BUNDLED;
        }
        Loaded loaded = fromFile;
        if (loaded == null) {
            synchronized (PreludeImage.class) {
                loaded = fromFile;
                if (loaded == null) {
                    try {
                        loaded = Loaded.read(context.getEnv().getPublicTruffleFile(FILE).readAllBytes());
                    } catch (IOException | SecurityException e) {
                        loaded = new Loaded(null, "cannot read " + FILE + ": " + e.getMessage());
                    }
                    fromFile = loaded;
                }
            }
        }
        return loaded;
    }

    /**
     * Called by a context about to initialize the normal way: whether it should record its library
     * bodies and build the image afterwards.
     */
    static boolean shouldBuild(SchemeContext context) {
        if (WRITE != null) {
            return true;
        }
        return context.getEnv().getOptions().get(SchemeLanguage.PRELUDE_IMAGE) && image == null && NORMAL_INITIALIZATIONS.incrementAndGet() >= 2;
    }

    /**
     * Builds the image from a context that has just initialized the normal way, and publishes it
     * (or, in the build step, writes it).
     */
    @TruffleBoundary
    static void build(SchemeContext context, CompilerState compilerState, List<CompilerState.LibraryBody> libraryBodies) {
        PreludeImage built;
        try {
            built = new Writer(context, compilerState).write(libraryBodies);
            if (WRITE != null) {
                try (OutputStream out = context.getEnv().getPublicTruffleFile(WRITE).newOutputStream()) {
                    out.write(built.toBytes());
                }
                return;
            }
        } catch (IOException e) {
            throw new IllegalStateException("prelude image: " + e.getMessage(), e);
        }
        synchronized (PreludeImage.class) {
            if (image == null) {
                image = built;
                LOG.fine("built a prelude image in memory: later contexts of this JVM restore from it");
            }
        }
    }

    /** A context's compiler state, rebuilt from the image, and its library bodies, to run in order. */
    record Restored(CompilerState state, List<SchemeRootNode> bodies) {
    }

    /**
     * Rebuilds the compiler state of a context whose primitives are defined, with {@code libraries}
     * making its {@link Libraries}, and deserializes the library bodies.
     */
    @TruffleBoundary
    Restored restore(SchemeLanguage language, SchemeContext context, Function<CompilerState, Libraries> libraries) {
        try {
            return new Reader(language, context).read(libraries);
        } catch (IOException e) {
            throw new IllegalStateException("prelude image: " + e.getMessage(), e);
        }
    }

    /** Why the image doesn't fit this context's primitives, or null if it does. */
    private String primitivesProblem(SchemeContext context) {
        if (!Arrays.equals(primitives, primitiveNames(context.globals))) {
            return "it was made with other primitives";
        }
        return null;
    }

    private static String[] primitiveNames(GlobalEnvironment globals) {
        List<String> names = new ArrayList<>();
        for (GlobalCell cell : globals.cells()) {
            names.add(cell.name.toString());
        }
        Collections.sort(names);
        return names.toArray(new String[0]);
    }

    private static String[] coreFormNames() {
        CoreForm[] forms = CoreForm.values();
        String[] names = new String[forms.length];
        for (int i = 0; i < forms.length; i++) {
            names[i] = forms[i].name();
        }
        return names;
    }

    private static void writeString(DataOutput out, String s) throws IOException {
        out.writeInt(s.length());
        out.writeChars(s);
    }

    private static String readString(DataInput in) throws IOException {
        char[] chars = new char[in.readInt()];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }

    private static void writeStrings(DataOutput out, String[] strings) throws IOException {
        out.writeInt(strings.length);
        for (String s : strings) {
            writeString(out, s);
        }
    }

    private static String[] readStrings(DataInput in) throws IOException {
        String[] strings = new String[in.readInt()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = readString(in);
        }
        return strings;
    }

    private static void writeBytes(DataOutput out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInput in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }

    // ------------------------------------------------------------------------------------------
    // The image as bytes (the build-time image)

    /**
     * The image as bytes: a header, what it was made from (core forms, primitives, and the text of
     * every prelude file read), its sources, its records and bytecode, and a checksum of all that.
     */
    @TruffleBoundary
    byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        writeStrings(out, coreFormNames());
        writeStrings(out, primitives);
        Map<Source, String> resourceNames = new HashMap<>();
        out.writeInt(resources.size());
        for (Map.Entry<String, Source> e : resources.entrySet()) {
            writeString(out, e.getKey());
            out.writeBoolean(e.getValue() != null);
            if (e.getValue() != null) {
                writeBytes(out, e.getValue().getCharacters().toString().getBytes(StandardCharsets.UTF_8));
                resourceNames.put(e.getValue(), e.getKey());
            }
        }
        out.writeInt(sources.length);
        for (Source source : sources) {
            String resource = resourceNames.get(source);
            if (resource != null) {
                out.writeByte(RESOURCE_SOURCE);
                writeString(out, resource);
            } else if (source.getPath() == null && source.hasCharacters()) {
                out.writeByte(LITERAL_SOURCE);
                writeString(out, source.getName());
                writeString(out, source.getCharacters().toString());
                out.writeBoolean(source.isInternal());
            } else {
                throw new IllegalStateException("prelude image: a source that is neither a prelude file nor literal text: " + source.getName());
            }
        }
        out.writeInt(bodies);
        writeBytes(out, state);
        writeBytes(out, bytecode);
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * Reads an image written by {@link #toBytes}, if it is up to date: its checksum matches, and
     * the core forms and the prelude's files ({@code files} reads them by name) are the ones it was
     * made from. Its prelude sources are made from those files.
     */
    @TruffleBoundary
    static Loaded fromBytes(byte[] bytes, Function<String, byte[]> files) throws IOException {
        if (bytes.length < 16) {
            return new Loaded(null, "it is truncated");
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - Long.BYTES);
        if (crc.getValue() != ByteBuffer.wrap(bytes, bytes.length - Long.BYTES, Long.BYTES).getLong()) {
            return new Loaded(null, "its checksum doesn't match");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - Long.BYTES));
        if (in.readInt() != MAGIC) {
            return new Loaded(null, "it isn't a prelude image");
        }
        int format = in.readInt();
        if (format != FORMAT) {
            return new Loaded(null, "its format is " + format + ", not " + FORMAT);
        }
        if (!Arrays.equals(readStrings(in), coreFormNames())) {
            return new Loaded(null, "it was made with other core forms");
        }
        String[] primitives = readStrings(in);
        Map<String, Source> resources = new LinkedHashMap<>();
        for (int n = in.readInt(); n > 0; n--) {
            String name = readString(in);
            byte[] then = in.readBoolean() ? readBytes(in) : null;
            byte[] now = files.apply(name);
            if (then == null || now == null) {
                if (then != now) {
                    return new Loaded(null, "prelude/" + name + (now == null ? " was removed" : " was added"));
                }
                resources.put(name, null);
            } else if (!Arrays.equals(then, now)) {
                return new Loaded(null, "prelude/" + name + " changed");
            } else {
                resources.put(name, SourceDir.Resources.source(name, now));
            }
        }
        Source[] sources = new Source[in.readInt()];
        for (int i = 0; i < sources.length; i++) {
            byte kind = in.readByte();
            if (kind == RESOURCE_SOURCE) {
                String name = readString(in);
                sources[i] = resources.get(name);
                if (sources[i] == null) {
                    throw new IOException("no prelude file " + name);
                }
            } else if (kind == LITERAL_SOURCE) {
                String name = readString(in);
                String text = readString(in);
                sources[i] = Source.newBuilder(SchemeLanguage.ID, text, name).internal(in.readBoolean()).build();
            } else {
                throw new IOException("bad source " + kind);
            }
        }
        int bodies = in.readInt();
        byte[] state = readBytes(in);
        byte[] bytecode = readBytes(in);
        if (in.available() != 0) {
            throw new IOException("bytes after the bytecode");
        }
        return new Loaded(new PreludeImage(primitives, resources, sources, state, bytecode, bodies), null);
    }

    // ------------------------------------------------------------------------------------------
    // Writing

    private static final class Writer {

        private final SchemeContext context;
        private final CompilerState compilerState;

        private final List<Source> sources = new ArrayList<>();
        private final Map<Source, Integer> sourceIds = new HashMap<>();

        private final ByteArrayOutputStream valueBytes = new ByteArrayOutputStream();
        private final DataOutputStream values = new DataOutputStream(valueBytes);
        private final Map<Object, Integer> valueIds = new IdentityHashMap<>();

        private final List<Scope> scopes = new ArrayList<>();
        private final Map<Scope, Integer> scopeIds = new IdentityHashMap<>();
        private final List<ScopeSet> scopeSets = new ArrayList<>();
        private final Map<ScopeSet, Integer> scopeSetIds = new HashMap<>();

        private final List<GlobalEnvironment> namespaces = new ArrayList<>();
        private final Map<GlobalEnvironment, Integer> namespaceIds = new IdentityHashMap<>();
        private final Map<GlobalCell, Integer> cellIds = new IdentityHashMap<>();

        private final ByteArrayOutputStream macroBytes = new ByteArrayOutputStream();
        private final DataOutputStream macros = new DataOutputStream(macroBytes);
        private final Map<Macro, Integer> macroIds = new IdentityHashMap<>();

        Writer(SchemeContext context, CompilerState compilerState) {
            this.context = context;
            this.compilerState = compilerState;
        }

        PreludeImage write(List<CompilerState.LibraryBody> libraryBodies) throws IOException {
            namespace(context.globals);
            namespace(compilerState.interaction);
            for (GlobalEnvironment namespace : compilerState.top.namespaces().values()) {
                namespace(namespace);
            }

            // The bytecode first: its constants become value records.
            ByteArrayOutputStream bytecodeBytes = new ByteArrayOutputStream();
            BytecodeSerializer serializer = (serializerContext, out, object) -> {
                if (object instanceof SchemeRootNode root) {
                    out.writeByte(ROOT);
                    serializerContext.writeBytecodeNode(out, root);
                } else {
                    out.writeByte(VALUE);
                    out.writeInt(value(object));
                }
            };
            SchemeRootNodeGen.serialize(new DataOutputStream(bytecodeBytes), serializer, b -> {
                for (CompilerState.LibraryBody body : libraryBodies) {
                    new CodeGenerator(b, body.topLevel()).generate(body.body());
                }
            });

            // Then the expander's state.
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(rest);
            out.writeInt(scope(compilerState.interactionScope));
            Map<Scope, GlobalEnvironment> registered = compilerState.top.namespaces();
            out.writeInt(registered.size());
            for (Map.Entry<Scope, GlobalEnvironment> e : registered.entrySet()) {
                out.writeInt(scope(e.getKey()));
                out.writeInt(namespaceId(e.getValue()));
            }
            Map<SSymbol, List<Entry>> entries = compilerState.top.entries();
            out.writeInt(entries.size());
            for (Map.Entry<SSymbol, List<Entry>> e : entries.entrySet()) {
                out.writeInt(value(e.getKey()));
                out.writeInt(e.getValue().size());
                for (Entry entry : e.getValue()) {
                    out.writeInt(scopeSet(entry.scopes()));
                    binding(out, entry.binding());
                }
            }
            // (r7rs %primitives) too: its exports are the primitives' cells when Libraries was made.
            out.writeInt(compilerState.libraries.loaded().size());
            for (Libraries.Library library : compilerState.libraries.loaded().values()) {
                writeString(out, library.name());
                out.writeInt(library.exports().size());
                for (Map.Entry<SSymbol, Object> e : library.exports().entrySet()) {
                    out.writeInt(value(e.getKey()));
                    binding(out, e.getValue());
                }
            }

            // Scopes are made again in the order of their ids, so scope sets keep their order.
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            DataOutputStream imageOut = new DataOutputStream(all);
            List<Scope> sorted = new ArrayList<>(scopes);
            Collections.sort(sorted);
            Map<Scope, Integer> rank = new IdentityHashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                rank.put(sorted.get(i), i);
            }
            imageOut.writeInt(scopes.size());
            for (Scope scope : scopes) {
                imageOut.writeInt(rank.get(scope));
                imageOut.writeBoolean(scope.isIntroduction());
            }
            imageOut.writeInt(scopeSets.size());
            for (ScopeSet set : scopeSets) {
                imageOut.writeInt(set.size());
                for (int i = 0; i < set.size(); i++) {
                    imageOut.writeInt(scopeIds.get(set.get(i)));
                }
            }
            imageOut.writeInt(namespaces.size());
            for (GlobalEnvironment namespace : namespaces) {
                imageOut.writeInt(namespace.cells().size());
                for (GlobalCell cell : namespace.cells()) {
                    writeString(imageOut, cell.name.toString());
                }
                imageOut.writeInt(namespace.hiddenCells().size());
                for (GlobalCell cell : namespace.hiddenCells()) {
                    writeString(imageOut, cell.name.toString());
                }
            }
            imageOut.writeInt(valueIds.size());
            values.flush();
            valueBytes.writeTo(imageOut);
            imageOut.writeInt(macroIds.size());
            macros.flush();
            macroBytes.writeTo(imageOut);
            out.flush();
            rest.writeTo(imageOut);
            imageOut.flush();

            List<String> primitives = new ArrayList<>();
            for (Object export : compilerState.libraries.loaded().get(Libraries.PRIMITIVES).exports().values()) {
                if (export instanceof GlobalCell cell) {
                    primitives.add(cell.name.toString());
                }
            }
            Collections.sort(primitives);
            return new PreludeImage(primitives.toArray(new String[0]), new LinkedHashMap<>(compilerState.libraries.resources().read()), sources.toArray(new Source[0]),
                            all.toByteArray(), bytecodeBytes.toByteArray(), libraryBodies.size());
        }

        private void namespace(GlobalEnvironment namespace) {
            if (namespaceIds.containsKey(namespace)) {
                return;
            }
            namespaceIds.put(namespace, namespaces.size());
            namespaces.add(namespace);
            for (GlobalCell cell : namespace.cells()) {
                cellIds.put(cell, cellIds.size());
            }
            for (GlobalCell cell : namespace.hiddenCells()) {
                cellIds.put(cell, cellIds.size());
            }
        }

        private int namespaceId(GlobalEnvironment namespace) {
            Integer id = namespaceIds.get(namespace);
            if (id == null) {
                throw new IllegalStateException("prelude image: a namespace that isn't registered");
            }
            return id;
        }

        private int scope(Scope scope) {
            Integer id = scopeIds.get(scope);
            if (id == null) {
                id = scopes.size();
                scopeIds.put(scope, id);
                scopes.add(scope);
            }
            return id;
        }

        private int scopeSet(ScopeSet set) {
            Integer id = scopeSetIds.get(set);
            if (id == null) {
                for (int i = 0; i < set.size(); i++) {
                    scope(set.get(i));
                }
                id = scopeSets.size();
                scopeSetIds.put(set, id);
                scopeSets.add(set);
            }
            return id;
        }

        private int source(Source source) {
            Integer id = sourceIds.get(source);
            if (id == null) {
                id = sources.size();
                sourceIds.put(source, id);
                sources.add(source);
            }
            return id;
        }

        private void binding(DataOutput out, Object binding) throws IOException {
            if (binding instanceof CoreForm form) {
                out.writeByte(CORE_FORM);
                out.writeInt(form.ordinal());
            } else if (binding instanceof GlobalCell cell) {
                out.writeByte(GLOBAL);
                out.writeInt(cellId(cell));
            } else if (binding instanceof Macro macro) {
                out.writeByte(MACRO);
                out.writeInt(macro(macro));
            } else {
                throw new IllegalStateException("prelude image: unexpected top-level binding " + binding);
            }
        }

        private int cellId(GlobalCell cell) {
            Integer id = cellIds.get(cell);
            if (id == null) {
                throw new IllegalStateException("prelude image: cell " + cell.name + " is in no namespace");
            }
            return id;
        }

        private int macro(Macro macro) throws IOException {
            Integer id = macroIds.get(macro);
            if (id != null) {
                return id;
            }
            if (!(macro.transformer() instanceof SyntaxRules rules) || rules.definedBy().topScope() == null) {
                throw new IllegalStateException("prelude image: a macro that isn't a top-level syntax-rules");
            }
            int spec = value(rules.spec());
            int namespace = namespaceId(rules.definedBy().namespace());
            int scope = scope(rules.definedBy().topScope());
            macros.writeInt(spec);
            macros.writeInt(namespace);
            macros.writeInt(scope);
            id = macroIds.size();
            macroIds.put(macro, id);
            return id;
        }

        /** The values inside {@code o} that its record refers to. */
        private static Object[] children(Object o) {
            if (o instanceof SPair p) {
                return new Object[]{p.car, p.cdr};
            } else if (o instanceof SVector v) {
                return v.isObjects() ? v.objects() : new Object[0];
            } else if (o instanceof Syntax s) {
                return s.source == null ? new Object[]{s.open()} : new Object[]{s.open(), s.source};
            } else if (o instanceof SComplex c) {
                return new Object[]{c.real, c.imag};
            } else if (o instanceof SourceSection section) {
                return new Object[]{section.getSource()};
            }
            return new Object[0];
        }

        /**
         * The index of {@code o}'s value record, written (after its children's) if it is new. No
         * Java recursion (AGENTS.md rule 4): the walk keeps its own stack.
         */
        private int value(Object root) throws IOException {
            Integer known = valueIds.get(root);
            if (known != null) {
                return known;
            }
            Deque<Object> stack = new ArrayDeque<>();
            Set<Object> open = Collections.newSetFromMap(new IdentityHashMap<>());
            stack.push(root);
            while (!stack.isEmpty()) {
                Object o = stack.peek();
                if (valueIds.containsKey(o)) {
                    stack.pop();
                    continue;
                }
                boolean ready = true;
                for (Object child : children(o)) {
                    if (!valueIds.containsKey(child)) {
                        if (open.contains(child)) {
                            throw new IllegalStateException("prelude image: circular constant");
                        }
                        stack.push(child);
                        ready = false;
                    }
                }
                if (ready) {
                    stack.pop();
                    open.remove(o);
                    record(o);
                } else {
                    open.add(o);
                }
            }
            return valueIds.get(root);
        }

        private void record(Object o) throws IOException {
            if (o instanceof Long n) {
                values.writeByte(FIXNUM);
                values.writeLong(n);
            } else if (o instanceof Double d) {
                values.writeByte(FLONUM);
                values.writeLong(Double.doubleToRawLongBits(d));
            } else if (o instanceof Boolean b) {
                values.writeByte(BOOLEAN);
                values.writeBoolean(b);
            } else if (o instanceof SChar c) {
                values.writeByte(CHAR);
                values.writeInt(c.codePoint);
            } else if (o instanceof SConstant c) {
                values.writeByte(CONSTANT);
                writeString(values, c.toString());
            } else if (o instanceof SBigInteger n) {
                values.writeByte(BIG_INTEGER);
                writeBytes(values, n.value.toByteArray());
            } else if (o instanceof SRational q) {
                values.writeByte(RATIONAL);
                writeBytes(values, q.numerator.toByteArray());
                writeBytes(values, q.denominator.toByteArray());
            } else if (o instanceof SComplex c) {
                values.writeByte(COMPLEX);
                values.writeInt(valueIds.get(c.real));
                values.writeInt(valueIds.get(c.imag));
            } else if (o instanceof String s) {
                values.writeByte(JAVA_STRING);
                writeString(values, s);
            } else if (o instanceof String[] strings) {
                values.writeByte(JAVA_STRINGS);
                writeStrings(values, strings);
            } else if (o instanceof Source s) {
                values.writeByte(SOURCE);
                values.writeInt(source(s));
            } else if (o instanceof SourceSection s) {
                values.writeByte(SECTION);
                values.writeInt(valueIds.get(s.getSource()));
                values.writeBoolean(s.isAvailable());
                if (s.isAvailable()) {
                    values.writeInt(s.getCharIndex());
                    values.writeInt(s.getCharLength());
                }
            } else if (o instanceof SSymbol s) {
                values.writeByte(SYMBOL);
                writeString(values, s.toString());
            } else if (o instanceof SString s) {
                values.writeByte(STRING);
                writeString(values, s.toString());
                values.writeBoolean(s.isImmutable());
            } else if (o instanceof SPair p) {
                values.writeByte(p instanceof LiteralPair ? LITERAL_PAIR : PAIR);
                values.writeInt(valueIds.get(p.car));
                values.writeInt(valueIds.get(p.cdr));
            } else if (o instanceof SVector v && v.isLongs()) {
                values.writeByte(VECTOR_LONGS);
                values.writeInt(v.length);
                for (long element : v.longs()) {
                    values.writeLong(element);
                }
                values.writeBoolean(v.immutable);
            } else if (o instanceof SVector v && v.isDoubles()) {
                values.writeByte(VECTOR_DOUBLES);
                values.writeInt(v.length);
                for (double element : v.doubles()) {
                    values.writeDouble(element);
                }
                values.writeBoolean(v.immutable);
            } else if (o instanceof SVector v) {
                values.writeByte(VECTOR);
                values.writeInt(v.length);
                for (Object element : v.objects()) {
                    values.writeInt(valueIds.get(element));
                }
                values.writeBoolean(v.immutable);
            } else if (o instanceof SBytevector v) {
                values.writeByte(BYTEVECTOR);
                values.writeInt(v.bytes.length);
                values.write(v.bytes);
                values.writeBoolean(v.immutable);
            } else if (o instanceof Syntax s) {
                values.writeByte(SYNTAX);
                values.writeInt(valueIds.get(s.open()));
                values.writeInt(scopeSet(s.scopes));
                values.writeInt(s.source == null ? -1 : valueIds.get(s.source));
            } else if (o instanceof GlobalCell cell) {
                values.writeByte(CELL);
                values.writeInt(cellId(cell));
            } else if (o instanceof CompilerState.TopLevel topLevel) {
                values.writeByte(TOP_LEVEL);
                values.writeInt(scope(topLevel.scope()));
                values.writeInt(namespaceId(topLevel.namespace()));
                values.writeBoolean(topLevel.closed());
            } else {
                throw new IllegalStateException("prelude image: unexpected constant " + o + " (" + o.getClass().getName() + ")");
            }
            valueIds.put(o, valueIds.size());
        }
    }

    /**
     * A restored {@code syntax-rules} macro, compiled when it is first used: most programs use few
     * of the prelude's macros, and compiling them all would make every context pay for each.
     */
    private static final class Uncompiled implements Transformer {
        private final Object spec;
        private final Expander definer;
        private final SSymbol ellipsis;
        private final SSymbol underscore;
        private SyntaxRules compiled;

        Uncompiled(Object spec, Expander definer, SSymbol ellipsis, SSymbol underscore) {
            this.spec = spec;
            this.definer = definer;
            this.ellipsis = ellipsis;
            this.underscore = underscore;
        }

        @Override
        public Object transform(Object form, Expander expander) {
            SyntaxRules rules = compiled;
            if (rules == null) {
                rules = new SyntaxRules(spec, definer, ellipsis, underscore);
                compiled = rules;
            }
            return rules.transform(form, expander);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading

    private final class Reader {

        private final SchemeLanguage language;
        private final SchemeContext context;

        private Scope[] scopes;
        private ScopeSet[] scopeSets;
        private GlobalEnvironment[] namespaces;
        private GlobalCell[] cells;
        private Object[] values;
        private Macro[] macros;

        Reader(SchemeLanguage language, SchemeContext context) {
            this.language = language;
            this.context = context;
        }

        Restored read(Function<CompilerState, Libraries> makeLibraries) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
            int[] ranks = new int[in.readInt()];
            boolean[] introductionByRank = new boolean[ranks.length];
            for (int i = 0; i < ranks.length; i++) {
                ranks[i] = in.readInt();
                introductionByRank[ranks[i]] = in.readBoolean();
            }
            Scope[] byRank = new Scope[ranks.length];
            for (int i = 0; i < byRank.length; i++) {
                byRank[i] = introductionByRank[i] ? Scope.introduction() : new Scope();
            }
            scopes = new Scope[ranks.length];
            for (int i = 0; i < ranks.length; i++) {
                scopes[i] = byRank[ranks[i]];
            }
            scopeSets = new ScopeSet[in.readInt()];
            for (int i = 0; i < scopeSets.length; i++) {
                ScopeSet set = ScopeSet.EMPTY;
                for (int n = in.readInt(); n > 0; n--) {
                    set = set.add(scopes[in.readInt()]);
                }
                scopeSets[i] = set;
            }
            namespaces = new GlobalEnvironment[in.readInt()];
            List<GlobalCell> allCells = new ArrayList<>();
            for (int i = 0; i < namespaces.length; i++) {
                GlobalEnvironment namespace = i == 0 ? context.globals : new GlobalEnvironment();
                namespaces[i] = namespace;
                for (int n = in.readInt(); n > 0; n--) {
                    allCells.add(namespace.cell(context.symbols.intern(readString(in))));
                }
                for (int n = in.readInt(); n > 0; n--) {
                    allCells.add(namespace.freshCell(context.symbols.intern(readString(in))));
                }
            }
            cells = allCells.toArray(new GlobalCell[0]);
            values = new Object[in.readInt()];
            for (int i = 0; i < values.length; i++) {
                values[i] = readValue(in);
            }
            int macroCount = in.readInt();
            int[][] macroSpecs = new int[macroCount][];
            for (int i = 0; i < macroCount; i++) {
                macroSpecs[i] = new int[]{in.readInt(), in.readInt(), in.readInt()};
            }

            CompilerState compilerState = new CompilerState(scopes[in.readInt()], namespaces[1]);
            compilerState.libraries = makeLibraries.apply(compilerState);
            // The prelude's files are read: later lookups of them find these.
            compilerState.libraries.resources().read().putAll(resources);
            // Macros are compiled again, each by an expander for the unit that defined it, when
            // first used.
            Map<GlobalEnvironment, Expander> definers = new IdentityHashMap<>();
            macros = new Macro[macroCount];
            SSymbol ellipsis = context.symbols.intern("...");
            SSymbol underscore = context.symbols.intern("_");
            for (int i = 0; i < macroCount; i++) {
                int[] m = macroSpecs[i];
                GlobalEnvironment namespace = namespaces[m[1]];
                Scope scope = scopes[m[2]];
                Expander definer = definers.computeIfAbsent(namespace,
                                ns -> new Expander(context.symbols, ns, compilerState.top, scope, compilerState.libraries, compilerState.libraries.resources()));
                macros[i] = new Macro(new Uncompiled(values[m[0]], definer, ellipsis, underscore));
            }
            for (int n = in.readInt(); n > 0; n--) {
                Scope scope = scopes[in.readInt()];
                GlobalEnvironment namespace = namespaces[in.readInt()];
                if (namespace != compilerState.interaction) {
                    compilerState.top.registerNamespace(scope, namespace);
                }
            }
            for (int n = in.readInt(); n > 0; n--) {
                SSymbol name = (SSymbol) values[in.readInt()];
                for (int k = in.readInt(); k > 0; k--) {
                    ScopeSet set = scopeSets[in.readInt()];
                    compilerState.top.restore(name, set, readBinding(in));
                }
            }
            for (int n = in.readInt(); n > 0; n--) {
                String name = readString(in);
                Map<SSymbol, Object> exports = new LinkedHashMap<>();
                for (int k = in.readInt(); k > 0; k--) {
                    SSymbol symbol = (SSymbol) values[in.readInt()];
                    exports.put(symbol, readBinding(in));
                }
                compilerState.libraries.restore(new Libraries.Library(name, exports));
            }

            Object[] contextValues = values;
            BytecodeDeserializer deserializer = (deserializerContext, input) -> input.readByte() == ROOT ? deserializerContext.readBytecodeNode(input) : contextValues[input.readInt()];
            BytecodeRootNodes<SchemeRootNode> nodes = SchemeRootNodeGen.deserialize(language, BytecodeConfig.WITH_SOURCE,
                            () -> SerializationUtils.createByteBufferDataInput(ByteBuffer.wrap(bytecode)), deserializer);
            List<SchemeRootNode> bodyRoots = new ArrayList<>(bodies);
            for (SchemeRootNode root : nodes.getNodes()) {
                if (PROGRAM.equals(root.getName())) {
                    bodyRoots.add(root);
                }
            }
            if (bodyRoots.size() != bodies) {
                throw new IllegalStateException("prelude image: " + bodyRoots.size() + " library bodies instead of " + bodies);
            }
            return new Restored(compilerState, bodyRoots);
        }

        private Object readBinding(DataInput in) throws IOException {
            byte kind = in.readByte();
            int index = in.readInt();
            switch (kind) {
                case CORE_FORM:
                    return CoreForm.values()[index];
                case GLOBAL:
                    return cells[index];
                case MACRO:
                    return macros[index];
                default:
                    throw new IOException("bad binding " + kind);
            }
        }

        private Object readValue(DataInput in) throws IOException {
            byte kind = in.readByte();
            switch (kind) {
                case FIXNUM:
                    return in.readLong();
                case FLONUM:
                    return Double.longBitsToDouble(in.readLong());
                case BOOLEAN:
                    return in.readBoolean();
                case CHAR:
                    return SChar.of(in.readInt());
                case CONSTANT:
                    return constant(readString(in));
                case BIG_INTEGER:
                    return SBigInteger.normalize(new BigInteger(readBytes(in)));
                case RATIONAL:
                    return SRational.make(new BigInteger(readBytes(in)), new BigInteger(readBytes(in)));
                case COMPLEX:
                    return SComplex.make(values[in.readInt()], values[in.readInt()]);
                case JAVA_STRING:
                    return readString(in);
                case JAVA_STRINGS:
                    return readStrings(in);
                case SOURCE:
                    return sources[in.readInt()];
                case SECTION: {
                    Source source = (Source) values[in.readInt()];
                    return in.readBoolean() ? source.createSection(in.readInt(), in.readInt()) : source.createUnavailableSection();
                }
                case SYMBOL:
                    return context.symbols.intern(readString(in));
                case STRING: {
                    String s = readString(in);
                    return in.readBoolean() ? SString.literal(s) : SString.of(s);
                }
                case PAIR:
                    return new SPair(values[in.readInt()], values[in.readInt()]);
                case LITERAL_PAIR:
                    return new LiteralPair(values[in.readInt()], values[in.readInt()]);
                case VECTOR: {
                    Object[] elements = new Object[in.readInt()];
                    for (int i = 0; i < elements.length; i++) {
                        elements[i] = values[in.readInt()];
                    }
                    return new SVector(elements, in.readBoolean());
                }
                case VECTOR_LONGS: {
                    long[] elements = new long[in.readInt()];
                    for (int i = 0; i < elements.length; i++) {
                        elements[i] = in.readLong();
                    }
                    return new SVector(elements, in.readBoolean());
                }
                case VECTOR_DOUBLES: {
                    double[] elements = new double[in.readInt()];
                    for (int i = 0; i < elements.length; i++) {
                        elements[i] = in.readDouble();
                    }
                    return new SVector(elements, in.readBoolean());
                }
                case BYTEVECTOR: {
                    byte[] bytes = new byte[in.readInt()];
                    in.readFully(bytes);
                    return new SBytevector(bytes, in.readBoolean());
                }
                case SYNTAX: {
                    Object datum = values[in.readInt()];
                    ScopeSet set = scopeSets[in.readInt()];
                    int source = in.readInt();
                    return new Syntax(datum, set, source < 0 ? null : (SourceSection) values[source]);
                }
                case CELL:
                    return cells[in.readInt()];
                case TOP_LEVEL:
                    return new CompilerState.TopLevel(scopes[in.readInt()], namespaces[in.readInt()], in.readBoolean());
                default:
                    throw new IOException("bad value " + kind);
            }
        }

        private Object constant(String name) throws IOException {
            for (SConstant c : new SConstant[]{SConstant.EMPTY_LIST, SConstant.UNSPECIFIED, SConstant.EOF, SConstant.DEFAULT}) {
                if (c.toString().equals(name)) {
                    return c;
                }
            }
            throw new IOException("bad constant " + name);
        }
    }
}
