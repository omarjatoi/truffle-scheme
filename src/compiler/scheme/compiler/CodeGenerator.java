package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.SchemeRootNode;
import scheme.runtime.SchemeRootNodeGen;
import scheme.runtime.value.SConstant;

/**
 * Emits Bytecode DSL builder calls for the core AST: one root per lambda, plus the program root.
 *
 * <ul>
 * <li>Closures are flat: a lambda's free variables are copied into the closure, boxes included
 * (design.md D2). Boxed variables hold a {@code Box} in their local or captured slot.</li>
 * <li>Non-tail calls come from {@link CallSiteEmitter}. Tail calls return a {@code TailCall} to the
 * caller's trampoline; a tail call of the running closure itself, with matching arity, stores the
 * new arguments and loops instead (design.md "Calls and tail calls").</li>
 * </ul>
 *
 * No Java recursion proportional to nesting depth (AGENTS.md rule 4): each AST node expands into
 * tasks on an explicit stack.
 */
final class CodeGenerator {

    private enum Ctx {
        VALUE,
        TAIL
    }

    private static final class State {
        final Lambda lambda;
        final Map<Var, BytecodeLocal> locals = new HashMap<>();
        BytecodeLabel loop;
        /** The Source the open source sections belong to (design.md D13), or null. */
        Source source;

        State(Lambda lambda) {
            this.lambda = lambda;
        }
    }

    private static final class RootHolder {
        SchemeRootNode root;
    }

    private final SchemeRootNodeGen.Builder b;
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private final Deque<State> states = new ArrayDeque<>();
    /** The expressions in statement position: body sequence elements and root bodies. */
    private final Set<Expr> statements = Collections.newSetFromMap(new IdentityHashMap<>());

    CodeGenerator(SchemeRootNodeGen.Builder b) {
        this.b = b;
    }

    /** Emits the program and every lambda in it; returns the program root. */
    SchemeRootNode generate(Lambda program) {
        RootHolder holder = new RootHolder();
        tasks.push(() -> pushRoot(program, holder));
        while (!tasks.isEmpty()) {
            tasks.pop().run();
        }
        return holder.root;
    }

    private void push(List<Runnable> sequence) {
        for (int i = sequence.size() - 1; i >= 0; i--) {
            tasks.push(sequence.get(i));
        }
    }

    /**
     * The task that emits {@code e}: its operations, wrapped in its source section when it has
     * one (design.md D13), so every bytecode maps back to the form it came from. A form from
     * another file (include, a library) opens that file's Source first.
     */
    private Runnable gen(Expr e, Ctx ctx) {
        return () -> {
            Class<?>[] tags = tags(e, ctx);
            SourceSection section = usable(e.source);
            if (section == null && tags.length == 0) {
                push(expand(e, ctx));
                return;
            }
            State st = state();
            Source outer = st.source;
            Source source = section == null ? null : section.getSource();
            List<Runnable> seq = new ArrayList<>();
            seq.add(() -> {
                if (section != null) {
                    if (source != outer) {
                        b.beginSource(source);
                        st.source = source;
                    }
                    b.beginSourceSection(section.getCharIndex(), section.getCharLength());
                }
                if (tags.length > 0) {
                    b.beginTag(tags);
                }
            });
            seq.addAll(expand(e, ctx));
            seq.add(() -> {
                if (tags.length > 0) {
                    b.endTag(tags);
                }
                if (section != null) {
                    b.endSourceSection();
                    if (source != outer) {
                        b.endSource();
                        st.source = outer;
                    }
                }
            });
            push(seq);
        };
    }

    /**
     * Truffle's standard instrumentation tags for an expression (design.md D13): every call is a
     * call; each expression of a body sequence and each root's body is a statement; an expression
     * in value position is an expression (in tail position its operation is a void return). Tags
     * cost nothing until a tool attaches.
     */
    private Class<?>[] tags(Expr e, Ctx ctx) {
        List<Class<?>> tags = new ArrayList<>(3);
        if (statements.contains(e)) {
            tags.add(StandardTags.StatementTag.class);
        }
        if (e instanceof Ast.Call) {
            tags.add(StandardTags.CallTag.class);
        }
        if (ctx == Ctx.VALUE && !(e instanceof Lambda fn && fn.program)) {
            tags.add(StandardTags.ExpressionTag.class);
        }
        return tags.toArray(new Class<?>[0]);
    }

