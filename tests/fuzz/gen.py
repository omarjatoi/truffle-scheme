"""Random R7RS-small programs for the differential fuzzer (tests/fuzz/README.md).

A program is a list of top-level forms; a form is a tree: a Python list is a Scheme list, `Vec` and
`Bytes` are vector and bytevector literals, and a str is an atom as it is written. Each program
defines a few globals, records, parameters and macros, then writes the values of typed, random
expressions, a line each.

The generator is type-directed, so that every program means one thing under R7RS: it never does
what R7RS calls an error or leaves unspecified (design.md D9, D18), and so a difference between
two implementations is a bug in one of them. In particular:

- Partial operations are guarded: indices are reduced modulo the length, divisors checked for zero,
  `exact` only sees finite flonums, `expt` small exponents.
- Arguments of a call (and `let` inits, macro arguments) are evaluated in an unspecified order, so
  at most one of them may leave non-locally (a continuation, `raise`); `map`'s, `vector-map`'s and
  `string-map`'s procedures, whose order of application is unspecified, never do. Local state
  (`set!`) is only ever on variables a template made for itself, read where the order is fixed.
- Procedures are never written, nor records, error objects, `eof`, or the values of procedures whose
  result is unspecified (`vector-fill!`, `for-each`, `when` without a value, ...).
- Every procedure the generator makes is total and cheap for any argument of its type: loops bound
  their counts with `modulo`, and big counts appear only in the tail-loop and deep-recursion
  templates, outside loops.
- Flonums only meet the operations whose results IEEE 754 fixes (+, -, *, /, sqrt, rounding,
  conversions); `exp`, `sin`, `expt` of flonums and friends are left out: R7RS doesn't say how
  accurate they are.
- Numbers are real: nothing makes a complex number (no `make-rectangular`, `make-polar` or complex
  literal; `sqrt` only of a non-negative flonum, `expt` of a ratio only to an integer power,
  `string->number` kept only when it reads a real), so chibi-scheme's `-` with a complex operand,
  which crashes after a ratio and negates the complex number's flonums in place (README.md), never
  runs.
"""

import random

IMPORTS = ['import', ['scheme', 'base'], ['scheme', 'case-lambda'], ['scheme', 'char'], ['scheme', 'cxr'],
           ['scheme', 'eval'], ['scheme', 'inexact'], ['scheme', 'lazy'], ['scheme', 'process-context'],
           ['scheme', 'read'], ['scheme', 'write']]

INT, RAT, FLO, BOOL, CHAR, STR, SYM, BV = 'int', 'rat', 'flo', 'bool', 'char', 'str', 'sym', 'bv'
ANY = 'any'
# The heads of the top-level forms that may end a program (Gen.ending).
ENDINGS = ('exit', 'dynamic-wind', 'raise', 'error')


def LIST(t):
    return ('list', t)


def VEC(t):
    return ('vec', t)


def PAIR(a, b):
    return ('pair', a, b)


def FN(args, r):
    return ('fn', tuple(args), r)


def kind(t):
    return t if isinstance(t, str) else t[0]


ATOMIC = [INT, RAT, FLO, BOOL, CHAR, STR, SYM, BV]
# Types eqv? compares by value (not flonums: R7RS leaves (eqv? +nan.0 +nan.0) unspecified).
EQV = (INT, RAT, CHAR, SYM, BOOL)
# Types a quoted datum can have.
LITERAL = (INT, RAT, FLO, BOOL, CHAR, STR, SYM)


class Vec(list):
    pass


class Bytes(list):
    pass


# ---------------------------------------------------------------------------
# Writing trees as text
# ---------------------------------------------------------------------------

def to_text(tree, width=100):
    """The program text of a tree (or a list of top-level forms with `program_text`)."""
    flat = _flat(tree)
    if len(flat) <= width or not isinstance(tree, list) or not tree:
        return flat
    return _pretty(tree, 0, width)


def _open(tree):
    return '#(' if isinstance(tree, Vec) else '#u8(' if isinstance(tree, Bytes) else '('


def _flat(tree):
    if not isinstance(tree, list):
        return tree
    if type(tree) is list and len(tree) == 2 and isinstance(tree[0], str) and tree[0] in _ABBREV:
        return _ABBREV[tree[0]] + _flat(tree[1])
    return _open(tree) + ' '.join(_flat(t) for t in tree) + ')'


_ABBREV = {'quote': "'", 'quasiquote': '`', 'unquote': ',', 'unquote-splicing': ',@'}


def _pretty(tree, indent, width):
    flat = _flat(tree)
    if len(flat) + indent <= width or not isinstance(tree, list) or len(tree) < 2:
        return flat
    if type(tree) is list and len(tree) == 2 and isinstance(tree[0], str) and tree[0] in _ABBREV:
        return _ABBREV[tree[0]] + _pretty(tree[1], indent + len(_ABBREV[tree[0]]), width)
    head = _open(tree)
    first = _flat(tree[0])
    inner = indent + len(head) + (len(first) + 1 if not isinstance(tree[0], list) else 0)
    if isinstance(tree[0], list) or len(first) > 12:
        inner = indent + 2
        lines = [head + _pretty(tree[0], indent + len(head), width)]
        rest = tree[1:]
    else:
        lines = [head + first + ' ' + _pretty(tree[1], inner, width)]
        rest = tree[2:]
    for t in rest:
        lines.append(' ' * inner + _pretty(t, inner, width))
    return '\n'.join(lines) + ')'


def program_text(forms, header=None):
    lines = [header] if header else []
    lines += [to_text(f) for f in forms]
    return '\n'.join(lines) + '\n'


def string_literal(text):
    out = ['"']
    for c in text:
        if c in '"\\':
            out.append('\\' + c)
        elif c == '\n':
            out.append('\\n')
        elif c == '\t':
            out.append('\\t')
        else:
            out.append(c)
    out.append('"')
    return ''.join(out)


# ---------------------------------------------------------------------------
# Literal pools
# ---------------------------------------------------------------------------

# Not 2^62 or -2^62: chibi-scheme, whose fixnums end there, writes (/ -1 (expt 2 62)) as
# -1/-4611686018427387904 and divides them wrong (guarded_div).
_INT_LITS = [0, 1, -1, 2, 3, 7, 10, -13, 42, 100, 255, 256, 1000, 65535, -32768, 2**29 - 1, 2**29, -2**29, 2**30,
             2**31 - 1, 2**31, -2**31, -2**31 - 1, 2**32, 2**53, 2**53 + 1, 2**62 - 1, -2**62 + 1, 2**63 - 1, 2**63,
             -2**63, -2**63 - 1, 2**64, 2**64 + 1, 10**20, -10**25, 3**50]
# Flonum literals chibi-scheme reads as the nearest flonum: its reader is off by an ulp for many
# with an exponent or with leading zeros (1.5e-05, 6.02e23, 0.0055775865389216224), which no
# program should trip over.
# Nor powers of ten past 1e21 or below 1e-4: chibi-scheme's number->string writes 1e+300 (see
# fuzz.py), which a program may take apart as a string.
_FLO_LITS = ['0.0', '-0.0', '1.0', '-1.5', '0.5', '0.1', '0.25', '2.5', '3.14159', '100.0', '1e10', '0.125',
             '123.456', '-7.25', '1.5e21', '9007199254740993.0', '2.5e300', '0.3', '0.0001',
             '+inf.0', '-inf.0', '4503599627370497.5', '0.1e1', '-1234567.875']
_CHAR_LITS = ['#\\a', '#\\z', '#\\A', '#\\Z', '#\\0', '#\\9', '#\\space', '#\\newline', '#\\tab', '#\\(', '#\\)',
              '#\\"', '#\\\\', '#\\;', '#\\#', '#\\x3bb', '#\\é', '#\\ж', '#\\中', '#\\!', '#\\~', '#\\_',
              '#\\x41', '#\\ü', '#\\5', '#\\-']
# Nor a capital sigma: chibi-scheme's string-downcase makes every Σ before a non-letter a
# final sigma, ς ("Σ#" gives "ς#", where Unicode's Final_Sigma needs a cased letter before it).
# Not ".5": chibi-scheme writes (string->symbol ".5") as .5, which reads as a number.
_STR_TEXTS = ['', 'a', 'hello', 'Hello, World', 'λx.x', 'tab\there', 'line\nbreak', 'q"uote', 'back\\slash', '123',
              '-42', '3.5', '1/2', '#x1F', '1e3', 'abc def', 'ÉCOLE', 'Strasse', '  ', 'Zz', 'a;b', '#t', '(1 2)',
              '+inf.0', '-0.0', '#e1.5', '1/0', '1.', '#b101', '-', '12345678901234567890', 'ΑΒΓ']
_SYM_LITS = ['a', 'b', 'foo', 'bar', 'x1', 'hello-world', '+', '-', '<=?', '->x', 'λ', '!$%&*/:<=>?^~',
             '|two words|', 'ABC', 'nil', 'else', 'define', '|a\\|b|', '||', '|1|', 'Ab']
_NAMES = ['x', 'y', 'z', 'tmp', 'n', 'acc', 'lst', 's', 'v', 'i', 'k', 'e', 't']


def _canonical(datum):
    """The datum a case clause's literal denotes, to keep case's data distinct."""
    return {'#true': '#t', '#false': '#f', '#\\x41': '#\\A', '#\\x3bb': '#\\λ'}.get(datum, datum)


class Env:
    """What an expression may use: the variables in scope (name, type), the escape continuations
    it may call (name, type), whether a handler catches `raise` (`rais`), the `raise-continuable`
    handlers that return (payload type, result type), and how many times it may run (`weight`)."""

    __slots__ = ('vars', 'esc', 'rais', 'cont', 'weight')

    def __init__(self, vars=(), esc=(), rais=False, cont=(), weight=1):
        self.vars, self.esc, self.rais, self.cont, self.weight = vars, esc, rais, cont, weight

    def _with(self, **kw):
        e = Env(self.vars, self.esc, self.rais, self.cont, self.weight)
        for k, v in kw.items():
            setattr(e, k, v)
        return e

    def bind(self, *pairs):
        names = {n for n, _ in pairs}
        return self._with(vars=tuple(v for v in self.vars if v[0] not in names) + tuple(pairs),
                          esc=tuple(v for v in self.esc if v[0] not in names))

    def calm(self):
        return self._with(esc=(), rais=False, cont=())

    def heavier(self, n):
        return self._with(weight=self.weight * max(n, 1))

    def exits(self):
        return bool(self.esc or self.rais or self.cont)


