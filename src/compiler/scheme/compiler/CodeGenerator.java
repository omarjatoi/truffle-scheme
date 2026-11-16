package scheme.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
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
            SourceSection section = e.source;
            if (section == null || !section.isAvailable()) {
                push(expand(e, ctx));
                return;
            }
            State st = state();
            Source outer = st.source;
            Source source = section.getSource();
            List<Runnable> seq = new ArrayList<>();
            seq.add(() -> {
                if (source != outer) {
                    b.beginSource(source);
                    st.source = source;
                }
                b.beginSourceSection(section.getCharIndex(), section.getCharLength());
            });
            seq.addAll(expand(e, ctx));
            seq.add(() -> {
                b.endSourceSection();
                if (source != outer) {
                    b.endSource();
                    st.source = outer;
                }
            });
            push(seq);
        };
    }

    private State state() {
        return states.peek();
    }

    // ------------------------------------------------------------------------------------------
    // Roots

    private void pushRoot(Lambda fn, RootHolder holder) {
        boolean loops = !fn.program && fn.rest == null;
        List<Runnable> seq = new ArrayList<>();
        SourceSection section = fn.source != null && fn.source.isAvailable() ? fn.source : null;
        seq.add(() -> {
            b.beginRoot();
            State st = new State(fn);
            states.push(st);
            if (section != null) {
                b.beginSource(section.getSource());
                b.beginSourceSection(section.getCharIndex(), section.getCharLength());
                st.source = section.getSource();
            }
            for (Var v : fn.owned) {
                st.locals.put(v, b.createLocal());
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
            if (section != null) {
                b.endSourceSection();
                b.endSource();
            }
            SchemeRootNode root = b.endRoot();
            root.setName(fn.program ? "<program>" : fn.name);
            holder.root = root;
        });
        push(seq);
    }

    /** Initializes a variable owned by the current lambda: in a fresh box if it is boxed. */
    private void store(Var v, Runnable value) {
        b.beginStoreLocal(state().locals.get(v));
        if (v.boxed()) {
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
                seq.add(gen(s.body.get(k), k == s.body.size() - 1 ? ctx : Ctx.VALUE));
            }
            seq.add(b::endBlock);
            return seq;
        } else if (e instanceof Ast.Letrec l) {
            List<Runnable> seq = new ArrayList<>();
            seq.add(() -> {
                b.beginBlock();
                for (Var v : l.vars) {
                    store(v, () -> b.emitLoadConstant(SConstant.UNSPECIFIED));
                }
            });
            for (int k = 0; k < l.vars.size(); k++) {
                Var v = l.vars.get(k);
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
        // Possibly a self tail call: evaluate into temporaries, then loop if the procedure is the
        // running closure, else return a TailCall.
        int n = c.arguments.size();
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
}