    private State state() {
        return states.peek();
    }

    /**
     * A source section the builder can attach to code of the current root, or null.
     *
     * <p>A root built inside another (a lambda's, inside the root that makes its closure) can only
     * have sections in one Source: the Truffle 25.5 builder looks for a section's Source in every
     * enclosing root being built, and the outermost one with a Source open wins
     * ({@code beginSourceSectionPrefix} keeps searching after it finds one). A section in another
     * file (from a macro template, say) would point into the wrong Source, possibly past its end,
     * so it is left out, and the code it covers reports the enclosing section.
     */
    private SourceSection usable(SourceSection section) {
        if (section == null || !section.isAvailable()) {
            return null;
        }
        Iterator<State> outermostFirst = states.descendingIterator();
        while (outermostFirst.hasNext()) {
            State s = outermostFirst.next();
            if (s == states.peek()) {
                break;
            }
            if (s.source != null) {
                return s.source.equals(section.getSource()) ? section : null;
            }
        }
        return section;
    }

    // ------------------------------------------------------------------------------------------
    // Roots

    private void pushRoot(Lambda fn, RootHolder holder) {
        boolean loops = !fn.program && fn.rest == null;
        List<Runnable> seq = new ArrayList<>();
        SourceSection[] sections = new SourceSection[1];
        seq.add(() -> {
            b.beginRoot();
            State st = new State(fn);
            states.push(st);
            SourceSection section = usable(fn.source);
            sections[0] = section;
            if (section != null) {
                b.beginSource(section.getSource());
                b.beginSourceSection(section.getCharIndex(), section.getCharLength());
                st.source = section.getSource();
            }
            // The parameters' locals are the root's; a let's or letrec*'s are its block's.
            for (Var v : fn.params) {
                createLocal(st, v);
            }
            if (fn.rest != null) {
                createLocal(st, fn.rest);
            }
            if (!fn.program) {
                int n = fn.params.size();
                b.emitCheckArity(n, fn.rest == null ? n : -1);
                for (int i = 0; i < n; i++) {
                    Var v = fn.params.get(i);
                    int argument = i + 1;
                    store(v, () -> b.emitLoadArgument(argument));
                }
                if (fn.rest != null) {
                    store(fn.rest, () -> b.emitRestList(n + 1));
                }
            }
            if (loops) {
                b.beginWhile();
                b.emitLoadConstant(true);
                b.beginBlock();
                st.loop = b.createLabel();
            }
            if (fn.program) {
                b.beginReturn();
            }
        });
        if (!(fn.body instanceof Ast.Seq)) {
            statements.add(fn.body);
        }
        seq.add(gen(fn.body, fn.program ? Ctx.VALUE : Ctx.TAIL));
        seq.add(() -> {
            State st = states.pop();
            if (fn.program) {
                b.endReturn();
            }
            if (loops) {
                b.emitLabel(st.loop);
                b.endBlock();
                b.endWhile();
            }
            if (sections[0] != null) {
                b.endSourceSection();
                b.endSource();
            }
            SchemeRootNode root = b.endRoot();
            root.setName(fn.program ? "<program>" : fn.name);
            root.setVariableNames(capturedNames(fn), fn.self != null && fn.self.visible() ? fn.self.name.toString() : null);
            holder.root = root;
        });
        push(seq);
    }

    /**
     * A local for a variable owned by the current root, scoped to the block being built (design.md
     * D2). Named if tools show it ({@link Var#visible}), so that the debugger's scope has it; a
     * temporary or a macro's own variable stays unnamed (plan.md M6-05).
     */
    private void createLocal(State st, Var v) {
        st.locals.put(v, b.createLocal(v.visible() ? v.name.toString() : null, null));
    }

    /** The names of the lambda's captured variables by slot, null for one tools don't show; or null. */
    private static String[] capturedNames(Lambda fn) {
        String[] names = new String[fn.free.size()];
        boolean any = false;
        for (Map.Entry<Var, Integer> e : fn.free.entrySet()) {
            if (e.getKey().visible()) {
                names[e.getValue()] = e.getKey().name.toString();
                any = true;
            }
        }
        return any ? names : null;
    }