class Gen:
    """One program's generator; `prefix` names its globals, so programs can share a file."""

    def __init__(self, rng, prefix='', depth=5, cases=8, exits=True):
        self.r = rng
        self.prefix = prefix
        self.depth = depth
        self.ncases = cases
        self.allow_exit = exits
        self.n = 0
        self.globals = []  # (name, type)
        self.macros = []  # (name, arg types, result type, ellipsis?)
        self.params = []  # names of parameters holding an int
        self.records = []  # (type name, ctor, predicate, [(field type, accessor, modifier or None)])
        self.budget = 0

    # -- utilities ---------------------------------------------------------

    def chance(self, p):
        return self.r.random() < p

    def pick(self, seq):
        return self.r.choice(seq)

    def fresh(self, base):
        self.n += 1
        return f'{base}{self.n}'

    def gname(self, base):
        self.n += 1
        return f'{self.prefix}{base}{self.n}'

    def local(self):
        return self.pick(_NAMES) if self.chance(0.4) else self.fresh(self.pick(['x', 'y', 'v', 'w', 'q']))

    def weighted(self, options):
        total = sum(w for w, _ in options)
        x = self.r.random() * total
        for w, f in options:
            x -= w
            if x < 0:
                return f
        return options[-1][1]

    # -- types ---------------------------------------------------------------

    def elem_type(self, d=1):
        t = self.pick(ATOMIC + [INT, INT, STR, SYM])
        if d > 0 and self.chance(0.15):
            return self.pick([LIST, VEC])(self.elem_type(d - 1))
        return t

    def printable(self, d=2):
        """A type whose values `write` prints the same everywhere."""
        x = self.r.random()
        if x < 0.55:
            return self.pick(ATOMIC + [INT, INT, RAT, FLO, STR])
        if x < 0.75:
            return LIST(self.printable(d - 1) if d > 0 else self.pick(ATOMIC))
        if x < 0.88:
            return VEC(self.printable(d - 1) if d > 0 else self.pick(ATOMIC))
        return PAIR(self.printable(d - 1) if d > 0 else INT, self.printable(d - 1) if d > 0 else SYM)

    def fn_type(self):
        n = self.pick([0, 1, 1, 1, 2, 2, 3])
        return FN([self.pick([INT, INT, STR, CHAR, FLO, SYM, LIST(INT)]) for _ in range(n)], self.printable(1))

    def bind_type(self):
        if self.chance(0.12):
            return self.fn_type()
        if self.records and self.chance(0.1):
            return ('rec', self.pick(self.records)[0])
        return self.printable(1)

    # -- entry points -------------------------------------------------------

    def program(self):
        """The top-level forms of one program."""
        forms = []
        for _ in range(self.r.randint(0, 1)):
            forms.append(self.record_def())
        for _ in range(self.r.randint(0, 2)):
            forms.append(self.param_def())
        for _ in range(self.r.randint(0, 2)):
            forms.append(self.global_def())
        for _ in range(self.r.randint(0, 2)):
            forms.append(self.macro_def())
        for _ in range(self.ncases):
            x = self.r.random()
            if x < 0.12:
                forms.append(self.global_def())
            elif x < 0.16 and self.globals:
                forms.append(self.global_set())
            elif x < 0.19:
                forms.append(self.macro_def())
            forms += self.case()
        if self.allow_exit and self.chance(0.08):
            forms.append(self.ending())
        return forms

    def case(self):
        self.budget = self.r.randint(15, 70)
        t = self.printable()
        e = self.expr(t, self.r.randint(2, self.depth), Env())
        if self.chance(0.08) and kind(t) in (STR, CHAR):
            return [['display', e], ['newline']]
        if self.chance(0.05):
            return [['write', e, ['current-output-port']], ['newline']]
        return [['write', e], ['newline']]

    def ending(self):  # its head is one of ENDINGS
        """The program's last form: an exit or an uncaught raise."""
        x = self.r.random()
        if x < 0.3:
            return ['exit', self.pick(['0', '1', '3', '42', '#t', '#f', '255'])]
        if x < 0.55:
            # R7RS 6.14: exit runs the outstanding dynamic-wind afters.
            return ['dynamic-wind', ['lambda', [], ['display', '"before"'], ['newline']],
                    ['lambda', [], ['exit', self.pick(['0', '7', '#f'])]],
                    ['lambda', [], ['display', '"after"'], ['newline']]]
        if x < 0.8:
            return ['raise', self.pick(["'boom", '"oops"', '42'])]
        return ['error', '"fuzz error"', self.leaf(INT, Env())]

    # -- top-level definitions ------------------------------------------------

    def global_def(self):
        self.budget = self.r.randint(10, 40)
        if self.chance(0.4):
            return self.function_def()
        t = self.bind_type()
        name = self.gname('g')
        e = self.expr(t, self.r.randint(1, 4), Env())
        self.globals.append((name, t))
        return ['define', name, e]

    def function_def(self):
        name = self.gname('f')
        x = self.r.random()
        if x < 0.3:
            # Tail recursion with a bounded count: (f n acc).
            acc_t = self.printable(1)
            n, a, loop = self.local(), self.fresh('a'), self.fresh('loop')
            if n == a:
                n = self.fresh('n')
            i = self.fresh('i')
            env = Env().bind((i, INT), (a, acc_t)).heavier(16)
            step = self.loop_step(acc_t, 2, env)
            body = ['let', loop, [[i, ['modulo', n, '12']], [a, a]],
                    ['if', ['<=', i, '0'], a, [loop, ['-', i, '1'], step]]]
            self.globals.append((name, FN([INT, acc_t], acc_t)))
            return ['define', [name, n, a], body]
        t = self.fn_type()
        params = []
        for _ in t[1]:
            p = self.local()
            while p in params:
                p = self.fresh('p')
            params.append(p)
        env = Env().bind(*zip(params, t[1])).heavier(16)
        body = self.expr(t[2], self.r.randint(1, 4), env)
        if x < 0.45 and len(params) == 1 and t[1][0] == INT:
            # Non-tail recursion with a bounded depth.
            j = self.fresh('j')
            body = ['let', [[j, ['modulo', params[0], '8']]],
                    ['if', ['<', j, '1'], body,
                     [name, ['-', j, '1']]]]
        self.globals.append((name, t))
        return ['define', [name] + params, body]

    def global_set(self):
        # Not of a procedure: the new one could call itself through the variable, forever.
        values = [(n, t) for n, t in self.globals if kind(t) != 'fn']
        if not values:
            return self.global_def()
        name, t = self.pick(values)
        self.budget = self.r.randint(10, 30)
        return ['set!', name, self.expr(t, self.r.randint(1, 3), Env())]

    def record_def(self):
        name = self.gname('rec')
        fields = []
        for i, ft in enumerate([INT, self.pick([STR, SYM, LIST(INT), FLO])]):
            f = f'fld{i}'
            fields.append((ft, f'{name}-{f}', f'set-{name}-{f}!' if self.chance(0.6) else None, f))
        self.records.append((name, f'make-{name}', f'{name}?', [(ft, acc, mod) for ft, acc, mod, _ in fields]))
        return ['define-record-type', f'<{name}>', [f'make-{name}'] + [f for *_, f in fields], f'{name}?'] + \
            [[f, acc] + ([mod] if mod else []) for _, acc, mod, f in fields]

    def param_def(self):
        name = self.gname('par')
        self.budget = 10
        init = self.leaf(INT, Env())
        self.params.append(name)
        if self.chance(0.4):
            x = self.fresh('x')
            conv = ['lambda', [x], self.pick([['+', x, '1'], ['*', x, '2'], ['-', x], ['abs', x]])]
            return ['define', name, ['make-parameter', init, conv]]
        return ['define', name, ['make-parameter', init]]

    def macro_def(self):
        name = self.gname('m')
        self.budget = self.r.randint(10, 30)
        x = self.r.random()
        if x < 0.4:
            # (m a b): the template binds `tmp` to a, which a use's own `tmp` must not see.
            ta, tb, r = self.pick([INT, STR, LIST(INT), SYM]), self.pick([INT, STR, CHAR]), self.printable(1)
            env = Env().bind(('pa', ta), ('pb', tb), ('tmp', ta))
            tmpl = ['let', [['tmp', 'pa']], self.expr(r, self.r.randint(1, 3), env)]
            self.macros.append((name, (ta, tb), r, False, False))
            return ['define-syntax', name, ['syntax-rules', [], [['_', 'pa', 'pb'], tmpl]]]
        if x < 0.65:
            # (m e ...) => (list (f e) ...)
            a, r = self.pick([INT, STR, CHAR, SYM]), self.printable(0)
            f = self.fn_expr(FN([a], r), 2, Env())
            self.macros.append((name, (a,), LIST(r), True, False))
            return ['define-syntax', name, ['syntax-rules', [], [['_', 'pe', '...'], ['list', [f, 'pe'], '...']]]]
        if x < 0.85:
            # A recursive `or` over booleans-or-values of one type, with the first non-#f.
            t = self.pick([INT, STR, SYM])
            default = self.leaf(t, Env())
            self.macros.append((name, (t,), t, True, False))
            return ['define-syntax', name,
                    ['syntax-rules', [],
                     [['_'], default],
                     [['_', 'pe'], 'pe'],
                     [['_', 'pe', 'pr', '...'], ['let', [['t', 'pe']], ['if', 't', 't', [name, 'pr', '...']]]]]]
        # A literal keyword: (m a => f) applies f, (m a) is a.
        t = self.pick([INT, STR])
        f = self.fn_expr(FN([t], t), 2, Env())
        self.macros.append((name, (t,), t, False, True))
        return ['define-syntax', name,
                ['syntax-rules', ['=>'],
                 [['_', 'pa', '=>', 'pf'], ['pf', 'pa']],
                 [['_', 'pa'], [f, 'pa']]]]

    # -- expressions -------------------------------------------------------

    def expr(self, t, d, env):
        self.budget -= 1
        if t == ANY:
            t = self.printable(1)
        if d <= 0 or self.budget <= 0:
            return self.leaf(t, env)
        if self.chance(0.12):
            v = self.var(t, env)
            if v is not None:
                return v
        if env.exits() and self.chance(0.06):
            e = self.nonlocal_exit(t, d, env)
            if e is not None:
                return ['if', self.expr(BOOL, d - 1, env.calm()), e, self.expr(t, d - 1, env)] \
                    if self.chance(0.6) else e
        for _ in range(8):
            f = self.weighted(self.productions(t))
            e = f(t, d - 1, env)
            if e is not None:
                return e
        return self.leaf(t, env)

    def args(self, types, d, env):
        """Arguments, evaluated in an unspecified order: at most one may leave non-locally."""
        if not types:
            return []
        lucky = self.r.randrange(len(types))
        calm = env.calm()
        return [self.expr(t, d, env if i == lucky else calm) for i, t in enumerate(types)]

    def var(self, t, env):
        names = [n for n, vt in env.vars if vt == t] + [n for n, vt in self.globals if vt == t]
        if t == INT and self.params and self.chance(0.3):
            return [self.pick(self.params)]
        return self.pick(names) if names else None

    def leaf(self, t, env):
        if self.chance(0.4):
            v = self.var(t, env)
            if v is not None:
                return v
        k = kind(t)
        if k in LITERAL:
            return self.literal(t)
        if k == BV:
            return Bytes(str(self.r.randint(0, 255)) for _ in range(self.r.randint(0, 4)))
        if k == 'list':
            if t[1] in LITERAL and self.chance(0.6):
                return ['quote', [self.datum(t[1]) for _ in range(self.r.randint(0, 4))]]
            return ['list'] + [self.leaf(t[1], env) for _ in range(self.r.randint(0, 3))]
        if k == 'vec':
            if t[1] in LITERAL and self.chance(0.6):
                return Vec(self.datum(t[1]) for _ in range(self.r.randint(0, 4)))
            return ['vector'] + [self.leaf(t[1], env) for _ in range(self.r.randint(0, 3))]
        if k == 'pair':
            return ['cons', self.leaf(t[1], env), self.leaf(t[2], env)]
        if k == 'fn':
            prims = self.prims(t)
            if prims and self.chance(0.5):
                return self.pick(prims)
            params = [self.fresh('u') for _ in t[1]]
            return ['lambda', params, self.leaf(t[2], env.calm().bind(*zip(params, t[1])))]
        if k == 'rec':
            rec = self.record(t)
            return [rec[1]] + [self.leaf(ft, env) for ft, *_ in rec[3]]
        raise ValueError(t)

    def record(self, t):
        return next(r for r in self.records if r[0] == t[1])

    def literal(self, t):
        if t == INT:
            x = self.r.random()
            if x < 0.5:
                return str(self.r.randint(-20, 20))
            if x < 0.85:
                return str(self.pick(_INT_LITS))
            return str(self.r.randint(-2**70, 2**70))
        if t == RAT:
            if self.chance(0.4):
                return self.literal(INT)
            return f'{self.r.randint(-50, 50)}/{self.r.randint(2, 30)}'
        if t == FLO:
            if self.chance(0.6):
                return self.pick(_FLO_LITS)
            # Eighths, which chibi-scheme reads right (it reads 126.5991 as 126.59909999999999).
            return repr(self.r.randint(-8000, 8000) / 8)
        if t == BOOL:
            return self.pick(['#t', '#f', '#true', '#false'])
        if t == CHAR:
            return self.pick(_CHAR_LITS)
        if t == STR:
            return string_literal(self.pick(_STR_TEXTS))
        if t == SYM:
            return ['quote', self.pick(_SYM_LITS)]
        raise ValueError(t)

    def datum(self, t):
        """A datum of type t, as it appears inside a quotation."""
        if t == SYM:
            return self.pick(_SYM_LITS)
        if kind(t) == 'list':
            return [self.datum(t[1]) for _ in range(self.r.randint(0, 3))]
        if kind(t) == 'vec':
            return Vec(self.datum(t[1]) for _ in range(self.r.randint(0, 3)))
        if kind(t) == 'pair':
            return [self.datum(t[1]), '.', self.datum(t[2])]
        if t == BV:
            return Bytes(str(self.r.randint(0, 255)) for _ in range(self.r.randint(0, 3)))
        return self.literal(t)

    def datum_text(self, t):
        x = self.datum(t)
        return to_text(x, 10**9)

    # -- productions ---------------------------------------------------------

    def productions(self, t):
        k = kind(t)
        generic = [
            (5, self.p_if), (5, self.p_let), (2, self.p_lambda_app), (2, self.p_cond), (2, self.p_case),
            (1.5, self.p_when), (2.5, self.p_callcc), (2.5, self.p_guard), (2, self.p_values),
            (1.5, self.p_call_var), (2, self.p_call_fn), (1, self.p_fold), (1, self.p_do), (1.5, self.p_foreach),
            (1, self.p_internal_define), (0.7, self.p_case_lambda), (0.7, self.p_dynwind_value),
            (0.7, self.p_let_syntax), (1, self.p_read_back), (0.7, self.p_lazy), (0.3, self.p_eval),
            (1, self.p_container_ref), (0.7, self.p_car_cdr), (1, self.p_handler_escape),
            (1, self.p_continuable),
        ]
        if self.params:
            generic.append((1, self.p_parameterize))
        if any(m[2] == t for m in self.macros):
            generic.append((2, self.p_macro))
        if any(ft == t for rec in self.records for ft, *_ in rec[3]):
            generic.append((1.5, self.p_record_ref))
        specific = getattr(self, 't_' + k)(t)
        return generic + specific

    # Generic: any type.

    def p_if(self, t, d, env):
        test = self.expr(BOOL if self.chance(0.7) else ANY, d, env.calm() if self.chance(0.5) else env)
        return ['if', test, self.expr(t, d, env), self.expr(t, d, env)]

    def p_let(self, t, d, env):
        form = self.pick(['let', 'let', 'let*', 'letrec*'])
        n = self.r.randint(1, 3)
        binds, new = [], []
        inner = env
        for i in range(n):
            # letrec*'s inits see its own variables, so they get names nothing outside has.
            name, bt = (self.fresh('r') if form == 'letrec*' else self.local()), self.bind_type()
            if any(name == b for b, _ in new):
                name = self.fresh(name)
            if form == 'let':
                init = self.expr(bt, d, env if i == 0 else env.calm())
            elif form == 'let*':
                init = self.expr(bt, d, inner)
                inner = inner.bind((name, bt))
            else:
                # letrec*: inits are lambdas or see the variables bound before them.
                init = self.fn_expr(bt, d, inner) if kind(bt) == 'fn' else self.expr(bt, d, inner.calm())
                inner = inner.bind((name, bt))
            binds.append([name, init])
            new.append((name, bt))
        return [form, binds, self.expr(t, d, env.bind(*new))]

    def p_lambda_app(self, t, d, env):
        types = [self.bind_type() for _ in range(self.r.randint(0, 3))]
        params = [self.fresh('a') for _ in types]
        body = self.expr(t, d, env.bind(*zip(params, types)))
        return [['lambda', params, body]] + self.args(types, d, env)

    def p_cond(self, t, d, env):
        clauses = []
        for _ in range(self.r.randint(1, 3)):
            if self.chance(0.25):
                et = self.pick(EQV)
                l = self.fresh('l')
                test = ['memv', self.expr(et, d, env.calm()), self.expr(LIST(et), d, env.calm())]
                clauses.append([test, '=>', ['lambda', [l], self.expr(t, d, env.bind((l, LIST(et))))]])
            elif self.chance(0.15):
                clauses.append([self.expr(t, d, env)])  # (test): the test's value, if true
            else:
                clauses.append([self.expr(BOOL, d, env), self.expr(t, d, env)])
        clauses.append(['else', self.expr(t, d, env)])
        return ['cond'] + clauses

    def p_case(self, t, d, env):
        kt = self.pick([INT, CHAR, SYM, BOOL])
        used, clauses = set(), []
        for _ in range(self.r.randint(1, 3)):
            data = []
            for _ in range(self.r.randint(1, 3)):
                x = self.datum(kt)
                if _canonical(x) not in used:
                    used.add(_canonical(x))
                    data.append(x)
            if not data:
                continue
            if self.chance(0.15):
                x = self.fresh('c')
                clauses.append([data, '=>', ['lambda', [x], self.expr(t, d, env.bind((x, kt)))]])
            else:
                clauses.append([data, self.expr(t, d, env)])
        if self.chance(0.2):
            x = self.fresh('c')
            clauses.append(['else', '=>', ['lambda', [x], self.expr(t, d, env.bind((x, kt)))]])
        else:
            clauses.append(['else', self.expr(t, d, env)])
        return ['case', self.expr(kt, d, env)] + clauses

    def p_when(self, t, d, env):
        r = self.fresh('r')
        inner = env.bind((r, t))
        return ['let', [[r, self.expr(t, d, env)]],
                [self.pick(['when', 'unless']), self.expr(BOOL, d, inner), ['set!', r, self.expr(t, d, inner)]],
                r]

    def p_callcc(self, t, d, env):
        k = self.fresh('k')
        body = self.expr(t, d, env._with(esc=env.esc + ((k, t),)))
        return [self.pick(['call/cc', 'call-with-current-continuation']), ['lambda', [k], body]]

    def p_guard(self, t, d, env):
        e = self.fresh('e')
        clauses = []
        for test, et in self.r.sample([('string?', STR), ('symbol?', SYM), ('exact-integer?', INT),
                                       ('pair?', LIST(INT)), ('error-object?', None)], self.r.randint(1, 3)):
            if et is None:
                m, irr = self.fresh('m'), self.fresh('irr')
                clauses.append([['error-object?', e],
                                ['let', [[m, ['error-object-message', e]], [irr, ['error-object-irritants', e]]],
                                 self.expr(t, d, env.bind((m, STR), (irr, LIST(ANY))))]])
            else:
                clauses.append([[test, e], self.expr(t, d, env.bind((e, et)))])
        if not env.rais or self.chance(0.7):
            # Without an else, what no clause takes goes on to the enclosing handler.
            clauses.append(['else', self.expr(t, d, env)])
        body = self.expr(t, d + 1, env._with(rais=True))
        return ['guard', [e] + clauses, body]

    def p_values(self, t, d, env):
        types = [self.bind_type() for _ in range(self.r.randint(0, 3))]
        names = [self.fresh('v') for _ in types]
        x = self.r.random()
        produced = ['values'] + self.args(types, d, env)
        if x < 0.5:
            return ['call-with-values', ['lambda', [], produced],
                    ['lambda', names, self.expr(t, d, env.bind(*zip(names, types)))]]
        if x < 0.75 and types:
            # A rest formal takes the values after the first ones as a list.
            rest = self.fresh('rest')
            return ['let-values', [[names[:1] + ['.', rest], produced]],
                    self.expr(t, d, env.bind((names[0], types[0])))]
        return [self.pick(['let-values', 'let*-values']), [[names, produced]],
                self.expr(t, d, env.bind(*zip(names, types)))]

    def p_call_var(self, t, d, env):
        fns = [(n, ft) for n, ft in list(env.vars) + self.globals if kind(ft) == 'fn' and ft[2] == t]
        if not fns:
            return None
        name, ft = self.pick(fns)
        return [name] + self.args(list(ft[1]), d, env)

    def p_call_fn(self, t, d, env):
        ft = FN([self.pick([INT, STR, CHAR, FLO, LIST(INT)]) for _ in range(self.r.randint(0, 2))], t)
        return [self.fn_expr(ft, d, env.calm())] + self.args(list(ft[1]), d, env)

    def p_fold(self, t, d, env):
        if env.weight > 8:
            return None
        et = self.elem_type()
        l, acc, x, loop = self.fresh('l'), self.fresh('acc'), self.fresh('x'), self.fresh('loop')
        inner = env.bind((acc, t), (x, et)).heavier(8)
        return ['let', loop, [[l, self.expr(LIST(et), d, env.calm())], [acc, self.expr(t, d, env.calm())]],
                ['if', ['null?', l], acc,
                 ['let', [[x, ['car', l]]], [loop, ['cdr', l], self.loop_step(t, d, inner)]]]]

    def loop_step(self, t, d, env):
        """A loop's next accumulator: a modest expression, bounded if it is an exact integer."""
        e = self.expr(t, min(d, 2), env)
        if t == INT:
            return ['modulo', e, str(self.pick([1000003, 2**61 - 1, 97]))]
        return e

    def p_do(self, t, d, env):
        n = self.r.randint(0, 6)
        if env.weight * n > 64:
            return None
        i, acc = self.fresh('i'), self.fresh('acc')
        inner = env.bind((i, INT), (acc, t)).heavier(n)
        return ['do', [[i, '0', ['+', i, '1']], [acc, self.expr(t, d, env.calm()), self.loop_step(t, d, inner)]],
                [['=', i, str(n)], acc]]

    def p_foreach(self, t, d, env):
        if env.weight > 8:
            return None
        acc = self.fresh('acc')
        x = self.r.random()
        inner = env.bind((acc, t)).heavier(8)
        if x < 0.5:
            et = self.elem_type()
            if self.chance(0.3):
                et2, y = self.elem_type(), self.fresh('y')
                p = self.fresh('x')
                body = ['set!', acc, self.loop_step(t, d, inner.bind((p, et), (y, et2)))]
                loop = ['for-each', ['lambda', [p, y], body], self.expr(LIST(et), d, env.calm()),
                        self.expr(LIST(et2), d, env.calm())]
            else:
                p = self.fresh('x')
                loop = ['for-each', ['lambda', [p], ['set!', acc, self.loop_step(t, d, inner.bind((p, et)))]],
                        self.expr(LIST(et), d, env.calm())]
        elif x < 0.8:
            et, p = self.elem_type(), self.fresh('x')
            loop = ['vector-for-each', ['lambda', [p], ['set!', acc, self.loop_step(t, d, inner.bind((p, et)))]],
                    self.expr(VEC(et), d, env.calm())]
        else:
            p = self.fresh('c')
            loop = ['string-for-each', ['lambda', [p], ['set!', acc, self.loop_step(t, d, inner.bind((p, CHAR)))]],
                    self.expr(STR, d, env.calm())]
        return ['let', [[acc, self.expr(t, d, env.calm())]], loop, acc]

    def p_internal_define(self, t, d, env):
        body, inner = [], env
        for _ in range(self.r.randint(1, 3)):
            name, bt = self.fresh('d'), self.bind_type()
            if kind(bt) == 'fn' and self.chance(0.6):
                params = [self.fresh('p') for _ in bt[1]]
                fbody = self.expr(bt[2], d, inner.calm().heavier(16).bind(*zip(params, bt[1])))
                body.append(['define', [name] + params, fbody])
            else:
                body.append(['define', name, self.expr(bt, d, inner.calm())])
            inner = inner.bind((name, bt))
        if self.chance(0.3):
            a, b = self.fresh('d'), self.fresh('d')
            ta, tb = self.printable(0), self.printable(0)
            body.append(['define-values', [a, b], ['values', self.expr(ta, d, inner.calm()),
                                                   self.expr(tb, d, inner.calm())]])
            inner = inner.bind((a, ta), (b, tb))
        return ['let', []] + body + [self.expr(t, d, inner)]

    def p_case_lambda(self, t, d, env):
        clauses, arities = [], []
        for n in sorted(self.r.sample([0, 1, 2, 3], self.r.randint(1, 3))):
            params = [self.fresh('c') for _ in range(n)]
            clauses.append([params, self.expr(t, d, env.calm().bind(*((p, INT) for p in params)))])
            arities.append(n)
        rest = self.fresh('r')
        if self.chance(0.4):
            clauses.append([rest, self.expr(t, d, env.calm())])
            arities.append(self.r.randint(0, 4))
        n = self.pick(arities)
        return [['case-lambda'] + clauses] + self.args([INT] * n, d, env)

    def p_dynwind_value(self, t, d, env):
        return ['dynamic-wind', ['lambda', [], self.pick(['#f', "'before"])], ['lambda', [], self.expr(t, d, env)],
                ['lambda', [], self.pick(['#t', "'after"])]]

    def p_let_syntax(self, t, d, env):
        m, at, px = self.fresh('ls'), self.pick([INT, STR, SYM]), self.fresh('px')
        tmpl = self.expr(t, min(d, 2), Env().bind((px, at)))
        return [self.pick(['let-syntax', 'letrec-syntax']), [[m, ['syntax-rules', [], [['_', px], tmpl]]]],
                [m, self.expr(at, d, env.calm())]]

    def p_read_back(self, t, d, env):
        if kind(t) not in LITERAL + ('list', 'vec', 'pair', BV) or not self.literal_type(t):
            return None
        return ['read', ['open-input-string', string_literal(self.datum_text(t))]]

    def comparable(self, t):
        """Whether equal? compares values of type t (not procedures: R7RS leaves
        (eqv? (lambda (x) x) (lambda (y) y)) unspecified)."""
        if kind(t) in ('list', 'vec'):
            return self.comparable(t[1])
        if kind(t) == 'pair':
            return self.comparable(t[1]) and self.comparable(t[2])
        return kind(t) not in ('fn', 'rec')

    def literal_type(self, t):
        if kind(t) in ('list', 'vec'):
            return self.literal_type(t[1])
        if kind(t) == 'pair':
            return self.literal_type(t[1]) and self.literal_type(t[2])
        return t in LITERAL or t == BV

    def p_lazy(self, t, d, env):
        e = self.expr(t, d, env.calm())
        x = self.r.random()
        if x < 0.4:
            return ['force', ['delay', e]]
        if x < 0.6:
            return ['force', ['make-promise', e]]
        if x < 0.8:
            return ['force', ['delay-force', ['delay', e]]]
        p = self.fresh('p')
        return ['let', [[p, ['delay', e]]], ['force', p], ['force', p]]

    def p_eval(self, t, d, env):
        if 'rec' in repr(t):
            return None  # the records are the program's, not eval's
        saved = self.globals, self.params, self.macros, self.records
        self.globals, self.params, self.macros, self.records = [], [], [], []
        try:
            e = self.expr(t, min(d, 3), Env())
        finally:
            self.globals, self.params, self.macros, self.records = saved
        return ['eval', ['quote', e], ['environment'] + [['quote', lib] for lib in IMPORTS[1:]]]

    def p_container_ref(self, t, d, env):
        c, i = self.fresh('c'), self.expr(INT, d, env.calm())
        x = self.r.random()
        if x < 0.4:
            return ['let', [[c, self.expr(VEC(t), d, env.calm())]],
                    ['if', ['zero?', ['vector-length', c]], self.expr(t, d, env),
                     ['vector-ref', c, ['modulo', i, ['vector-length', c]]]]]
        if x < 0.8:
            return ['let', [[c, self.expr(LIST(t), d, env.calm())]],
                    ['if', ['null?', c], self.expr(t, d, env),
                     ['list-ref', c, ['modulo', i, ['length', c]]]]]
        # The value of a cxr of a nested list, if it has one.
        return ['let', [[c, self.expr(LIST(LIST(t)), d, env.calm())]],
                ['if', ['and', ['pair?', c], ['pair?', ['car', c]]], ['caar', c], self.expr(t, d, env)]]

    def p_car_cdr(self, t, d, env):
        other = self.printable(0)
        if self.chance(0.5):
            return ['car', self.expr(PAIR(t, other), d, env)]
        return ['cdr', self.expr(PAIR(other, t), d, env)]

    def p_handler_escape(self, t, d, env):
        """A handler that leaves through a continuation: raise is caught."""
        k, e = self.fresh('k'), self.fresh('e')
        handler = ['lambda', [e], [k, ['cond', [['string?', e], self.expr(t, d, env.calm().bind((e, STR)))],
                                       [['symbol?', e], self.expr(t, d, env.calm().bind((e, SYM)))],
                                       ['else', self.expr(t, d, env.calm())]]]]
        body = self.expr(t, d + 1, env._with(esc=env.esc + ((k, t),), rais=True, cont=()))
        return ['call/cc', ['lambda', [k], ['with-exception-handler', handler, ['lambda', [], body]]]]

    def p_continuable(self, t, d, env):
        """A handler that returns: raise-continuable gets its value."""
        pt, rt = self.pick([INT, STR, SYM]), self.pick([INT, STR, t])
        e = self.fresh('e')
        handler = ['lambda', [e], self.expr(rt, min(d, 2), env.calm().bind((e, pt)))]
        # Only this handler: it is the one raise-continuable calls, whatever the outer ones take.
        body = self.expr(t, d + 1, env._with(rais=False, cont=((pt, rt),)))
        return ['with-exception-handler', handler, ['lambda', [], body]]

    def p_parameterize(self, t, d, env):
        ps = self.r.sample(self.params, self.r.randint(1, len(self.params)))
        return ['parameterize', [[p, self.expr(INT, d, env.calm())] for p in ps], self.expr(t, d, env)]

    def p_macro(self, t, d, env):
        name, ats, _, ellipsis, arrow = self.pick([m for m in self.macros if m[2] == t])
        calm = env.calm()
        if ellipsis:
            return [name] + [self.expr(ats[0], d, calm) for _ in range(self.r.randint(0, 3))]
        if arrow and self.chance(0.5):
            return [name, self.expr(ats[0], d, calm), '=>', self.fn_expr(FN(ats, t), d, calm)]
        return [name] + [self.expr(a, d, calm) for a in ats]

    def p_record_ref(self, t, d, env):
        rec, i = self.pick([(rec, i) for rec in self.records for i, (ft, *_) in enumerate(rec[3]) if ft == t])
        ft, acc, mod = rec[3][i]
        if mod and self.chance(0.4):
            # Only a record made here is changed: another may be read elsewhere, in any order.
            v = self.fresh('r')
            fresh = [rec[1]] + self.args([t for t, *_ in rec[3]], d, env.calm())
            return ['let', [[v, fresh]], [mod, v, self.expr(ft, d, env.calm())], [acc, v]]
        return [acc, self.expr(('rec', rec[0]), d, env)]

    # Non-local exits.

    def nonlocal_exit(self, t, d, env):
        options = []
        if env.esc:
            options.append('esc')
        if env.rais:
            options.append('raise')
        if any(rt == t for _, rt in env.cont):
            options.append('cont')
        if not options:
            return None
        o = self.pick(options)
        if o == 'esc':
            k, kt = self.pick(env.esc)
            return [k, self.expr(kt, d - 1, env)]
        if o == 'cont':
            pt = self.pick([pt for pt, rt in env.cont if rt == t])
            return ['raise-continuable', self.expr(pt, d - 1, env.calm())]
        x = self.r.random()
        if x < 0.6:
            return [self.pick(['raise', 'raise', 'raise-continuable']),
                    self.expr(self.pick([STR, SYM, INT, LIST(INT)]), d - 1, env.calm())]
        return ['error', string_literal(self.pick(['bad thing', 'fuzz', 'λ!', ''])) ] + \
            [self.expr(self.printable(0), d - 1, env.calm()) for _ in range(self.r.randint(0, 2))]

    # Procedures.

    def prims(self, t):
        args, r = t[1], t[2]
        table = {
            ((INT,), INT): ['-', 'abs', 'square'],
            ((INT, INT), INT): ['+', '-', '*', 'max', 'min', 'gcd'],
            ((INT,), BOOL): ['even?', 'odd?', 'zero?', 'positive?', 'negative?', 'exact?', 'exact-integer?'],
            ((INT, INT), BOOL): ['=', '<', '>', '<=', '>=', 'eqv?', 'equal?'],
            ((CHAR,), CHAR): ['char-upcase', 'char-downcase', 'char-foldcase'],
            ((CHAR,), BOOL): ['char-alphabetic?', 'char-numeric?', 'char-whitespace?', 'char-upper-case?',
                              'char-lower-case?'],
            ((CHAR,), INT): ['char->integer'],
            ((STR,), STR): ['string-upcase', 'string-downcase', 'string-copy', 'string-foldcase'],
            ((STR, STR), STR): ['string-append'],
            ((STR,), INT): ['string-length'],
            ((SYM,), STR): ['symbol->string'],
            ((FLO,), FLO): ['floor', 'round', 'truncate', 'ceiling', 'square', '-'],
            ((FLO, FLO), FLO): ['+', '*', '-', 'max', 'min'],
            ((RAT,), RAT): ['abs', '-', 'floor', 'round', 'square'],
            ((RAT, RAT), RAT): ['+', '-', '*', 'max', 'min'],
            ((STR, STR), BOOL): ['string=?', 'string<?', 'string-ci=?', 'equal?', 'string>=?'],
            ((CHAR, CHAR), BOOL): ['char=?', 'char<?', 'char-ci=?', 'eqv?'],
            ((BOOL,), BOOL): ['not'],
            ((INT,), STR): ['number->string'],
            ((LIST(INT),), INT): ['length'],
            ((LIST(INT),), LIST(INT)): ['reverse', 'list-copy'],
            ((STR,), LIST(CHAR)): ['string->list'],
            ((), INT): list(self.params),
        }
        return table.get((tuple(args), r), [])

    def fn_expr(self, t, d, env):
        """An expression whose value is a procedure of type t; its body doesn't leave."""
        env = env.calm()
        x = self.r.random()
        if x < 0.15:
            v = self.var(t, env)
            if v is not None:
                return v
        prims = self.prims(t)
        if prims and x < 0.3:
            return self.pick(prims)
        params = [self.local() for _ in t[1]]
        for i in range(len(params)):
            if params[i] in params[:i]:
                params[i] = self.fresh('p')
        inner = env.heavier(16).bind(*zip(params, t[1]))
        if x < 0.45:
            # A closure over a variable of its own.
            c, ct = self.fresh('c'), self.printable(0)
            return ['let', [[c, self.expr(ct, d, env)]],
                    ['lambda', params, self.expr(t[2], d, inner.bind((c, ct)))]]
        if x < 0.5 and t[1]:
            rest = self.fresh('rest')
            return ['lambda', params[:-1] + ['.', rest] if len(params) > 1 else rest,
                    ['let', [[params[-1], ['car', rest]]], self.expr(t[2], d, inner)]] \
                if len(params) > 1 else \
                ['lambda', rest, ['let', [[params[0], ['car', rest]]], self.expr(t[2], d, inner)]]
        if x < 0.55:
            return ['if', self.expr(BOOL, d, env), ['lambda', params, self.expr(t[2], d, inner)],
                    ['lambda', params, self.expr(t[2], d, inner)]]
        return ['lambda', params, self.expr(t[2], d, inner)]

    # Type-specific productions: each returns [(weight, production)].

    def guarded_div(self, op, t, d, env, ints=False):
        """(op a b), unless b is 0; and with `ints` (integer division), only where chibi-scheme
        divides right: its floor/, floor-quotient and floor-remainder go wrong when either
        operand is at least 2^62 in magnitude, and truncate/ at -2^62 and 2^62."""
        a, b = self.fresh('n'), self.fresh('m')
        test = ['zero?', b]
        if ints:
            limit = str(2**62)
            if op.startswith('floor'):
                test = ['or', test, ['>=', ['abs', a], limit], ['>=', ['abs', b], limit]]
            else:
                test = ['or', test, ['=', ['abs', a], limit], ['=', ['abs', b], limit]]
        # (/ a b) on copies: chibi-scheme's / negates negative bignums in place when the
        # quotient is a ratio, which changes the variable (or the literal) they came from.
        operands = [['-', ['-', a]], ['-', ['-', b]]] if op == '/' else [a, b]
        return ['let', [[a, self.expr(t, d, env.calm())], [b, self.expr(t, d, env.calm())]],
                ['if', test, a, [op] + operands]]

    def string_to_number(self, d, env):
        """string->number of a string without whitespace: chibi-scheme skips it, and reads
        "\n100" as 100, not #f."""
        s = self.fresh('s')
        return ['let', [[s, self.expr(STR, d, env)]],
                ['if', ['memv', '#t', ['map', 'char-whitespace?', ['string->list', s]]], '#f', ['string->number', s]]]

    def flo_abs(self, d, env):
        """abs of a flonum that isn't -0.0, by adding 0.0: chibi-scheme's (abs -0.0) is -0.0."""
        return ['abs', ['+', self.expr(FLO, d, env), '0.0']]

    def small_rat(self, d, env):
        """An exact rational whose numerator and denominator are flonums too: chibi-scheme's
        inexact of a bignum isn't always the nearest flonum (291803963674569850706 gives
        2.9180396367456987e+20, not ...83e+20)."""
        x = self.fresh('x')
        limit = str(2**53)
        return ['let', [[x, self.expr(RAT, d, env.calm())]],
                ['if', ['and', ['<', ['abs', ['numerator', x]], limit], ['<', ['denominator', x], limit]], x,
                 f'{self.r.randint(-50, 50)}/{self.r.randint(1, 30)}']]

    def t_int(self, t):
        def arith(t, d, env):
            op = self.pick(['+', '-', '*', '+', '-', 'max', 'min', 'gcd', 'lcm'])
            # gcd and lcm of one argument: chibi-scheme gives (gcd -20) => -20, not 20.
            n = self.pick([0, 2, 3]) if op in ('gcd', 'lcm') else self.r.randint(0 if op in '+*' else 1, 3)
            if op == 'lcm' and n:
                # Not of 0: chibi-scheme's (lcm 0 5) divides by zero.
                names = [self.fresh('l') for _ in range(n)]
                return ['let', [[x, e] for x, e in zip(names, self.args([INT] * n, d, env))],
                        ['if', ['or'] + [['zero?', x] for x in names], '0', ['lcm'] + names]]
            return [op] + self.args([INT] * n, d, env)

        def unary(t, d, env):
            return [self.pick(['-', 'abs', 'square'])] + self.args([INT], d, env)

        def division(t, d, env):
            return self.guarded_div(self.pick(['quotient', 'remainder', 'modulo', 'floor-quotient',
                                               'floor-remainder', 'truncate-quotient', 'truncate-remainder']),
                                    INT, d, env, ints=True)

        def two_values(t, d, env):
            q, r = self.fresh('q'), self.fresh('r')
            pick = self.pick([q, r, ['+', ['*', q, '1000'], r]])
            e = self.guarded_div(self.pick(['floor/', 'truncate/']), INT, d, env, ints=True)
            e[2][3] = ['call-with-values', ['lambda', [], e[2][3]], ['lambda', [q, r], pick]]
            return e

        def isqrt(t, d, env):
            s, r = self.fresh('s'), self.fresh('r')
            return ['call-with-values', ['lambda', [], ['exact-integer-sqrt', ['abs', self.expr(INT, d, env)]]],
                    ['lambda', [s, r], self.pick([s, r, ['-', s, r]])]]

        def expt(t, d, env):
            return ['expt', self.expr(INT, min(d, 1), env.calm()), ['modulo', self.expr(INT, d, env.calm()), '9']]

        def from_rat(t, d, env):
            return [self.pick(['floor', 'ceiling', 'round', 'truncate', 'numerator', 'denominator'])] + \
                self.args([RAT], d, env)

        def from_flo(t, d, env):
            x = self.fresh('x')
            return ['let', [[x, self.expr(FLO, d, env.calm())]],
                    ['if', ['<', '-1e18', x, '1e18'],
                     ['exact', [self.pick(['floor', 'ceiling', 'round', 'truncate']), x]],
                     self.expr(INT, d, env)]]

        def length(t, d, env):
            x = self.r.random()
            if x < 0.25:
                return ['string-length', self.expr(STR, d, env)]
            if x < 0.5:
                return ['vector-length', self.expr(VEC(self.elem_type()), d, env)]
            if x < 0.7:
                return ['length', self.expr(LIST(self.elem_type()), d, env)]
            if x < 0.85:
                return ['bytevector-length', self.expr(BV, d, env)]
            return ['char->integer', self.expr(CHAR, d, env)]

        def bv_ref(t, d, env):
            b = self.fresh('b')
            return ['let', [[b, self.expr(BV, d, env.calm())]],
                    ['if', ['zero?', ['bytevector-length', b]], self.expr(INT, d, env),
                     ['bytevector-u8-ref', b, ['modulo', self.expr(INT, d, env.calm()), ['bytevector-length', b]]]]]

        def string_number(t, d, env):
            x = self.fresh('x')
            if self.chance(0.5):
                radix = self.pick(['2', '8', '10', '16'])
                return ['string->number', ['number->string', self.expr(INT, d, env), radix], radix]
            return ['let', [[x, self.string_to_number(d, env)]],
                    ['if', ['exact-integer?', x], x, self.expr(INT, d, env)]]

        def digit(t, d, env):
            return ['or', ['digit-value', self.expr(CHAR, d, env.calm())], self.expr(INT, d, env)]

        def apply_(t, d, env):
            if self.chance(0.5):
                return ['apply', self.pick(['+', '*', 'max', 'min']), self.expr(INT, d, env.calm()),
                        self.expr(LIST(INT), d, env.calm())]
            return ['apply', self.fn_expr(FN([INT, INT], INT), d, env), self.expr(INT, d, env.calm()),
                    ['list', self.expr(INT, d, env.calm())]]

        def tail_loop(t, d, env):
            if env.weight > 1:
                return None
            n = self.pick([10, 1000, 10000, 100000])
            i, a, loop = self.fresh('i'), self.fresh('a'), self.fresh('loop')
            step = self.pick([['+', a, i], ['modulo', ['+', ['*', a, '31'], i], '1000003'], ['-', a, '1'],
                              ['if', ['even?', i], ['+', a, '1'], a]])
            if self.chance(0.3):
                # Mutual tail recursion through letrec.
                ev, od = self.fresh('ev'), self.fresh('od')
                return ['letrec', [[ev, ['lambda', [i], ['if', ['=', i, '0'], "'even", [od, ['-', i, '1']]]]],
                                   [od, ['lambda', [i], ['if', ['=', i, '0'], "'odd", [ev, ['-', i, '1']]]]]],
                        ['if', ['eq?', [ev, str(n + self.r.randint(0, 1))], "'even"], '1', '0']]
            return ['let', loop, [[i, '0'], [a, self.expr(INT, min(d, 1), env.calm())]],
                    ['if', ['=', i, str(n)], a, [loop, ['+', i, '1'], step]]]

        def deep(t, d, env):
            if env.weight > 1:
                return None
            n = self.pick([10, 100, 1000, 10000, 50000])
            i, loop = self.fresh('i'), self.fresh('loop')
            if self.chance(0.4):
                return ['length', ['let', loop, [[i, str(n)]], ['if', ['=', i, '0'], ["quote", []],
                                                                  ['cons', i, [loop, ['-', i, '1']]]]]]
            return ['let', loop, [[i, str(n)]],
                    ['if', ['=', i, '0'], self.expr(INT, min(d, 1), env.calm()),
                     ['+', [loop, ['-', i, '1']], self.pick(['1', i, ['remainder', i, '7']])]]]

        def deep_through(t, d, env):
            """Non-tail recursion whose every level goes through a control form."""
            if env.weight > 1:
                return None
            n = self.pick([10, 100, 1000, 5000])
            i, loop = self.fresh('i'), self.fresh('loop')
            x = [loop, ['-', i, '1']]
            return ['let', loop, [[i, str(n)]],
                    ['if', ['=', i, '0'], self.expr(INT, min(d, 1), env.calm()), ['+', '1', self.through(x, i)]]]

        def tail_through(t, d, env):
            """A loop whose call to itself is a tail call through another form (R7RS 3.5)."""
            if env.weight > 1:
                return None
            n = self.pick([10, 1000, 100000])
            i, a, loop = self.fresh('i'), self.fresh('a'), self.fresh('loop')
            call = [loop, ['+', i, '1'], ['modulo', ['+', a, i], '1000003']]
            return ['let', loop, [[i, '0'], [a, self.expr(INT, min(d, 1), env.calm())]],
                    ['if', ['=', i, str(n)], a, self.tail_context(call, i, a, n)]]

        def nested_handlers(t, d, env):
            """raise-continuable in an inner handler goes to the outer one (R7RS 6.11)."""
            e1, e2 = self.fresh('e'), self.fresh('e')
            return ['with-exception-handler', ['lambda', [e1], ['*', e1, self.expr(INT, min(d, 1), env.calm())]],
                    ['lambda', [],
                     ['with-exception-handler', ['lambda', [e2], ['+', '1', ['raise-continuable', ['+', e2, '1']]]],
                      ['lambda', [], ['+', self.expr(INT, min(d, 1), env.calm()),
                                      ['raise-continuable', self.expr(INT, min(d, 1), env.calm())]]]]]]

        return [(6, arith), (2, unary), (3, division), (1, two_values), (1, isqrt), (1, expt), (2, from_rat),
                (1.5, from_flo), (3, length), (1, bv_ref), (1.5, string_number), (0.5, digit), (1, apply_),
                (0.7, tail_loop), (0.7, deep), (0.7, deep_through), (0.7, tail_through), (0.5, nested_handlers)]

    def through(self, x, i):
        """x (an int expression) evaluated through a control form, in no tail position."""
        v, e, k = self.fresh('v'), self.fresh('e'), self.fresh('k')
        forms = [
            ['guard', [e, ['#t', '0']], x],
            ['dynamic-wind', ['lambda', [], '#f'], ['lambda', [], x], ['lambda', [], '#f']],
            ['call-with-values', ['lambda', [], x], ['lambda', [v], v]],
            ['apply', ['lambda', [v], v], ['list', x]],
            ['car', ['map', ['lambda', [v], v], ['list', x]]],
            ['with-exception-handler', ['lambda', [e], '0'], ['lambda', [], x]],
            ['force', ['delay', x]],
            ['call/cc', ['lambda', [k], x]],
            ['vector-ref', ['vector', x], '0'],
            ['let-values', [[[v], x]], v],
            ['-', ['+', x, '1'], '1'],
        ]
        if self.params:
            forms.append(['parameterize', [[self.pick(self.params), i]], x])
        return self.pick(forms)

    def tail_context(self, call, i, a, n):
        """call, in a tail position of another form (R7RS 3.5), n times."""
        v, k = self.fresh('v'), self.fresh('k')
        forms = [
            ['cond', ['#f', '0'], ['else', call]],
            ['cond', [['memv', i, ["quote", ['-1']]], '=>', ['lambda', [v], v]], ['else', call]],
            ['case', ['modulo', i, '3'], [['0'], call], ['else', call]],
            ['and', '#t', call],
            ['or', '#f', call],
            ['when', '#t', call],
            ['unless', '#f', call],
            ['let', [], call],
            ['let*', [[v, i]], call],
            ['letrec', [[v, '0']], call],
            ['begin', i, call],
            ['apply', call[0], ['list'] + call[1:]],
            ['apply', call[0], call[1], ['list', call[2]]],
            ['call-with-values', ['lambda', [], ['values'] + call[1:]], call[0]],
            ['if', ['even?', i], call, call],
            ['do', [], ['#t', call]],
            [['lambda', [v], call], i],
        ]
        if n <= 1000:
            # chibi-scheme copies its stack on each capture, so a long loop of them takes minutes.
            forms.append(['call/cc', ['lambda', [k], call]])
        return self.pick(forms)

    def t_rat(self, t):
        def arith(t, d, env):
            op = self.pick(['+', '-', '*', 'max', 'min'])
            return [op] + self.args([RAT] * self.r.randint(1, 3), d, env)

        def div(t, d, env):
            return self.guarded_div('/', RAT, d, env)

        def exact_(t, d, env):
            x = self.fresh('x')
            return ['let', [[x, self.expr(FLO, d, env.calm())]],
                    ['if', ['<', '-1e18', x, '1e18'], ['exact', x], self.expr(RAT, d, env)]]

        def rationalize(t, d, env):
            # Not when the interval holds 0: chibi-scheme gives (rationalize 5 10) => -5, not 0.
            x, y = self.fresh('x'), self.fresh('y')
            return ['let', [[x, self.expr(RAT, d, env.calm())], [y, ['abs', self.expr(RAT, d, env.calm())]]],
                    ['if', ['<', y, ['abs', x]], ['rationalize', x, y], x]]

        def expt(t, d, env):
            b = self.fresh('b')
            # A negative power only of an integer: chibi-scheme gives (expt 2/3 -2) => 1.
            e = self.fresh('e')
            return ['let', [[b, self.expr(RAT, min(d, 1), env.calm())],
                            [e, ['-', ['modulo', self.expr(INT, d, env.calm()), '7'], '3']]],
                    ['if', ['zero?', b], b, ['expt', b, ['if', ['exact-integer?', b], e, ['abs', e]]]]]

        def roundtrip(t, d, env):
            # In decimal: chibi-scheme reads 1a99acdcaf518cd74408/fd in radix 16 as #f.
            return ['string->number', ['number->string', self.expr(RAT, d, env)]]

        return [(4, arith), (2, div), (1, exact_), (0.7, rationalize), (1, expt), (1, roundtrip),
                (3, lambda t, d, env: self.expr(INT, d, env))]

    def t_flo(self, t):
        def arith(t, d, env):
            op = self.pick(['+', '-', '*', 'max', 'min'])
            e = [op] + self.args([FLO] * self.r.randint(1, 3), d, env)
            # Plus 0.0, which makes -0.0 0.0: R7RS doesn't say which of -0.0 and 0.0 max and min
            # return; chibi-scheme returns the first, ours what IEEE 754-2019's maximum and minimum do.
            return ['+', e, '0.0'] if op in ('max', 'min') else e

        def mixed(t, d, env):
            r = self.fresh('r')
            args = [FLO, RAT] if self.chance(0.5) else [RAT, FLO]
            e = [self.pick(['+', '-'])] + self.args(args, d, env.calm())
            e[1 + args.index(RAT)] = r
            # Not an exact 0: whether (+ -0.0 0) is -0.0 or 0.0, R7RS doesn't say.
            return ['let', [[r, self.small_rat(d, env)]], ['if', ['zero?', r], '1.0', e]]

        def div(t, d, env):
            return ['/'] + self.args([FLO, FLO], d, env)

        def unary(t, d, env):
            op = self.pick(['abs', 'floor', 'ceiling', 'round', 'truncate', 'square', '-'])
            if op == 'abs':
                return self.flo_abs(d, env)
            return [op] + self.args([FLO], d, env)

        def sqrt_(t, d, env):
            return ['sqrt', self.flo_abs(d, env)]

        def inexact(t, d, env):
            return ['inexact', self.small_rat(d, env)]

        def roundtrip(t, d, env):
            # Only where chibi-scheme reads back what it writes (see _FLO_LITS).
            x = self.fresh('x')
            return ['let', [[x, self.expr(FLO, d, env)]],
                    ['if', ['<', '1', ['abs', x], '1e15'], ['string->number', ['number->string', x]], x]]

        def from_str(t, d, env):
            x = self.fresh('x')
            return ['let', [[x, self.string_to_number(d, env.calm())]],
                    ['if', ['and', x, ['inexact?', x], ['real?', x]], x, self.expr(FLO, d, env)]]

        return [(4, arith), (1, mixed), (1.5, div), (2, unary), (1, sqrt_), (2, inexact), (1, roundtrip),
                (0.5, from_str)]

    def t_bool(self, t):
        def compare(t, d, env):
            nt = self.pick([INT, RAT, FLO, INT])
            return [self.pick(['=', '<', '>', '<=', '>='])] + self.args([nt] * self.r.randint(2, 3), d, env)

        def num_pred(t, d, env):
            nt = self.pick([INT, RAT, FLO])
            # Not integer? of a flonum: chibi-scheme's (integer? -inf.0) is #t.
            p = self.pick(['zero?', 'positive?', 'negative?', 'exact?', 'inexact?', 'rational?', 'real?', 'number?',
                           'exact-integer?'] + (['odd?', 'even?', 'integer?'] if nt == INT else
                                                ['nan?', 'finite?', 'infinite?'] if nt == FLO else ['integer?']))
            return [p, self.expr(nt, d, env)]

        def logic(t, d, env):
            op = self.pick(['and', 'or', 'not'])
            if op == 'not':
                return ['not', self.expr(ANY if self.chance(0.3) else BOOL, d, env)]
            return [op] + [self.expr(BOOL, d, env) for _ in range(self.r.randint(0, 3))]

        def eqv(t, d, env):
            et = self.pick(EQV)
            return [self.pick(['eqv?', 'equal?'])] + self.args([et, et], d, env)

        def equal(t, d, env):
            et = self.printable(1)
            if self.chance(0.4):
                x = self.fresh('x')
                return ['let', [[x, self.expr(et, d, env)]], ['equal?', x, self.expr(et, d, env.calm()) if
                                                               self.chance(0.5) else x]]
            return ['equal?'] + self.args([et, et], d, env)

        def type_pred(t, d, env):
            p = self.pick(['pair?', 'list?', 'null?', 'symbol?', 'string?', 'vector?', 'char?', 'boolean?',
                           'bytevector?', 'procedure?', 'number?'])
            if p == 'procedure?':
                return [p, self.fn_expr(self.fn_type(), d, env)]
            return [p, self.expr(ANY, d, env)]

        def chars(t, d, env):
            if self.chance(0.4):
                return [self.pick(['char-alphabetic?', 'char-numeric?', 'char-whitespace?', 'char-upper-case?',
                                   'char-lower-case?']), self.expr(CHAR, d, env)]
            return [self.pick(['char=?', 'char<?', 'char>?', 'char<=?', 'char-ci=?'])] + \
                self.args([CHAR] * self.r.randint(2, 3), d, env)

        def strings(t, d, env):
            return [self.pick(['string=?', 'string<?', 'string>?', 'string<=?', 'string>=?', 'string-ci=?',
                               'string-ci<?'])] + self.args([STR] * self.r.randint(2, 3), d, env)

        def member(t, d, env):
            et = self.pick(EQV + (STR, LIST(INT)))
            op = self.pick(['memv', 'member']) if et in EQV else 'member'
            return ['if', [op] + self.args([et, LIST(et)], d, env), '#t', '#f']

        def other(t, d, env):
            x = self.r.random()
            if x < 0.3:
                return ['boolean=?'] + self.args([BOOL] * self.r.randint(2, 3), d, env)
            if x < 0.6:
                return ['symbol=?'] + self.args([SYM] * self.r.randint(2, 3), d, env)
            if x < 0.8 and self.records:
                rec = self.pick(self.records)
                return [rec[2], self.expr(('rec', rec[0]) if self.chance(0.5) else ANY, d, env)]
            return ['eof-object?', ['read-char', ['open-input-string', self.expr(STR, d, env)]]]

        return [(3, compare), (2, num_pred), (2, logic), (2, eqv), (2, equal), (2, type_pred), (1.5, chars),
                (1.5, strings), (1.5, member), (1, other)]

    def t_char(self, t):
        def case_(t, d, env):
            return [self.pick(['char-upcase', 'char-downcase', 'char-foldcase']), self.expr(CHAR, d, env)]

        def string_ref(t, d, env):
            s = self.fresh('s')
            return ['let', [[s, self.expr(STR, d, env.calm())]],
                    ['if', ['zero?', ['string-length', s]], self.expr(CHAR, d, env),
                     ['string-ref', s, ['modulo', self.expr(INT, d, env.calm()), ['string-length', s]]]]]

        def from_int(t, d, env):
            return ['integer->char', ['+', '32', ['modulo', self.expr(INT, d, env), '95']]]

        def read_char(t, d, env):
            c = self.fresh('c')
            return ['let', [[c, [self.pick(['read-char', 'peek-char']),
                                 ['open-input-string', self.expr(STR, d, env.calm())]]]],
                    ['if', ['eof-object?', c], self.expr(CHAR, d, env), c]]

        return [(2, case_), (2, string_ref), (1, from_int), (1, read_char)]

    def t_str(self, t):
        def append(t, d, env):
            return ['string-append'] + self.args([STR] * self.r.randint(0, 3), d, env)

        def substring(t, d, env):
            s, a, b = self.fresh('s'), self.fresh('a'), self.fresh('b')
            return ['let*', [[s, self.expr(STR, d, env.calm())],
                             [a, ['modulo', self.expr(INT, d, env.calm()), ['+', ['string-length', s], '1']]],
                             [b, ['+', a, ['modulo', self.expr(INT, d, env.calm()),
                                           ['+', ['-', ['string-length', s], a], '1']]]]],
                    [self.pick(['substring', 'string-copy']), s, a, b]]

        def number(t, d, env):
            if self.chance(0.5):
                return ['number->string', self.expr(self.pick([INT, RAT]), d, env), self.pick(['2', '8', '10', '16'])]
            return ['number->string', self.expr(self.pick([INT, RAT, FLO]), d, env)]

        def convert(t, d, env):
            x = self.r.random()
            if x < 0.25:
                return ['symbol->string', self.expr(SYM, d, env)]
            if x < 0.5:
                return ['list->string', self.expr(LIST(CHAR), d, env)]
            if x < 0.65:
                return ['string'] + self.args([CHAR] * self.r.randint(0, 3), d, env)
            if x < 0.8:
                return ['make-string', ['modulo', self.expr(INT, d, env.calm()), '5'], self.expr(CHAR, d, env.calm())]
            if x < 0.9:
                return ['vector->string', self.expr(VEC(CHAR), d, env)]
            return ['utf8->string', ['string->utf8', self.expr(STR, d, env)]]

        def case_(t, d, env):
            return [self.pick(['string-upcase', 'string-downcase', 'string-foldcase']), self.expr(STR, d, env)]

        def string_map(t, d, env):
            c = self.fresh('c')
            if self.chance(0.4):
                return ['string-map', self.pick(['char-upcase', 'char-downcase', 'char-foldcase']), self.expr(STR, d, env)]
            if self.chance(0.3):
                c2 = self.fresh('c')
                return ['string-map', ['lambda', [c, c2], self.expr(CHAR, d, env.calm().bind((c, CHAR), (c2, CHAR)))],
                        self.expr(STR, d, env.calm()), self.expr(STR, d, env.calm())]
            return ['string-map', ['lambda', [c], self.expr(CHAR, d, env.calm().heavier(8).bind((c, CHAR)))],
                    self.expr(STR, d, env.calm())]

        def mutate(t, d, env):
            s = self.fresh('s')
            x = self.r.random()
            init = ['string-copy', self.expr(STR, d, env.calm())] if self.chance(0.6) else \
                ['make-string', ['modulo', self.expr(INT, d, env.calm()), '6'], self.expr(CHAR, d, env.calm())]
            n = ['string-length', s]
            if x < 0.4:
                op = ['if', ['positive?', n], ['string-set!', s, ['modulo', self.expr(INT, d, env.calm()), n],
                                               self.expr(CHAR, d, env.calm())]]
            elif x < 0.7:
                a = self.fresh('a')
                op = ['let', [[a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]]],
                      ['string-fill!', s, self.expr(CHAR, d, env.calm()), a]]
            else:
                # string-copy! within one string: overlapping ranges copy as if through a buffer.
                a, b = self.fresh('a'), self.fresh('b')
                op = ['let*', [[a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]],
                               [b, ['modulo', self.expr(INT, d, env.calm()), ['+', ['-', n, a], '1']]]],
                      ['string-copy!', s, a, s, '0', b]]
            return ['let', [[s, init]], op, s]

        def port(t, d, env):
            p = self.fresh('p')
            ops = []
            for _ in range(self.r.randint(1, 3)):
                x = self.r.random()
                if x < 0.4:
                    # display only of atoms but symbols: chibi-scheme displays the characters and
                    # strings in a list, and symbols like |a b|, as write does.
                    # Nor bytevectors, whose bytes chibi writes in hexadecimal (fuzz.py), which a
                    # program may take apart as a string.
                    pt = self.printable(1)
                    while 'bv' in repr(pt):
                        pt = self.printable(1)
                    op = self.pick(['write', 'display']) if pt in ATOMIC and pt != SYM else 'write'
                    ops.append([op, self.expr(pt, d, env.calm()), p])
                elif x < 0.6:
                    ops.append(['write-string', self.expr(STR, d, env.calm()), p])
                elif x < 0.8:
                    ops.append(['write-char', self.expr(CHAR, d, env.calm()), p])
                else:
                    ops.append(['newline', p])
            return ['let', [[p, ['open-output-string']]]] + ops + [['get-output-string', p]]

        def read_line(t, d, env):
            p, x = self.fresh('p'), self.fresh('x')
            op = ['read-line', p] if self.chance(0.6) else \
                ['read-string', ['+', '1', ['modulo', self.expr(INT, d, env.calm()), '5']], p]
            return ['let*', [[p, ['open-input-string', self.expr(STR, d, env.calm())]], [x, op]],
                    ['if', ['eof-object?', x], self.expr(STR, d, env), x]]

        return [(3, append), (2, substring), (2, number), (2, convert), (1.5, case_), (1.5, string_map),
                (1.5, mutate), (1.5, port), (1, read_line)]

    def t_sym(self, t):
        def from_string(t, d, env):
            # Not of a string that starts with a dot: chibi-scheme writes (string->symbol ".3.")
            # as .3., which isn't an identifier, not |.3.|.
            s = self.fresh('s')
            return ['let', [[s, self.expr(STR, d, env)]],
                    ['if', ['and', ['positive?', ['string-length', s]], ['char=?', ['string-ref', s, '0'], '#\\.']],
                     self.literal(SYM), ['string->symbol', s]]]

        def roundtrip(t, d, env):
            return ['string->symbol', ['symbol->string', self.expr(SYM, d, env)]]

        return [(2, from_string), (1, roundtrip)]

    def t_bv(self, t):
        def make(t, d, env):
            x = self.r.random()
            if x < 0.4:
                return ['bytevector'] + [['modulo', e, '256'] for e in self.args([INT] * self.r.randint(0, 3), d, env)]
            if x < 0.6:
                return ['make-bytevector', ['modulo', self.expr(INT, d, env.calm()), '5'],
                        ['modulo', self.expr(INT, d, env.calm()), '256']]
            if x < 0.8:
                return ['string->utf8', self.expr(STR, d, env)]
            return ['bytevector-append'] + self.args([BV] * self.r.randint(0, 3), d, env)

        def copy(t, d, env):
            b, a, c = self.fresh('b'), self.fresh('a'), self.fresh('c')
            return ['let*', [[b, self.expr(BV, d, env.calm())],
                             [a, ['modulo', self.expr(INT, d, env.calm()), ['+', ['bytevector-length', b], '1']]],
                             [c, ['+', a, ['modulo', self.expr(INT, d, env.calm()),
                                           ['+', ['-', ['bytevector-length', b], a], '1']]]]],
                    ['bytevector-copy', b, a, c]]

        def mutate(t, d, env):
            b = self.fresh('b')
            n = ['bytevector-length', b]
            if self.chance(0.5):
                op = ['if', ['positive?', n], ['bytevector-u8-set!', b, ['modulo', self.expr(INT, d, env.calm()), n],
                                               ['modulo', self.expr(INT, d, env.calm()), '256']]]
            else:
                a = self.fresh('a')
                op = ['let*', [[a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]]],
                      ['bytevector-copy!', b, ['-', n, a], b, '0', a]]
            return ['let', [[b, ['bytevector-copy', self.expr(BV, d, env.calm())]]], op, b]

        return [(3, make), (1.5, copy), (1, mutate)]

    def t_list(self, t):
        et = t[1]

        def make(t, d, env):
            return ['list'] + self.args([et] * self.r.randint(0, 4), d, env)

        def cons(t, d, env):
            return ['cons'] + self.args([et, t], d, env)

        def append(t, d, env):
            return ['append'] + self.args([t] * self.r.randint(0, 3), d, env)

        def unary(t, d, env):
            return [self.pick(['reverse', 'list-copy'])] + self.args([t], d, env)

        def tail(t, d, env):
            l = self.fresh('l')
            if self.chance(0.5):
                return ['let', [[l, self.expr(t, d, env.calm())]], ['if', ['pair?', l], ['cdr', l], ["quote", []]]]
            return ['let', [[l, self.expr(t, d, env.calm())]],
                    ['list-tail', l, ['modulo', self.expr(INT, d, env.calm()), ['+', ['length', l], '1']]]]

        def map_(t, d, env):
            a = self.elem_type()
            if self.chance(0.25):
                b = self.elem_type()
                return ['map', self.fn_expr(FN([a, b], et), d, env), self.expr(LIST(a), d, env.calm()),
                        self.expr(LIST(b), d, env.calm())]
            return ['map', self.fn_expr(FN([a], et), d, env.heavier(8)), self.expr(LIST(a), d, env.calm())]

        def from_vec(t, d, env):
            v, a, b = self.fresh('v'), self.fresh('a'), self.fresh('b')
            x = self.r.random()
            if x < 0.5:
                return ['vector->list', self.expr(VEC(et), d, env)]
            n = ['vector-length', v]
            return ['let*', [[v, self.expr(VEC(et), d, env.calm())],
                             [a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]],
                             [b, ['+', a, ['modulo', self.expr(INT, d, env.calm()), ['+', ['-', n, a], '1']]]]],
                    ['vector->list', v, a, b]]

        def make_list(t, d, env):
            return ['make-list', ['modulo', self.expr(INT, d, env.calm()), '5'], self.expr(et, d, env.calm())]

        def member(t, d, env):
            if not self.comparable(et):
                return None
            if et in EQV:
                return ['or', [self.pick(['memv', 'member'])] + self.args([et, t], d, env), ['quote', []]]
            if self.chance(0.3):
                return ['or', ['member', self.expr(et, d, env.calm()), self.expr(t, d, env.calm()),
                               self.fn_expr(FN([et, et], BOOL), d, env)], ['quote', []]]
            return ['or', ['member'] + self.args([et, t], d, env), ['quote', []]]

        def quasi(t, d, env):
            parts = []
            for _ in range(self.r.randint(0, 4)):
                if self.chance(0.3):
                    parts.append(['unquote-splicing', self.expr(t, d, env.calm())])
                elif et in LITERAL and self.chance(0.3):
                    parts.append(self.datum(et))
                else:
                    parts.append(['unquote', self.expr(et, d, env.calm())])
            return ['quasiquote', parts]

        def reentry(t, d, env):
            """A continuation re-entered until it has given n values."""
            k, n, acc, v, c = self.fresh('k'), self.fresh('n'), self.fresh('acc'), self.fresh('v'), self.fresh('c')
            count = str(self.r.randint(1, 4))
            return ['let', [[k, '#f'], [n, '0'], [acc, ["quote", []]]],
                    ['let', [[v, ['call/cc', ['lambda', [c], ['set!', k, c], self.expr(et, d, env.calm())]]]],
                     ['set!', acc, ['cons', v, acc]],
                     ['set!', n, ['+', n, '1']],
                     ['if', ['<', n, count], [k, self.expr(et, d, env.calm().bind((v, et)))], ['reverse', acc]]]]

        def dynwind_reentry(t, d, env):
            if et != SYM:
                return None
            k, n, log = self.fresh('k'), self.fresh('n'), self.fresh('log')
            count = str(self.r.randint(1, 3))
            return ['let', [[k, '#f'], [n, '0'], [log, ["quote", []]]],
                    ['dynamic-wind', ['lambda', [], ['set!', log, ['cons', "'in", log]]],
                     ['lambda', [], ['call/cc', ['lambda', ['c'], ['set!', k, 'c']]], ['set!', n, ['+', n, '1']]],
                     ['lambda', [], ['set!', log, ['cons', "'out", log]]]],
                    ['if', ['<', n, count], [k, '#f']],
                    ['reverse', log]]

        def escape_log(t, d, env):
            """after thunks run on the way out, through an escape or a raise."""
            if et != ANY and et != SYM:
                return None
            log, k, r = self.fresh('log'), self.fresh('k'), self.fresh('r')
            rt = self.printable(0)
            if self.chance(0.5):
                body = self.expr(rt, d, Env(vars=env.vars, esc=((k, rt),)))
                catch = ['call/cc', ['lambda', [k], ['dynamic-wind', ['lambda', [], ['set!', log, ['cons', "'in", log]]],
                                                     ['lambda', [], body],
                                                     ['lambda', [], ['set!', log, ['cons', "'out", log]]]]]]
            else:
                body = self.expr(rt, d, Env(vars=env.vars, rais=True))
                e = self.fresh('e')
                catch = ['guard', [e, ['#t', ["quote", 'caught']]],
                         ['dynamic-wind', ['lambda', [], ['set!', log, ['cons', "'in", log]]],
                          ['lambda', [], body],
                          ['lambda', [], ['set!', log, ['cons', "'out", log]]]]]
            return ['let', [[log, ["quote", []]]],
                    ['let', [[r, catch]], ['set!', log, ['cons', "'done", log]], ['reverse', log]]]

        def string_list(t, d, env):
            if et != CHAR:
                return None
            return ['string->list', self.expr(STR, d, env)]

        def deep(t, d, env):
            if env.weight > 1 or et != INT:
                return None
            n = self.pick([10, 1000, 10000])
            i, loop = self.fresh('i'), self.fresh('loop')
            return ['list-tail', ['let', loop, [[i, str(n)]], ['if', ['=', i, '0'], ["quote", []],
                                                                ['cons', i, [loop, ['-', i, '1']]]]], str(n - 3)]

        def assoc(t, d, env):
            k = self.pick(EQV)
            p = self.fresh('p')
            return ['let', [[p, [self.pick(['assv', 'assoc']), self.expr(k, d, env.calm()),
                                 self.expr(LIST(PAIR(k, t)), d, env.calm())]]],
                    ['if', p, ['cdr', p], self.expr(t, d, env)]]

        def list_set(t, d, env):
            l = self.fresh('l')
            return ['let', [[l, ['list-copy', self.expr(t, d, env.calm())]]],
                    ['if', ['pair?', l], ['list-set!', l, ['modulo', self.expr(INT, d, env.calm()), ['length', l]],
                                          self.expr(et, d, env.calm())]],
                    l]

        def map_reentry(t, d, env):
            """A continuation captured in map's procedure, re-entered after map returned: the lists
            earlier returns gave are not changed (R7RS 6.10)."""
            if et != LIST(INT):
                return None
            k, n, acc, x, c = self.fresh('k'), self.fresh('n'), self.fresh('acc'), self.fresh('x'), self.fresh('c')
            r = self.fresh('r')
            items = [str(j) for j in range(1, self.r.randint(2, 5))]
            hit = self.pick(items)
            return ['let', [[k, '#f'], [n, '0'], [acc, ["quote", []]]],
                    ['let', [[r, ['map', ['lambda', [x], ['call/cc', ['lambda', [c], ['if', ['=', x, hit], ['set!', k, c]],
                                                                    x]]],
                                  ["quote", items]]]],
                     ['set!', acc, ['cons', r, acc]],
                     ['set!', n, ['+', n, '1']],
                     ['if', ['<', n, str(self.r.randint(1, 3))], [k, ['*', n, '10']], ['reverse', acc]]]]

        def coroutine(t, d, env):
            """A generator: for-each walks a list, handing each element out through one continuation
            and resuming through another."""
            ret, res, out, v, c, r, x = (self.fresh(b) for b in ('ret', 'res', 'out', 'v', 'c', 'r', 'x'))
            end, walk, loop = self.fresh('end'), self.fresh('walk'), self.fresh('loop')
            return ['let*', [[ret, '#f'], [res, '#f'], [out, ["quote", []]], [end, ['list', "'end"]],
                             [walk, ['lambda', [],
                                     ['for-each', ['lambda', [x], ['call/cc', ['lambda', [c], ['set!', res, c],
                                                                               [ret, x]]]],
                                      self.expr(t, d, env.calm())],
                                     [ret, end]]]],
                    ['let', loop, [],
                     ['let', [[v, ['call/cc', ['lambda', [r], ['set!', ret, r], ['if', res, [res, '#f'], [walk]]]]]],
                      ['if', ['eq?', v, end], ['reverse', out], ['begin', ['set!', out, ['cons', v, out]], [loop]]]]]]

        def nested_winds(t, d, env):
            """Re-entering two nested dynamic-winds from outside runs both befores, outer first."""
            if et != SYM:
                return None
            k, n, log = self.fresh('k'), self.fresh('n'), self.fresh('log')

            def wind(tag, body):
                return ['dynamic-wind', ['lambda', [], ['set!', log, ['cons', f"'{tag}-in", log]]],
                        ['lambda', [], body],
                        ['lambda', [], ['set!', log, ['cons', f"'{tag}-out", log]]]]
            inner = wind('b', ['begin', ['call/cc', ['lambda', ['c'], ['set!', k, 'c']]], ['set!', n, ['+', n, '1']]])
            return ['let', [[k, '#f'], [n, '0'], [log, ["quote", []]]],
                    wind('a', inner),
                    ['if', ['<', n, str(self.r.randint(2, 3))], [k, '#f']],
                    ['reverse', log]]

        def param_reentry(t, d, env):
            """Re-entering a parameterize body from outside rebinds the parameter (R7RS 4.2.6)."""
            if et != INT or not self.params:
                return None
            p = self.pick(self.params)
            k, n, acc = self.fresh('k'), self.fresh('n'), self.fresh('acc')
            return ['let', [[k, '#f'], [n, '0'], [acc, ["quote", []]]],
                    ['parameterize', [[p, self.expr(INT, min(d, 1), env.calm())]],
                     ['call/cc', ['lambda', ['c'], ['set!', k, 'c']]],
                     ['set!', acc, ['cons', [p], acc]]],
                    ['set!', acc, ['cons', [p], acc]],
                    ['set!', n, ['+', n, '1']],
                    ['if', ['<', n, '3'], [k, '#f']],
                    ['reverse', acc]]

        return [(4, make), (2, cons), (2, append), (1.5, unary), (1.5, tail), (2, map_), (1, from_vec),
                (1, make_list), (1, member), (1.5, quasi), (1, reentry), (1, dynwind_reentry), (1, escape_log),
                (1, string_list), (0.5, deep), (1, assoc), (1, list_set), (1, map_reentry), (1, coroutine),
                (1, nested_winds), (1, param_reentry)]

    def t_vec(self, t):
        et = t[1]

        def make(t, d, env):
            return ['vector'] + self.args([et] * self.r.randint(0, 4), d, env)

        def make_vector(t, d, env):
            return ['make-vector', ['modulo', self.expr(INT, d, env.calm()), '5'], self.expr(et, d, env.calm())]

        def from_list(t, d, env):
            return ['list->vector', self.expr(LIST(et), d, env)]

        def vmap(t, d, env):
            a = self.elem_type()
            return ['vector-map', self.fn_expr(FN([a], et), d, env.heavier(8)), self.expr(VEC(a), d, env.calm())]

        def copy(t, d, env):
            v, a, b = self.fresh('v'), self.fresh('a'), self.fresh('b')
            n = ['vector-length', v]
            if self.chance(0.4):
                return ['vector-append'] + self.args([t] * self.r.randint(0, 3), d, env)
            return ['let*', [[v, self.expr(t, d, env.calm())],
                             [a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]],
                             [b, ['+', a, ['modulo', self.expr(INT, d, env.calm()), ['+', ['-', n, a], '1']]]]],
                    ['vector-copy', v, a, b]]

        def mutate(t, d, env):
            v = self.fresh('v')
            n = ['vector-length', v]
            x = self.r.random()
            if x < 0.4:
                op = ['if', ['positive?', n], ['vector-set!', v, ['modulo', self.expr(INT, d, env.calm()), n],
                                               self.expr(et, d, env.calm())]]
            elif x < 0.7:
                a = self.fresh('a')
                op = ['let', [[a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]]],
                      ['vector-fill!', v, self.expr(et, d, env.calm()), a]]
            else:
                a = self.fresh('a')
                op = ['let', [[a, ['modulo', self.expr(INT, d, env.calm()), ['+', n, '1']]]],
                      ['vector-copy!', v, '0', v, ['-', n, a], n] if self.chance(0.5) else
                      ['vector-copy!', v, ['-', n, a], v, '0', a]]
            return ['let', [[v, ['vector-copy', self.expr(t, d, env.calm())]]], op, v]

        def string_vec(t, d, env):
            if et != CHAR:
                return None
            return ['string->vector', self.expr(STR, d, env)]

        def quasi(t, d, env):
            parts = []
            for _ in range(self.r.randint(0, 3)):
                if self.chance(0.2):
                    parts.append(['unquote-splicing', self.expr(LIST(et), d, env.calm())])
                else:
                    parts.append(['unquote', self.expr(et, d, env.calm())])
            return ['quasiquote', Vec(parts)]

        return [(4, make), (1, make_vector), (2, from_list), (1.5, vmap), (1.5, copy), (1.5, mutate),
                (0.5, string_vec), (1, quasi)]

    def t_pair(self, t):
        def cons(t, d, env):
            return ['cons'] + self.args([t[1], t[2]], d, env)

        return [(5, cons)]

    def t_fn(self, t):
        return [(5, lambda t, d, env: self.fn_expr(t, d, env))]

    def t_rec(self, t):
        def make(t, d, env):
            rec = self.record(t)
            return [rec[1]] + self.args([ft for ft, *_ in rec[3]], d, env)

        return [(5, make)]


def generate(seed, index, depth=5, cases=8, exits=True, prefix=None):
    """The top-level forms of program `index` of the run with this seed (reproducible)."""
    rng = random.Random(f'{seed}:{index}')
    g = Gen(rng, prefix if prefix is not None else f'p{index}-', depth=depth, cases=cases, exits=exits)
    return g.program()
