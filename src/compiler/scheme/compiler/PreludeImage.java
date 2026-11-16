package scheme.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
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
 * The standard libraries, expanded and compiled once per JVM (design.md D13): a context restores
 * them from this image instead of reading, expanding and compiling the prelude again.
 *
 * <p>The image has two parts. The library bodies are serialized with the Bytecode DSL
 * ({@link SchemeRootNodeGen#serialize}), and each context deserializes them into roots of its own
 * and runs them, as instantiating the libraries would. The expander's state after the standard
 * libraries are instantiated (scopes, namespaces and their cells, syntactic bindings, macros, and
 * each library's exports) is saved as a graph of records that refer to each other by index; a
 * context rebuilds it with its own symbols, scopes and cells. Values that no context can change
 * (numbers, characters, sources and source sections) are shared, not copied.
 *
 * <p>The first context of a JVM initializes the normal way and builds nothing, so a program run
 * once pays nothing for the image. The second builds it, and the ones after restore from it.
 * {@code -Dscheme.preludeImage=false} turns the image off.
 */
final class PreludeImage {

    private static final boolean ENABLED = !"false".equals(System.getProperty("scheme.preludeImage"));
    /** Contexts initialized the normal way so far. */
    private static final AtomicInteger NORMAL_INITIALIZATIONS = new AtomicInteger();
    private static volatile PreludeImage image;

    /** What every library body root is named (CodeGenerator); no procedure of the prelude is. */
    private static final String PROGRAM = "<program>";

    // Value records.
    private static final byte SHARED = 0;
    private static final byte SYMBOL = 1;
    private static final byte STRING = 2;
    private static final byte PAIR = 3;
    private static final byte VECTOR = 4;
    private static final byte BYTEVECTOR = 5;
    private static final byte SYNTAX = 6;
    private static final byte CELL = 7;

    // Bindings.
    private static final byte CORE_FORM = 0;
    private static final byte GLOBAL = 1;
    private static final byte MACRO = 2;

    // Bytecode constants.
    private static final byte ROOT = 0;
    private static final byte VALUE = 1;

    /** Values that are the same in every context. */
    private final Object[] shared;
    private final byte[] state;
    private final byte[] bytecode;
    private final int bodies;

    private PreludeImage(Object[] shared, byte[] state, byte[] bytecode, int bodies) {
        this.shared = shared;
        this.state = state;
        this.bytecode = bytecode;
        this.bodies = bodies;
    }

    /** The image, or null if there is none yet. */
    static PreludeImage get() {
        return ENABLED ? image : null;
    }

    /**
     * Called by a context about to initialize the normal way: whether it should record its library
     * bodies and build the image afterwards.
     */
    static boolean shouldBuild() {
        return ENABLED && image == null && NORMAL_INITIALIZATIONS.incrementAndGet() >= 2;
    }

    /** Builds the image from a context that has just initialized the normal way, and publishes it. */
    @TruffleBoundary
    static void build(SchemeContext context, CompilerState compilerState, List<Ast.Lambda> libraryBodies) {
        PreludeImage built;
        try {
            built = new Writer(context, compilerState).write(libraryBodies);
        } catch (IOException e) {
            throw new IllegalStateException("prelude image: " + e.getMessage(), e);
        }
        synchronized (PreludeImage.class) {
            if (image == null) {
                image = built;
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

    private static boolean isShared(Object o) {
        return o instanceof Long || o instanceof Double || o instanceof Boolean || o instanceof SChar || o instanceof SConstant || o instanceof SBigInteger || o instanceof SRational ||
                        o instanceof SComplex || o instanceof String || o instanceof Source || o instanceof SourceSection;
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

    // ------------------------------------------------------------------------------------------
    // Writing

    private static final class Writer {

        private final SchemeContext context;
        private final CompilerState compilerState;

        private final List<Object> shared = new ArrayList<>();
        private final Map<Object, Integer> sharedIds = new IdentityHashMap<>();

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

        PreludeImage write(List<Ast.Lambda> libraryBodies) throws IOException {
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
                for (Ast.Lambda body : libraryBodies) {
                    new CodeGenerator(b).generate(body);
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
            return new PreludeImage(shared.toArray(), all.toByteArray(), bytecodeBytes.toByteArray(), libraryBodies.size());
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
                return v.elements;
            } else if (o instanceof Syntax s) {
                return s.source == null ? new Object[]{s.open()} : new Object[]{s.open(), s.source};
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
            if (isShared(o)) {
                values.writeByte(SHARED);
                Integer id = sharedIds.get(o);
                if (id == null) {
                    id = shared.size();
                    sharedIds.put(o, id);
                    shared.add(o);
                }
                values.writeInt(id);
            } else if (o instanceof SSymbol s) {
                values.writeByte(SYMBOL);
                writeString(values, s.toString());
            } else if (o instanceof SString s) {
                values.writeByte(STRING);
                writeString(values, s.toString());
                values.writeBoolean(s.isImmutable());
            } else if (o instanceof SPair p) {
                values.writeByte(PAIR);
                values.writeInt(valueIds.get(p.car));
                values.writeInt(valueIds.get(p.cdr));
            } else if (o instanceof SVector v) {
                values.writeByte(VECTOR);
                values.writeInt(v.elements.length);
                for (Object element : v.elements) {
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
            } else {
                throw new IllegalStateException("prelude image: unexpected constant " + o + " (" + o.getClass().getName() + ")");
            }
            valueIds.put(o, valueIds.size());
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
            for (int i = 0; i < ranks.length; i++) {
                ranks[i] = in.readInt();
            }
            Scope[] byRank = new Scope[ranks.length];
            for (int i = 0; i < byRank.length; i++) {
                byRank[i] = new Scope();
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
            // Macros are compiled again, each by an expander for the unit that defined it.
            Map<GlobalEnvironment, Expander> definers = new IdentityHashMap<>();
            macros = new Macro[macroCount];
            SSymbol ellipsis = context.symbols.intern("...");
            SSymbol underscore = context.symbols.intern("_");
            for (int i = 0; i < macroCount; i++) {
                int[] m = macroSpecs[i];
                GlobalEnvironment namespace = namespaces[m[1]];
                Scope scope = scopes[m[2]];
                Expander definer = definers.computeIfAbsent(namespace,
                                ns -> new Expander(context.symbols, ns, compilerState.top, scope, compilerState.libraries, SourceDir.Resources.INSTANCE));
                macros[i] = new Macro(new SyntaxRules(values[m[0]], definer, ellipsis, underscore));
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
                case SHARED:
                    return shared[in.readInt()];
                case SYMBOL:
                    return context.symbols.intern(readString(in));
                case STRING: {
                    String s = readString(in);
                    return in.readBoolean() ? SString.literal(s) : SString.of(s);
                }
                case PAIR:
                    return new SPair(values[in.readInt()], values[in.readInt()]);
                case VECTOR: {
                    Object[] elements = new Object[in.readInt()];
                    for (int i = 0; i < elements.length; i++) {
                        elements[i] = values[in.readInt()];
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
                default:
                    throw new IOException("bad value " + kind);
            }
        }
    }
}