    /**
     * Names the lambdas that the value of {@code e}, bound to a variable of this name, can be
     * (plan.md M6-05): {@code e} itself, or the value of a let's, letrec*'s or sequence's body, or of
     * either branch of an if. A lambda the expander already named (a definition's) keeps its name.
     */
    private static void nameValue(Expr e, String name) {
        Deque<Expr> todo = new ArrayDeque<>();
        todo.push(e);
        while (!todo.isEmpty()) {
            Expr x = todo.pop();
            if (x instanceof Lambda fn) {
                if (fn.name == null && !fn.program) {
                    fn.name = name;
                }
            } else if (x instanceof Ast.Let l) {
                todo.push(l.body);
            } else if (x instanceof Ast.Letrec l) {
                todo.push(l.body);
            } else if (x instanceof Ast.Seq s && !s.body.isEmpty()) {
                todo.push(s.body.get(s.body.size() - 1));
            } else if (x instanceof Ast.If i) {
                todo.push(i.consequent);
                todo.push(i.alternative);
            }
        }
    }

    private static void nameValue(Expr e, Var v) {
        if (v.visible()) {
            nameValue(e, v.name.toString());
        }
    }

    /**
     * Initializes a variable owned by the current lambda: in a fresh box if it is boxed, unless its
     * value is a box already (a debugger frame's variable, {@link Var#preboxed}).
     */
    private void store(Var v, Runnable value) {
        b.beginStoreLocal(state().locals.get(v));
        if (v.boxed() && !v.preboxed) {
            b.beginMakeBox();
            value.run();
            b.endMakeBox();
        } else {
            value.run();
        }
        b.endStoreLocal();
    }

    // ------------------------------------------------------------------------------------------
    // Variables

    /** The variable's slot contents: the value, or its Box if boxed. */
    private void loadRaw(Var v) {
        State st = state();
        if (v.owner == st.lambda) {
            b.emitLoadLocal(st.locals.get(v));
        } else if (v.self == st.lambda) {
            // A fixed letrec* variable in its own lambda's root is the running closure (Closures).
            b.emitLoadArgument(0);
        } else {
            b.beginLoadCaptured(st.lambda.free.get(v));
            b.emitLoadArgument(0);
            b.endLoadCaptured();
        }
    }

