package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import scheme.compiler.Ast.Expr;
import scheme.compiler.Ast.Lambda;
import scheme.compiler.Ast.Var;
import scheme.runtime.GlobalCell;
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
        /** Where a self tail call jumps to, or null if the root has none (see {@link #selfTailCalls}). */
        BytecodeLabel loop;
        /** Where a call of each contified loop's variable jumps to (Closures, plan.md M7-02). */
        final Map<Lambda, BytecodeLabel> loops = new HashMap<>();
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

    /** What every root generated here records as its top level ({@link SchemeRootNode#setTopLevel}), or null. */
    private final Object topLevel;

    CodeGenerator(SchemeRootNodeGen.Builder b, Object topLevel) {
        this.b = b;
        this.topLevel = topLevel;
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
        return gen(e, ctx, List.of());
    }

    /**
     * {@link #gen(Expr, Ctx)} for an expression in tail position of the lets (and letrec*s, and
     * contified loops, and the root's body) that bind {@code dying}, in this root: a non-tail call
     * there, or the entry of a contified loop, replaces their frames (R7RS §3.5), so it clears
     * their variables first (design.md "Tail calls keep nothing alive"). A tail call of the root
     * returns, and its frame goes with it; a jump back to a loop's start leaves their blocks, whose
     * locals the Bytecode DSL clears.
     */
    private Runnable gen(Expr e, Ctx ctx, List<Var> dying) {
        return inSection(e, () -> tags(e, ctx), () -> expand(e, ctx, dying));
    }

    /**
     * The task that emits {@code expansion}'s tasks for {@code e}, wrapped in {@code e}'s source
     * section and in {@code tags}: both are taken when the task runs, as {@link #statements} is
     * filled in by the enclosing expressions' expansions.
     */
    private Runnable inSection(Expr e, Supplier<Class<?>[]> tagsOf, Supplier<List<Runnable>> expansion) {
        return () -> {
            Class<?>[] tags = tagsOf.get();
            SourceSection section = usable(e.source);
            if (section == null && tags.length == 0) {
                push(expansion.get());
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
            seq.addAll(expansion.get());
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
     *
     * <p>A Truffle 25.5 bug, reproduced by {@code scheme.test.upstream.UpstreamNestedRootSourceTest}
     * (docs/upstream.md#nested-root-source-sections). When that test fails after a Truffle upgrade,
     * the bug is fixed and this method can go.
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
        // The While a self tail call loops in costs two instructions per call: only when it can.
        boolean loops = !fn.program && fn.rest == null && selfTailCalls(fn);
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
                if (fn.rest == null && n > 0 && n < Integer.SIZE) {
                    // One instruction checks the arity and binds every parameter (plan.md M8-04).
                    BytecodeLocal[] parameters = new BytecodeLocal[n];
                    int boxed = 0;
                    for (int i = 0; i < n; i++) {
                        Var v = fn.params.get(i);
                        parameters[i] = st.locals.get(v);
                        if (v.boxed() && !v.preboxed) {
                            boxed |= 1 << i;
                        }
                    }
                    b.emitBindArguments(parameters, boxed);
                } else {
                    b.emitCheckArity(n, fn.rest == null ? n : -1);
                    for (int i = 0; i < n; i++) {
                        Var v = fn.params.get(i);
                        int argument = i + 1;
                        store(v, () -> b.emitLoadArgument(argument));
                    }
                    if (fn.rest != null) {
                        store(fn.rest, () -> b.emitRestList(n + 1));
                    }
                    if (n > 0 || fn.rest != null) {
                        b.emitReleaseArguments();
                    }
                }
            }
            if (fn.program) {
                b.beginReturn();
            }
        });
        if (!(fn.body instanceof Ast.Seq)) {
            statements.add(fn.body);
        }
        if (fn.program) {
            seq.add(gen(fn.body, Ctx.VALUE));
        } else {
            // The parameters die at a loop the body enters in tail position, if it doesn't use them.
            List<Var> dying = fn.rest == null ? fn.params : plus(fn.params, List.of(fn.rest));
            if (loops) {
                seq.addAll(loop(fn.body, argumentCount(fn), c -> mayLoop(fn, c), label -> state().loop = label, () -> state().loop, dying));
            } else {
                seq.add(gen(fn.body, Ctx.TAIL, dying));
            }
        }
        seq.add(() -> {
            states.pop();
            if (fn.program) {
                b.endReturn();
            }
            if (sections[0] != null) {
                b.endSourceSection();
                b.endSource();
            }
            SchemeRootNode root = b.endRoot();
            root.setName(fn.program ? "<program>" : fn.name);
            root.setVariableNames(capturedNames(fn), fn.self != null && fn.self.visible() ? fn.self.name.toString() : null);
            root.setTopLevel(topLevel);
            holder.root = root;
        });
        push(seq);
    }

    /** The length of a call's argument array for {@code fn}'s root, or -1 if it varies. */
    private static int argumentCount(Lambda fn) {
        return fn.program || fn.rest != null ? -1 : fn.params.size() + 1;
    }

    /** The variables {@code e} refers to or assigns, in lambdas in it too. */
    private static Set<Var> referenced(Expr e) {
        Set<Var> vars = new HashSet<>();
        Deque<Expr> todo = new ArrayDeque<>();
        todo.push(e);
        while (!todo.isEmpty()) {
            Expr x = todo.pop();
            if (x instanceof Ast.LocalRef r) {
                vars.add(r.var);
            } else if (x instanceof Ast.LocalSet s) {
                vars.add(s.var);
            }
            Closures.pushChildren(x, todo);
        }
        return vars;
    }

    /** {@code vars} and then {@code more}. */
    private static List<Var> plus(List<Var> vars, List<Var> more) {
        if (vars.isEmpty()) {
            return more;
        }
        List<Var> all = new ArrayList<>(vars);
        all.addAll(more);
        return all;
    }

    /**
     * Whether {@code fn}'s body has a tail call that {@link #call} may turn into a jump back to the
     * start of the root: one with as many arguments as {@code fn} has parameters, other than a call
     * of a contified loop (which jumps to that loop's start).
     */
    private static boolean selfTailCalls(Lambda fn) {
        return tailCall(fn.body, c -> mayLoop(fn, c));
    }

    /** Whether {@code c}, a tail call in {@code fn}'s body, may be a self tail call ({@link #selfTailCalls}). */
    private static boolean mayLoop(Lambda fn, Ast.Call c) {
        return !(c.procedure instanceof Ast.LocalRef r && r.var.self != null && r.var.self.contified) &&
                        c.arguments.size() == fn.params.size() && !Intrinsic.isIntrinsic(c) && !ConstantMember.isConstantMember(c);
    }

    /**
     * Whether {@code e} has a call in tail position (of {@code e}, or of a contified loop entered in
     * tail position) that {@code p} accepts.
     */
    private static boolean tailCall(Expr e, Predicate<Ast.Call> p) {
        Deque<Expr> tail = new ArrayDeque<>();
        tail.push(e);
        while (!tail.isEmpty()) {
            Expr x = tail.pop();
            if (x instanceof Ast.If i) {
                tail.push(i.consequent);
                tail.push(i.alternative);
            } else if (x instanceof Ast.Seq s && !s.body.isEmpty()) {
                tail.push(s.body.get(s.body.size() - 1));
            } else if (x instanceof Ast.Let l) {
                tail.push(l.body);
            } else if (x instanceof Ast.Letrec l) {
                if (contified(l) instanceof Lambda loop) {
                    tail.push(loop.body);
                } else {
                    tail.push(l.body);
                }
            } else if (x instanceof Ast.Call c && p.test(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An {@code if}'s test with the {@code not}s around it taken off, and its branches in the order
     * the test's truth takes them: {@code (if (not x) a b)} branches on {@code x} to {@code b} or
     * {@code a}, one instruction fewer (plan.md M8-04). {@code not} is its primitive's operation
     * ({@link Intrinsic}), which nothing can redefine.
     */
    private record Test(Expr test, Expr whenTrue, Expr whenFalse) {
        static Test of(Ast.If i) {
            Expr test = i.test;
            Expr whenTrue = i.consequent;
            Expr whenFalse = i.alternative;
            while (test instanceof Ast.Call c && Intrinsic.of(c) == Intrinsic.NOT) {
                test = c.arguments.get(0);
                Expr t = whenTrue;
                whenTrue = whenFalse;
                whenFalse = t;
            }
            return new Test(test, whenTrue, whenFalse);
        }
    }

    /**
     * The loop of a root with self tail calls, or of a contified loop, whose body is {@code body},
     * in tail position: a {@code While} whose body jumps to its end ({@code label}, set by
     * {@code setLabel} when the loop's body begins) to loop again; {@code jumps} accepts the tail
     * calls that may jump.
     *
     * <p>When {@code body} is an {@code if} with jumps in one branch only, the loop is rotated (plan.md
     * M8-04): the {@code if}'s test is the {@code While}'s condition ({@code LoopTest}),
     * its body that branch, and the other branch follows the loop. So an iteration, and a call that
     * returns from the other branch at once, branches once, where {@code LoopHead}'s condition and the
     * {@code if} branched twice. The {@code if}'s tags (a statement's) go around the test: a
     * breakpoint on it is hit once per iteration, as before.
     */
    private List<Runnable> loop(Expr body, int argumentCount, Predicate<Ast.Call> jumps, Consumer<BytecodeLabel> setLabel, Supplier<BytecodeLabel> label,
                    List<Var> dying) {
        if (body instanceof Ast.If i) {
            Test t = Test.of(i);
            boolean whenTrue = tailCall(t.whenTrue, jumps);
            if (whenTrue != tailCall(t.whenFalse, jumps)) {
                Expr looping = whenTrue ? t.whenTrue : t.whenFalse;
                Expr exit = whenTrue ? t.whenFalse : t.whenTrue;
                Class<?>[][] tags = new Class<?>[1][];
                return List.of(inSection(i, () -> new Class<?>[0], () -> List.of(() -> {
                    b.beginWhile();
                    b.beginLoopTest(argumentCount, whenTrue);
                    tags[0] = tags(i, Ctx.TAIL);
                    if (tags[0].length > 0) {
                        b.beginTag(tags[0]);
                    }
                }, gen(t.test, Ctx.VALUE), () -> {
                    if (tags[0].length > 0) {
                        b.endTag(tags[0]);
                    }
                    b.endLoopTest();
                    b.beginBlock();
                    setLabel.accept(b.createLabel());
                }, gen(looping, Ctx.TAIL, dying), () -> {
                    b.emitLabel(label.get());
                    b.endBlock();
                    b.endWhile();
                }, gen(exit, Ctx.TAIL, dying))));
            }
        }
        return List.of(() -> {
            b.beginWhile();
            b.emitLoopHead(argumentCount);
            b.beginBlock();
            setLabel.accept(b.createLabel());
        }, gen(body, Ctx.TAIL, dying), () -> {
            b.emitLabel(label.get());
            b.endBlock();
            b.endWhile();
        });
    }

    /** The loop {@code l} binds if it is a contified loop's letrec* (Closures), else null. */
    private static Lambda contified(Ast.Letrec l) {
        return l.vars.size() == 1 && l.inits.get(0) instanceof Lambda fn && fn.contified ? fn : null;
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

    private List<Runnable> expand(Expr e, Ctx ctx, List<Var> dying) {
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
            Test t = Test.of(i);
            // A predicate's operation gives the branch its boolean as it is (plan.md M7-03).
            boolean truthy = !Intrinsic.isPredicate(t.test);
            Runnable beginTest = truthy ? b::beginTruthy : () -> {
            };
            Runnable endTest = truthy ? b::endTruthy : () -> {
            };
            if (ctx == Ctx.VALUE) {
                return List.of(() -> {
                    b.beginConditional();
                    beginTest.run();
                }, gen(t.test, Ctx.VALUE), endTest, gen(t.whenTrue, Ctx.VALUE, dying), gen(t.whenFalse, Ctx.VALUE, dying), b::endConditional);
            }
            return List.of(() -> {
                b.beginIfThenElse();
                beginTest.run();
            }, gen(t.test, Ctx.VALUE), endTest, gen(t.whenTrue, Ctx.TAIL, dying), gen(t.whenFalse, Ctx.TAIL, dying), b::endIfThenElse);
        } else if (e instanceof Ast.Seq s) {
            List<Runnable> seq = new ArrayList<>();
            seq.add(b::beginBlock);
            for (int k = 0; k < s.body.size(); k++) {
                statements.add(s.body.get(k));
                boolean last = k == s.body.size() - 1;
                seq.add(gen(s.body.get(k), last ? ctx : Ctx.VALUE, last ? dying : List.of()));
            }
            seq.add(b::endBlock);
            return seq;
        } else if (e instanceof Ast.Let l) {
            return let(l, ctx, dying);
        } else if (e instanceof Ast.Letrec l && contified(l) instanceof Lambda loop) {
            return contifiedLoop(loop, (Ast.Call) l.body, ctx, dying);
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
            seq.add(gen(l.body, ctx, plus(dying, l.vars)));
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
            return call(c, ctx, dying);
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
    private List<Runnable> let(Ast.Let l, Ctx ctx, List<Var> dying) {
        List<Runnable> seq = new ArrayList<>();
        seq.add(b::beginBlock);
        bind(l.vars, l.inits, seq);
        seq.add(gen(l.body, ctx, plus(dying, l.vars)));
        seq.add(b::endBlock);
        return seq;
    }

    /** Adds the tasks that bind {@code vars} to the values of {@code inits}, as a let does. */
    private void bind(List<Var> vars, List<Expr> inits, List<Runnable> seq) {
        int n = vars.size();
        for (int k = 0; k < n; k++) {
            Var v = vars.get(k);
            nameValue(inits.get(k), v);
            boolean boxNow = v.boxed() && k == n - 1;
            seq.add(() -> {
                createLocal(state(), v);
                b.beginStoreLocal(state().locals.get(v));
                if (boxNow) {
                    b.beginMakeBox();
                }
            });
            seq.add(gen(inits.get(k), Ctx.VALUE));
            seq.add(() -> {
                if (boxNow) {
                    b.endMakeBox();
                }
                b.endStoreLocal();
            });
        }
        seq.add(() -> {
            for (int k = 0; k < n - 1; k++) {
                Var v = vars.get(k);
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
    }

    /**
     * A contified loop (Closures, plan.md M7-02), entered in tail position by {@code entry}: its
     * parameters are bound as a let's variables are, then its body runs in a {@code While} of this
     * root, in tail position. A call of the loop's variable stores the new arguments in the
     * parameters (a boxed one in a fresh box, as a call would) and jumps back ({@link #call}).
     * Entering it is a tail call of the lets around it, and of the root's body ({@code dying}):
     * their variables that its body doesn't use are cleared once the parameters are bound. They
     * would stay set while it loops: it runs in their blocks. In its body, its parameters die at the
     * entry of another loop.
     */
    private List<Runnable> contifiedLoop(Lambda loop, Ast.Call entry, Ctx ctx, List<Var> dying) {
        if (ctx != Ctx.TAIL) {
            throw new IllegalStateException("a contified loop not in tail position");
        }
        List<Runnable> seq = new ArrayList<>();
        seq.add(b::beginBlock);
        bind(loop.params, entry.arguments, seq);
        if (!(loop.body instanceof Ast.Seq)) {
            statements.add(loop.body);
        }
        seq.add(() -> {
            if (!dying.isEmpty()) {
                // The loop's body may use a variable from outside it: that one stays.
                Set<Var> used = referenced(loop.body);
                for (Var v : dying) {
                    if (!used.contains(v)) {
                        b.emitClearLocal(state().locals.get(v));
                    }
                }
            }
        });
        seq.add(() -> push(loop(loop.body, argumentCount(state().lambda), c -> c.procedure instanceof Ast.LocalRef r && r.var.self == loop,
                        label -> state().loops.put(loop, label), () -> state().loops.get(loop), loop.params)));
        seq.add(b::endBlock);
        return seq;
    }

    private List<Runnable> call(Ast.Call c, Ctx ctx, List<Var> dying) {
        List<ConstantMember.Match> matches = ConstantMember.matches(c);
        if (matches != null) {
            if (ConstantMember.isAlist(c)) {
                // assq or assv: one operation, whose loop the interpreter runs in Java (M9-05).
                Object alist = ((Ast.Const) c.arguments.get(1)).value;
                List<Runnable> seq = new ArrayList<>();
                seq.add(() -> b.beginAssoc(alist));
                seq.add(gen(c.arguments.get(0), Ctx.VALUE));
                seq.add(b::endAssoc);
                return valued(seq, ctx);
            }
            return constantMember(c.arguments.get(0), matches, ctx);
        }
        List<Runnable> arguments = new ArrayList<>();
        for (Expr a : c.arguments) {
            arguments.add(gen(a, Ctx.VALUE));
        }
        Intrinsic intrinsic = Intrinsic.of(c);
        if (intrinsic != null) {
            // An operation of its own (plan.md M7-03): no call, in tail position or not.
            GlobalCell cell = ((Ast.GlobalRef) c.procedure).cell;
            List<Runnable> seq = new ArrayList<>();
            boolean tail = ctx == Ctx.TAIL && !state().lambda.program;
            seq.add(() -> intrinsic.begin(b, cell, tail));
            seq.addAll(arguments);
            seq.add(() -> intrinsic.end(b));
            return valued(seq, ctx);
        }
        if (ctx == Ctx.VALUE || state().lambda.program) {
            Runnable procedure = gen(c.procedure, Ctx.VALUE);
            if (!dying.isEmpty()) {
                // The variables die once the operands are evaluated: the last one clears them.
                if (arguments.isEmpty()) {
                    procedure = thenClear(procedure, dying);
                } else {
                    arguments.set(arguments.size() - 1, thenClear(arguments.get(arguments.size() - 1), dying));
                }
            }
            return valued(CallSiteEmitter.nonTailCall(b, procedure, arguments), ctx);
        }
        State st = state();
        if (c.procedure instanceof Ast.LocalRef r && r.var.self != null && r.var.self.contified) {
            // A call of a contified loop: it jumps back to the loop's start.
            return selfLoop(r.var.self.params, st.loops.get(r.var.self), arguments);
        }
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
            return selfLoop(st.lambda.params, st.loop, arguments);
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

    /** {@code operand}, then the locals of {@code dying} cleared, with the operand's value. */
    private Runnable thenClear(Runnable operand, List<Var> dying) {
        return () -> push(List.of(() -> {
            for (Var v : dying) {
                b.beginThenClear(state().locals.get(v));
            }
        }, operand, () -> {
            for (int k = 0; k < dying.size(); k++) {
                b.endThenClear();
            }
        }));
    }

    /**
     * {@code (memv key 'list)} or {@code (memq key 'list)} ({@link ConstantMember}, plan.md M8-05):
     * the key goes into a temporary, which {@code eq?}'s operation compares with each datum in turn;
     * the value is the tail of the list whose car matched, or #f. No call, in tail position or not.
     */
    private List<Runnable> constantMember(Expr key, List<ConstantMember.Match> matches, Ctx ctx) {
        BytecodeLocal[] temp = new BytecodeLocal[1];
        List<Runnable> seq = new ArrayList<>();
        seq.add(() -> {
            b.beginBlock();
            temp[0] = b.createLocal();
            b.beginStoreLocal(temp[0]);
        });
        seq.add(gen(key, Ctx.VALUE));
        seq.add(() -> {
            b.endStoreLocal();
            for (ConstantMember.Match m : matches) {
                b.beginConditional();
                b.beginEq();
                b.emitLoadLocal(temp[0]);
                b.emitLoadConstant(m.datum());
                b.endEq();
                b.emitLoadConstant(m.value());
            }
            b.emitLoadConstant(false);
            for (int k = 0; k < matches.size(); k++) {
                b.endConditional();
            }
            b.endBlock();
        });
        return valued(seq, ctx);
    }

    /**
     * A tail call of the running closure, or of a contified loop: evaluate the arguments into
     * temporaries, store them in the parameters, and jump to {@code start}.
     */
    private List<Runnable> selfLoop(List<Var> params, BytecodeLabel start, List<Runnable> arguments) {
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
                store(params.get(k), () -> b.emitLoadLocal(temp));
            }
            b.emitBranch(start);
            b.endBlock();
        });
        return seq;
    }
}