    private void loadValue(Var v) {
        if (v.boxed()) {
            b.beginBoxRef();
            loadRaw(v);
            b.endBoxRef();
        } else {
            loadRaw(v);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Expressions

    private List<Runnable> leaf(Runnable emit, Ctx ctx) {
        return ctx == Ctx.VALUE ? List.of(emit) : List.of(b::beginReturn, emit, b::endReturn);
    }

    /** A value expression, returned if in tail position. */
    private List<Runnable> valued(List<Runnable> seq, Ctx ctx) {
        if (ctx == Ctx.VALUE) {
            return seq;
        }
        List<Runnable> wrapped = new ArrayList<>();
        wrapped.add(b::beginReturn);
        wrapped.addAll(seq);
        wrapped.add(b::endReturn);
        return wrapped;
    }

    private List<Runnable> expand(Expr e, Ctx ctx) {
        if (e instanceof Ast.Const c) {
            return leaf(() -> b.emitLoadConstant(c.value), ctx);
        } else if (e instanceof Ast.LocalRef r) {
            return leaf(() -> loadValue(r.var), ctx);
        } else if (e instanceof Ast.GlobalRef g) {
            return leaf(() -> b.emitGlobalRef(g.cell), ctx);
        } else if (e instanceof Ast.LocalSet s) {
            nameValue(s.value, s.var);
            return valued(List.of(() -> {
                b.beginBlock();
                b.beginBoxSet();
                loadRaw(s.var);
            }, gen(s.value, Ctx.VALUE), () -> {
                b.endBoxSet();
                b.emitLoadConstant(SConstant.UNSPECIFIED);
                b.endBlock();
            }), ctx);
        } else if (e instanceof Ast.GlobalSet s) {
            nameValue(s.value, s.cell.name.toString());
            return valued(List.of(() -> {
                b.beginBlock();
                if (s.define) {
                    b.beginGlobalDefine(s.cell);
                } else {
                    b.beginGlobalSet(s.cell);
                }
            }, gen(s.value, Ctx.VALUE), () -> {
                if (s.define) {
                    b.endGlobalDefine();
                } else {
                    b.endGlobalSet();
                }
                b.emitLoadConstant(SConstant.UNSPECIFIED);
                b.endBlock();
            }), ctx);
        } else if (e instanceof Ast.If i) {
            if (ctx == Ctx.VALUE) {
                return List.of(() -> {
                    b.beginConditional();
                    b.beginTruthy();
                }, gen(i.test, Ctx.VALUE), b::endTruthy, gen(i.consequent, Ctx.VALUE), gen(i.alternative, Ctx.VALUE), b::endConditional);
            }
            return List.of(() -> {
                b.beginIfThenElse();
                b.beginTruthy();
            }, gen(i.test, Ctx.VALUE), b::endTruthy, gen(i.consequent, Ctx.TAIL), gen(i.alternative, Ctx.TAIL), b::endIfThenElse);
        } else if (e instanceof Ast.Seq s) {
            List<Runnable> seq = new ArrayList<>();
            seq.add(b::beginBlock);
            for (int k = 0; k < s.body.size(); k++) {
                statements.add(s.body.get(k));
                seq.add(gen(s.body.get(k), k == s.body.size() - 1 ? ctx : Ctx.VALUE));
            }
            seq.add(b::endBlock);
            return seq;
        } else if (e instanceof Ast.Let l) {
            return let(l, ctx);
        } else if (e instanceof Ast.Letrec l) {
            List<Runnable> seq = new ArrayList<>();
            seq.add(() -> {
                b.beginBlock();
                for (Var v : l.vars) {
                    createLocal(state(), v);
                    store(v, () -> b.emitLoadConstant(SConstant.UNSPECIFIED));
                }
            });
            for (int k = 0; k < l.vars.size(); k++) {
                Var v = l.vars.get(k);
                nameValue(l.inits.get(k), v);
                if (v.boxed()) {
                    seq.add(() -> {
                        b.beginBoxSet();
                        loadRaw(v);
                    });
                    seq.add(gen(l.inits.get(k), Ctx.VALUE));
                    seq.add(b::endBoxSet);
                } else {
                    seq.add(() -> b.beginStoreLocal(state().locals.get(v)));
                    seq.add(gen(l.inits.get(k), Ctx.VALUE));
                    seq.add(b::endStoreLocal);
                }
                if (v.fixed) {
                    int j = k;
                    seq.add(() -> patch(l, j));
                }
            }
            seq.add(gen(l.body, ctx));
            seq.add(b::endBlock);
            return seq;
        } else if (e instanceof Lambda fn) {
            RootHolder holder = new RootHolder();
            return valued(List.of(() -> pushRoot(fn, holder), () -> {
                b.beginMakeClosure(holder.root);
                for (Var v : fn.free.keySet()) {
                    loadRaw(v);
                }
                b.endMakeClosure();
            }), ctx);
        } else if (e instanceof Ast.Call c) {
            return call(c, ctx);
        }
        throw new IllegalStateException("unknown AST node " + e);
    }

    /**
     * A fixed letrec* variable has just been initialized (Closures): its value goes into the
     * closures of the lambda inits before it that captured it uninitialized.
     */
    private void patch(Ast.Letrec l, int j) {
        Var v = l.vars.get(j);
        for (int k = 0; k < j; k++) {
            if (l.inits.get(k) instanceof Lambda fn && fn.free.containsKey(v)) {
                b.beginPatchCaptured(fn.free.get(v));
                loadValue(l.vars.get(k));
                loadValue(v);
                b.endPatchCaptured();
            }
        }
    }

    /**
     * A let (M5-05): the inits go into the variables' locals, then the body runs, in this root. A
     * boxed variable gets its box once every init has returned: an init's continuation invoked
     * again binds a fresh one, as a call of the lambda would.
     */
    private List<Runnable> let(Ast.Let l, Ctx ctx) {
        int n = l.vars.size();
        List<Runnable> seq = new ArrayList<>();
        seq.add(b::beginBlock);
        for (int k = 0; k < n; k++) {
            Var v = l.vars.get(k);
            nameValue(l.inits.get(k), v);
            boolean boxNow = v.boxed() && k == n - 1;
            seq.add(() -> {
                createLocal(state(), v);
                b.beginStoreLocal(state().locals.get(v));
                if (boxNow) {
                    b.beginMakeBox();
                }
            });
            seq.add(gen(l.inits.get(k), Ctx.VALUE));
            seq.add(() -> {
                if (boxNow) {
                    b.endMakeBox();
                }
                b.endStoreLocal();
            });
        }
        seq.add(() -> {
            for (int k = 0; k < n - 1; k++) {
                Var v = l.vars.get(k);
                if (v.boxed()) {
                    BytecodeLocal local = state().locals.get(v);
                    b.beginStoreLocal(local);
                    b.beginMakeBox();
                    b.emitLoadLocal(local);
                    b.endMakeBox();
                    b.endStoreLocal();
                }
            }
        });
        seq.add(gen(l.body, ctx));
        seq.add(b::endBlock);
        return seq;
    }

    private List<Runnable> call(Ast.Call c, Ctx ctx) {
        List<Runnable> arguments = new ArrayList<>();
        for (Expr a : c.arguments) {
            arguments.add(gen(a, Ctx.VALUE));
        }
        if (ctx == Ctx.VALUE || state().lambda.program) {
            return valued(CallSiteEmitter.nonTailCall(b, gen(c.procedure, Ctx.VALUE), arguments), ctx);
        }
        State st = state();
        if (st.loop == null || c.arguments.size() != st.lambda.params.size()) {
            List<Runnable> seq = new ArrayList<>();
            seq.add(() -> {
                b.beginReturn();
                b.beginMakeTailCall();
            });
            seq.add(gen(c.procedure, Ctx.VALUE));
            seq.addAll(arguments);
            seq.add(() -> {
                b.endMakeTailCall();
                b.endReturn();
            });
            return seq;
        }
        int n = c.arguments.size();
        if (c.procedure instanceof Ast.LocalRef r && r.var.self == st.lambda) {
            // A self tail call by a fixed letrec* variable's name (Closures): it loops.
            return selfLoop(st, arguments);
        }
        // Possibly a self tail call: evaluate into temporaries, then loop if the procedure is the
        // running closure, else return a TailCall.
        BytecodeLocal[] temps = new BytecodeLocal[n + 1];
        List<Runnable> seq = new ArrayList<>();
        seq.add(() -> {
            b.beginBlock();
            for (int k = 0; k <= n; k++) {
                temps[k] = b.createLocal();
            }
            b.beginStoreLocal(temps[0]);
        });
        seq.add(gen(c.procedure, Ctx.VALUE));
        seq.add(b::endStoreLocal);
        for (int k = 0; k < n; k++) {
            int index = k + 1;
            seq.add(() -> b.beginStoreLocal(temps[index]));
            seq.add(arguments.get(k));
            seq.add(b::endStoreLocal);
        }
        seq.add(() -> {
            b.beginIfThenElse();
            b.beginIsSame();
            b.emitLoadLocal(temps[0]);
            b.emitLoadArgument(0);
            b.endIsSame();
            b.beginBlock();
            for (int k = 0; k < n; k++) {
                BytecodeLocal temp = temps[k + 1];
                store(st.lambda.params.get(k), () -> b.emitLoadLocal(temp));
            }
            b.emitBranch(st.loop);
            b.endBlock();
            b.beginReturn();
            b.beginMakeTailCall();
            for (BytecodeLocal temp : temps) {
                b.emitLoadLocal(temp);
            }
            b.endMakeTailCall();
            b.endReturn();
            b.endIfThenElse();
            b.endBlock();
        });
        return seq;
    }

    /** A tail call of the running closure: evaluate the arguments into temporaries, then loop. */
    private List<Runnable> selfLoop(State st, List<Runnable> arguments) {
        int n = arguments.size();
        BytecodeLocal[] temps = new BytecodeLocal[n];
        List<Runnable> seq = new ArrayList<>();
        seq.add(() -> {
            b.beginBlock();
            for (int k = 0; k < n; k++) {
                temps[k] = b.createLocal();
            }
        });
        for (int k = 0; k < n; k++) {
            int index = k;
            seq.add(() -> b.beginStoreLocal(temps[index]));
            seq.add(arguments.get(k));
            seq.add(b::endStoreLocal);
        }
        seq.add(() -> {
            for (int k = 0; k < n; k++) {
                BytecodeLocal temp = temps[k];
                store(st.lambda.params.get(k), () -> b.emitLoadLocal(temp));
            }
            b.emitBranch(st.loop);
            b.endBlock();
        });
        return seq;
    }
}
