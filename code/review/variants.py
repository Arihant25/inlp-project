"""
Review control: semantics-preserving variants of the CORRECT solutions.

Reviewer request: compare d(fix, bug) with d(fix, variant), where the variant is a
behaviour-preserving edit of the fix with matched edit size (identifier rename,
statement swap, for->while, commutative comparison swap).

For every HumanEvalFix correct solution (Python, JavaScript, Java; the RQ5 corpus
document, i.e. imports + declaration + body) and every ClassEval reference class
this module builds the following variant kinds:

    rename1    rename ONE local variable / parameter (all its occurrences in its
               scope) to a neutral, fresh name of similar length (up to 5 per
               program, one per candidate variable, in order of first occurrence)
    renameall  rename ALL candidate local variables / parameters (needs >= 2)
    cmpswap    mirror one comparison between side-effect-free operands
               (a < b -> b > a, a == b -> b == a; up to 3 per program)
    stmtswap   swap two adjacent independent single-line assignments /
               declarations with side-effect-free, non-raising right-hand sides
               (up to 2 per program)
    for2while  rewrite a counting for-loop as the equivalent while-loop
               (no `continue`, loop variable unused after the loop; up to 2)

Python (and ClassEval) variants are produced with `ast` (exact source positions);
renames and comparison swaps are additionally checked by comparing the AST of the
edited program with the original AST transformed in the same way. JavaScript and
Java variants use a tokenizer that skips strings, characters, regexes and
comments; a name is renamed only if it is declared locally (let/var/const,
parameters, typed declarations), never appears after `.`/`::`, is never called,
is not an object key / shorthand property, is not a top-level or class-member
declaration and does not occur in the test code.

EVERY variant is executed against the benchmark's hidden tests (code/rq5/execute.py);
only passing variants are kept (the original correct solution passes all tests).

Usage:
    python code/review/variants.py            # build + validate all languages
    python code/review/variants.py --lang js  # one language

Output: results/review/variants.parquet (all candidates with pass/fail) and
        results/review/variant_counts.json
"""

import argparse
import ast
import builtins
import json
import keyword
import os
import re
import sys

import pandas as pd

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(SCRIPT_DIR), "rq5"))
from common import PROJECT_ROOT, build_corpus, load_language  # noqa: E402
from execute import run_many  # noqa: E402

OUT_DIR = os.path.join(PROJECT_ROOT, "results/review")
LANGS = ["python", "js", "java", "classeval"]
KINDS = ["rename1", "renameall", "cmpswap", "stmtswap", "for2while"]
MAX_PER_KIND = {"rename1": 5, "renameall": 1, "cmpswap": 3, "stmtswap": 2, "for2while": 2}

# --------------------------------------------------------------------------- names

_META = ["foo", "bar", "baz", "qux", "quux", "corge", "grault", "garply", "waldo", "fred", "plugh",
         "xyzzy", "thud"]


def _name_pool(style: str) -> list[str]:
    """Deterministic pool of neutral identifiers of many lengths."""
    pool = list("qzwkjxyuv") + [f"v{i}" for i in range(10)] + _META
    pool += [f"{m}{i}" for m in _META for i in range(10)]
    joined = []
    for a in _META:
        for b in _META:
            if a != b:
                joined.append(a + "_" + b if style == "snake" else a + b.capitalize())
    pool += joined
    pool += [f"{a}_{b}_{c}" if style == "snake" else a + b.capitalize() + c.capitalize()
             for a, b, c in zip(_META, _META[3:] + _META[:3], _META[6:] + _META[:6])]
    return pool


def fresh_name(old: str, taken: set[str]) -> str:
    """A neutral fresh identifier whose length is as close as possible to `old`."""
    style = "snake" if "_" in old.strip("_") else "camel" if re.search(r"[a-z][A-Z]", old) else "plain"
    pool = _name_pool("snake" if style != "camel" else "camel")
    best = None
    for i, cand in enumerate(pool):
        if cand in taken or cand == old:
            continue
        key = (abs(len(cand) - len(old)), i)
        if best is None or key < best[0]:
            best = (key, cand)
    return best[1]


IDENT_RE = re.compile(r"[A-Za-z_$][\w$]*")
PY_RESERVED = set(keyword.kwlist) | set(getattr(keyword, "softkwlist", [])) | set(dir(builtins))
JS_RESERVED = set("""break case catch class const continue debugger default delete do else export extends
false finally for function if import in instanceof new null return super switch this throw true try typeof
var void while with yield let static enum await implements package protected interface private public
arguments eval undefined NaN Infinity of async get set""".split())
JAVA_RESERVED = set("""abstract assert boolean break byte case catch char class const continue default do
double else enum extends final finally float for goto if implements import instanceof int interface long
native new package private protected public return short static strictfp super switch synchronized this
throw throws transient try void volatile while true false null var record yield sealed permits""".split())


def taken_names(*texts: str) -> set[str]:
    out = set()
    for t in texts:
        out |= set(IDENT_RE.findall(t))
    return out


# --------------------------------------------------------------------------- python


class _Pos:
    """Convert ast (lineno, utf-8 byte col) to absolute character offsets."""

    def __init__(self, src: str):
        self.src = src
        self.lines = src.splitlines(keepends=True)
        self.starts = [0]
        for ln in self.lines:
            self.starts.append(self.starts[-1] + len(ln))

    def off(self, lineno: int, col: int) -> int:
        line = self.lines[lineno - 1]
        return self.starts[lineno - 1] + len(line.encode("utf-8")[:col].decode("utf-8"))

    def span(self, node) -> tuple[int, int]:
        return self.off(node.lineno, node.col_offset), self.off(node.end_lineno, node.end_col_offset)


def apply_edits(src: str, edits: list[tuple[int, int, str]]) -> str:
    """Apply non-overlapping (start, end, replacement) edits."""
    edits = sorted(edits)
    for (s1, e1, _), (s2, _e2, _) in zip(edits, edits[1:]):
        if s2 < e1:
            raise ValueError("overlapping edits")
    for s, e, r in reversed(edits):
        src = src[:s] + r + src[e:]
    return src


def _scopes(tree: ast.Module) -> list[ast.AST]:
    """Rename scopes: module-level functions and methods of module-level classes."""
    out = []
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            out.append(node)
        elif isinstance(node, ast.ClassDef):
            out += [n for n in node.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]
    return out


def _all_args(fn) -> list[ast.arg]:
    a = fn.args
    out = a.posonlyargs + a.args + a.kwonlyargs
    out += [x for x in (a.vararg, a.kwarg) if x is not None]
    return out


def py_local_candidates(fn) -> list[str]:
    """Local variables and parameters of `fn` that can be alpha-renamed safely."""
    order, bad = [], set()
    for a in _all_args(fn):
        order.append(a.arg)
    for node in ast.walk(fn):
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            order.append(node.id)
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            bad |= set(node.names)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            bad.add(node.name)
        elif isinstance(node, (ast.Import, ast.ImportFrom)):
            bad |= {(al.asname or al.name).split(".")[0] for al in node.names}
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node is not fn:
            bad.add(node.name)
        elif isinstance(node, ast.keyword) and node.arg:
            bad.add(node.arg)  # keyword argument names refer to callee parameters
        elif isinstance(node, (ast.MatchAs, ast.MatchStar)) and node.name:
            bad.add(node.name)
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in (
                "locals", "vars", "eval", "exec", "globals"):
            return []
    seen, out = set(), []
    for n in order:
        if n in seen or n in bad or n in ("self", "cls") or n in PY_RESERVED:
            continue
        seen.add(n)
        out.append(n)
    return out


def _py_name_spans(fn, name: str, pos: _Pos) -> list[tuple[int, int]]:
    spans = []
    for node in ast.walk(fn):
        if isinstance(node, ast.Name) and node.id == name:
            spans.append(pos.span(node))
        elif isinstance(node, ast.arg) and node.arg == name:
            s = pos.off(node.lineno, node.col_offset)
            spans.append((s, s + len(name)))
    return spans


class _Renamer(ast.NodeTransformer):
    def __init__(self, mapping_by_fn: dict[int, dict[str, str]]):
        self.m = mapping_by_fn
        self.cur = None

    def _visit_fn(self, node):
        prev = self.cur
        if id(node) in self.m:
            self.cur = self.m[id(node)]
        self.generic_visit(node)
        self.cur = prev
        return node

    visit_FunctionDef = visit_AsyncFunctionDef = _visit_fn

    def visit_Name(self, node):
        if self.cur and node.id in self.cur:
            node.id = self.cur[node.id]
        return node

    def visit_arg(self, node):
        if self.cur and node.arg in self.cur:
            node.arg = self.cur[node.arg]
        self.generic_visit(node)
        return node


def py_rename(src: str, plan: list[tuple[int, str, str]]) -> str | None:
    """plan: (scope index, old, new). Returns the renamed source, AST-verified."""
    tree = ast.parse(src)
    scopes = _scopes(tree)
    pos = _Pos(src)
    edits = []
    for si, old, new in plan:
        for s, e in _py_name_spans(scopes[si], old, pos):
            if src[s:e] != old:
                return None
            edits.append((s, e, new))
    out = apply_edits(src, edits)
    # Verification: AST of the edited text == original AST with the names substituted.
    ref = ast.parse(src)
    ref_scopes = _scopes(ref)
    mapping = {}
    for si, old, new in plan:
        mapping.setdefault(id(ref_scopes[si]), {})[old] = new
    ref = _Renamer(mapping).visit(ref)
    try:
        if ast.dump(ast.parse(out)) != ast.dump(ref):
            return None
    except SyntaxError:
        return None
    return out


_PURE_CALLS = {"len", "abs", "int", "float", "str", "min", "max", "sorted", "list", "set", "dict",
               "tuple", "bool", "ord", "chr", "sum", "round"}


def _py_pure(node, allow_raise=True) -> bool:
    """Side-effect-free expression (no user calls, no mutation)."""
    if isinstance(node, ast.Constant):
        return True
    if isinstance(node, ast.Name):
        return True
    if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
        return all(_py_pure(e, allow_raise) for e in node.elts)
    if isinstance(node, ast.Dict):
        return all(k is not None and _py_pure(k, allow_raise) for k in node.keys) and all(
            _py_pure(v, allow_raise) for v in node.values)
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        return isinstance(node.operand, ast.Constant) or (allow_raise and _py_pure(node.operand))
    if not allow_raise:
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in (
                "set", "list", "dict") and not node.args and not node.keywords:
            return True
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "len" \
                and len(node.args) == 1 and isinstance(node.args[0], ast.Name) and not node.keywords:
            return True
        return False
    if isinstance(node, ast.BinOp):
        return _py_pure(node.left) and _py_pure(node.right)
    if isinstance(node, ast.Attribute):
        return _py_pure(node.value)
    if isinstance(node, ast.Subscript):
        return _py_pure(node.value) and _py_pure(node.slice)
    if isinstance(node, ast.Slice):
        return all(x is None or _py_pure(x) for x in (node.lower, node.upper, node.step))
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in _PURE_CALLS:
        return not node.keywords and all(_py_pure(a) for a in node.args)
    return False


_MIRROR_PY = {ast.Lt: (ast.Gt, ">"), ast.Gt: (ast.Lt, "<"), ast.LtE: (ast.GtE, ">="),
              ast.GtE: (ast.LtE, "<="), ast.Eq: (ast.Eq, "=="), ast.NotEq: (ast.NotEq, "!=")}


def py_cmpswaps(src: str) -> list[str]:
    tree = ast.parse(src)
    pos = _Pos(src)
    out = []
    for idx, node in enumerate(ast.walk(tree)):
        if not (isinstance(node, ast.Compare) and len(node.ops) == 1 and type(node.ops[0]) in _MIRROR_PY):
            continue
        left, right = node.left, node.comparators[0]
        if not (_py_pure(left) and _py_pure(right)):
            continue
        ls, le = pos.span(left)
        rs, re_ = pos.span(right)
        between = src[le:rs]
        lt, rt = src[ls:le], src[rs:re_]
        if "\n" in lt or "\n" in rt or not re.fullmatch(r"\s*(<|>|<=|>=|==|!=)\s*", between):
            continue
        new_op = _MIRROR_PY[type(node.ops[0])][1]
        cand = src[:ls] + rt + between.replace(between.strip(), new_op) + lt + src[re_:]
        # verify: AST equals the original with this Compare mirrored
        ref = ast.parse(src)
        target = [n for i, n in enumerate(ast.walk(ref)) if i == idx][0]
        target.left, target.comparators = right_copy(target.comparators[0]), [right_copy(target.left)]
        target.ops = [_MIRROR_PY[type(target.ops[0])][0]()]
        try:
            if ast.dump(ast.parse(cand)) == ast.dump(ref):
                out.append(cand)
        except SyntaxError:
            pass
    return out


def right_copy(n):
    import copy
    return copy.deepcopy(n)


def _names_in(node) -> set[str]:
    return {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}


def py_stmtswaps(src: str) -> list[str]:
    tree = ast.parse(src)
    lines = src.splitlines(keepends=True)
    out = []
    for block_owner in ast.walk(tree):
        for field in ("body", "orelse", "finalbody"):
            body = getattr(block_owner, field, None)
            if not isinstance(body, list):
                continue
            for a, b in zip(body, body[1:]):
                if not all(isinstance(s, ast.Assign) and len(s.targets) == 1 and isinstance(s.targets[0], ast.Name)
                           and s.lineno == s.end_lineno for s in (a, b)):
                    continue
                if b.lineno != a.lineno + 1:
                    continue
                ta, tb = a.targets[0].id, b.targets[0].id
                if ta == tb or not (_py_pure(a.value, allow_raise=False) and _py_pure(b.value, allow_raise=False)):
                    continue
                if tb in _names_in(a.value) or ta in _names_in(b.value):
                    continue
                la, lb = lines[a.lineno - 1], lines[b.lineno - 1]
                ia = re.match(r"\s*", la).group(0)
                ib = re.match(r"\s*", lb).group(0)
                if ia != ib or "#" in la or "#" in lb or ";" in la or ";" in lb:
                    continue
                new_lines = list(lines)
                ea = la[len(la.rstrip("\r\n")):]
                eb = lb[len(lb.rstrip("\r\n")):]
                new_lines[a.lineno - 1] = lb.rstrip("\r\n") + ea
                new_lines[b.lineno - 1] = la.rstrip("\r\n") + eb
                cand = "".join(new_lines)
                try:
                    ast.parse(cand)
                except SyntaxError:
                    continue
                out.append(cand)
    return out


def _has_continue(stmts) -> bool:
    return any(isinstance(n, ast.Continue) for s in stmts for n in ast.walk(s))


def py_for2while(src: str) -> list[str]:
    tree = ast.parse(src)
    lines = src.splitlines(keepends=True)
    pos = _Pos(src)
    out = []
    for fn in _scopes(tree):
        for loop in ast.walk(fn):
            if not (isinstance(loop, ast.For) and isinstance(loop.target, ast.Name) and not loop.orelse
                    and isinstance(loop.iter, ast.Call) and isinstance(loop.iter.func, ast.Name)
                    and loop.iter.func.id == "range" and 1 <= len(loop.iter.args) <= 3 and not loop.iter.keywords):
                continue
            var = loop.target.id
            if loop.body[0].lineno <= loop.lineno or loop.iter.end_lineno != loop.lineno:
                continue
            if _has_continue(loop.body):
                continue
            # loop variable not rebound in the body and not used anywhere outside the loop
            loop_nodes = {id(n) for n in ast.walk(loop)}
            if any(isinstance(n, ast.Name) and n.id == var and id(n) not in loop_nodes for n in ast.walk(fn)):
                continue
            if any(isinstance(n, ast.arg) and n.arg == var for n in ast.walk(fn)):
                continue
            if any(isinstance(n, ast.Name) and n.id == var and isinstance(n.ctx, ast.Store)
                   for s in loop.body for n in ast.walk(s)):
                continue
            args = loop.iter.args
            start, stop = (None, args[0]) if len(args) == 1 else (args[0], args[1])
            step = 1
            if len(args) == 3:
                st = args[2]
                if isinstance(st, ast.Constant) and isinstance(st.value, int) and st.value != 0:
                    step = st.value
                elif isinstance(st, ast.UnaryOp) and isinstance(st.op, ast.USub) and isinstance(
                        st.operand, ast.Constant) and isinstance(st.operand.value, int) and st.operand.value != 0:
                    step = -st.operand.value
                else:
                    continue
            # stop is re-evaluated by the while condition: it must be invariant in the body
            if not _invariant_bound(stop, loop.body):
                continue
            stop_src = src[slice(*pos.span(stop))]
            start_src = "0" if start is None else src[slice(*pos.span(start))]
            hdr = lines[loop.lineno - 1]
            ind = re.match(r"[ \t]*", hdr).group(0)
            if not hdr.strip().startswith("for ") or not hdr.rstrip().endswith(":"):
                continue
            body_ind = re.match(r"[ \t]*", lines[loop.body[0].lineno - 1]).group(0)
            cmp = "<" if step > 0 else ">"
            inc = f"{var} += {step}" if step > 0 else f"{var} -= {-step}"
            if isinstance(stop, (ast.Compare, ast.BoolOp, ast.IfExp, ast.NamedExpr, ast.Lambda)):
                stop_src = f"({stop_src})"
            new_lines = list(lines)
            nl = hdr[len(hdr.rstrip("\r\n")):] or "\n"
            new_lines[loop.lineno - 1] = f"{ind}{var} = {start_src}{nl}{ind}while {var} {cmp} {stop_src}:{nl}"
            last = loop.body[-1].end_lineno
            tail = new_lines[last - 1]
            if not tail.endswith("\n"):
                tail += "\n"
            new_lines[last - 1] = tail + f"{body_ind}{inc}\n"
            cand = "".join(new_lines)
            if not src.endswith("\n") and cand.endswith("\n"):
                cand = cand[:-1]
            try:
                ast.parse(cand)
            except SyntaxError:
                continue
            out.append(cand)
    return out


def _invariant_bound(expr, body) -> bool:
    if isinstance(expr, ast.Constant):
        return True
    names = _names_in(expr)
    if not _py_pure(expr):
        return False
    for s in body:
        for n in ast.walk(s):
            if isinstance(n, ast.Name) and n.id in names:
                if isinstance(n.ctx, ast.Store):
                    return False
            if isinstance(n, (ast.Attribute, ast.Subscript)) and isinstance(n.value, ast.Name) and \
                    n.value.id in names and (isinstance(n.ctx, (ast.Store, ast.Del)) or isinstance(n, ast.Attribute)):
                return False  # x.append(...), x[i] = ..., del x[i]
            if isinstance(n, ast.Call) and any(isinstance(a, ast.Name) and a.id in names for a in n.args) \
                    and not (isinstance(n.func, ast.Name) and n.func.id in _PURE_CALLS):
                return False  # passed to a function that could mutate it
            if isinstance(n, (ast.Global, ast.Nonlocal)):
                return False
    return True


def python_variants(src: str, test: str) -> dict[str, list[str]]:
    tree = ast.parse(src)
    scopes = _scopes(tree)
    taken = taken_names(src, test) | PY_RESERVED
    cands = []  # (scope index, name)
    for si, fn in enumerate(scopes):
        cands += [(si, n) for n in py_local_candidates(fn)]
    out = {k: [] for k in KINDS}
    mapping = {}
    for si, n in cands:
        if n not in mapping:
            mapping[n] = fresh_name(n, taken | set(mapping.values()))
    for si, n in cands:
        v = py_rename(src, [(si, n, mapping[n])])
        if v is not None and v != src:
            out["rename1"].append(v)
    if len(cands) >= 2:
        v = py_rename(src, [(si, n, mapping[n]) for si, n in cands])
        if v is not None:
            out["renameall"].append(v)
    out["cmpswap"] = py_cmpswaps(src)
    out["stmtswap"] = py_stmtswaps(src)
    out["for2while"] = py_for2while(src)
    return out


# --------------------------------------------------------------------------- JS / Java tokenizer

_OPS = sorted(""">>>= === !== >>> <<= >>= **= ... => -> == != <= >= && || ++ -- += -= *= /= %= &= |= ^= << >>
:: ?. ?? ** { } ( ) [ ] ; , . < > + - * / % & | ^ ! ~ ? : = @ #""".split(), key=len, reverse=True)
_REGEX_PREV = set("( , = : [ ! & | ? { } ; + - * % < > ~ ^ && || == === != !== += -= => return typeof case "
                  "do else in of new delete void throw".split())


def tokenize_c(src: str, lang: str) -> list[dict]:
    """Tokens: {'t': kind, 's': start, 'e': end, 'v': text}. Kinds: id num str com op."""
    toks, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c.isspace():
            i += 1
            continue
        start = i
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            toks.append({"t": "com", "s": start, "e": i, "v": src[start:i]})
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            toks.append({"t": "com", "s": start, "e": i, "v": src[start:i]})
            continue
        if lang == "java" and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            toks.append({"t": "str", "s": start, "e": i, "v": src[start:i]})
            continue
        if c in "\"'`":
            j = i + 1
            while j < n and src[j] != c:
                if src[j] == "\\":
                    j += 1
                elif c != "`" and src[j] == "\n":
                    break
                j += 1
            i = j + 1
            toks.append({"t": "str", "s": start, "e": i, "v": src[start:i], "template": c == "`"})
            continue
        if lang == "js" and c == "/":
            prev = next((t for t in reversed(toks) if t["t"] != "com"), None)
            if prev is None or (prev["t"] == "op" and prev["v"] in _REGEX_PREV and prev["v"] not in (")", "]", "}")) \
                    or (prev["t"] == "id" and prev["v"] in _REGEX_PREV):
                j, in_cls = i + 1, False
                while j < n and src[j] != "\n":
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == "[":
                        in_cls = True
                    elif src[j] == "]":
                        in_cls = False
                    elif src[j] == "/" and not in_cls:
                        break
                    j += 1
                j += 1
                while j < n and (src[j].isalnum()):
                    j += 1
                i = j
                toks.append({"t": "str", "s": start, "e": i, "v": src[start:i]})
                continue
        m = IDENT_RE.match(src, i)
        if m:
            i = m.end()
            toks.append({"t": "id", "s": start, "e": i, "v": m.group(0)})
            continue
        if c.isdigit() or (c == "." and i + 1 < n and src[i + 1].isdigit()):
            m = re.compile(r"(0[xX][0-9a-fA-F_]+|\d[\d_]*\.?\d*([eE][+-]?\d+)?|\.\d+([eE][+-]?\d+)?)[a-zA-Z]*").match(src, i)
            i = m.end()
            toks.append({"t": "num", "s": start, "e": i, "v": src[start:i]})
            continue
        for op in _OPS:
            if src.startswith(op, i):
                i += len(op)
                toks.append({"t": "op", "s": start, "e": i, "v": op})
                break
        else:
            i += 1
            toks.append({"t": "op", "s": start, "e": i, "v": c})
    return toks


def _code_tokens(src, lang):
    return [t for t in tokenize_c(src, lang) if t["t"] != "com"]


def _match(toks, i):
    """Index of the bracket matching toks[i]."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    o = toks[i]["v"]
    c = pairs[o]
    d = 0
    for j in range(i, len(toks)):
        if toks[j]["t"] == "op":
            if toks[j]["v"] == o:
                d += 1
            elif toks[j]["v"] == c:
                d -= 1
                if d == 0:
                    return j
    return None


def _depths(toks):
    """Brace depth before each token."""
    out, d = [], 0
    for t in toks:
        if t["t"] == "op" and t["v"] == "}":
            d -= 1
        out.append(d)
        if t["t"] == "op" and t["v"] == "{":
            d += 1
    return out


_JAVA_TYPES = {"int", "long", "double", "float", "boolean", "char", "short", "byte", "var", "String"}


def c_local_candidates(src: str, test: str, lang: str) -> list[str]:
    toks = _code_tokens(src, lang)
    test_ids = taken_names(test)
    reserved = JS_RESERVED if lang == "js" else JAVA_RESERVED
    depth = _depths(toks)
    declared, bad = [], set()
    paren = 0
    pdepth = []
    for t in toks:
        if t["t"] == "op" and t["v"] in "([":
            paren += 1
        if t["t"] == "op" and t["v"] in ")]":
            paren -= 1
        pdepth.append(paren)
    for k, t in enumerate(toks):
        if t["t"] != "id":
            continue
        v = t["v"]
        prev = toks[k - 1] if k > 0 else None
        nxt = toks[k + 1] if k + 1 < len(toks) else None
        pv = prev["v"] if prev else None
        nv = nxt["v"] if nxt else None
        if pv in (".", "::", "?.") or nv == "(" or (nv == "::"):
            bad.add(v)
        if lang == "js":
            if nv == ":" and pv in ("{", ","):
                bad.add(v)  # object key
            if pv in ("{", ",") and nv in (",", "}") and _in_object_literal(toks, k):
                bad.add(v)  # shorthand property
            if pv in ("let", "var", "const"):
                if depth[k] == 0:
                    bad.add(v)  # top-level binding (entry point / helpers)
                else:
                    declared.append(v)
            elif nv == "=>" and pv != ")":
                declared.append(v)
            elif pv in ("(", ",") and nv in (",", ")", "="):
                # parameter of an arrow function or function expression/declaration
                if _is_param_list(toks, k, lang):
                    declared.append(v)
            if pv in ("[",) or pv == ",":
                if _in_array_destructuring(toks, k):
                    declared.append(v)
        else:
            if v[:1].isupper():
                bad.add(v)
                continue
            if prev and nv in ("=", ";", ":", ",", ")") and (
                    (prev["t"] == "id" and (pv in _JAVA_TYPES or pv[:1].isupper())) or pv in (">", "]")):
                if depth[k] <= 1 and pdepth[k] == 0:
                    bad.add(v)  # class field
                else:
                    declared.append(v)
            if nv == "->" and pv in ("(", ","):
                declared.append(v)
            elif nv == "->":
                declared.append(v)
            elif pv in ("(", ",") and nv in (",", ")"):
                if _is_param_list(toks, k, lang):
                    declared.append(v)
    seen, out = set(), []
    for v in declared:
        if v in seen or v in bad or v in reserved or v in test_ids:
            continue
        seen.add(v)
        out.append(v)
    return out


def _is_param_list(toks, k, lang) -> bool:
    """toks[k] is inside a (...) that is followed by => / -> or preceded by `function name`."""
    d = 0
    for j in range(k, -1, -1):
        v = toks[j]["v"] if toks[j]["t"] == "op" else None
        if v == ")":
            d += 1
        elif v == "(":
            if d == 0:
                close = _match(toks, j)
                after = toks[close + 1]["v"] if close is not None and close + 1 < len(toks) else None
                if after in ("=>", "->"):
                    return True
                if lang == "js" and j >= 1 and (toks[j - 1]["v"] == "function" or (
                        j >= 2 and toks[j - 2]["v"] == "function")):
                    return True
                return False
            d -= 1
    return False


def _in_object_literal(toks, k) -> bool:
    d = 0
    for j in range(k, -1, -1):
        v = toks[j]["v"] if toks[j]["t"] == "op" else None
        if v in ("}", ")", "]"):
            d += 1
        elif v in ("{", "(", "["):
            if d == 0:
                return v == "{" and j > 0 and toks[j - 1]["v"] in ("=", "(", ",", ":", "return", "[", "=>")
            d -= 1
    return False


def _in_array_destructuring(toks, k) -> bool:
    d = 0
    for j in range(k, -1, -1):
        v = toks[j]["v"] if toks[j]["t"] == "op" else None
        if v in ("]", ")", "}"):
            d += 1
        elif v in ("[", "(", "{"):
            if d == 0:
                return v == "[" and j > 0 and toks[j - 1]["v"] in ("let", "var", "const")
            d -= 1
    return False


def c_rename(src: str, lang: str, mapping: dict[str, str]) -> str:
    toks = _code_tokens(src, lang)
    edits = []
    for k, t in enumerate(toks):
        if t["t"] == "id" and t["v"] in mapping:
            pv = toks[k - 1]["v"] if k > 0 else None
            if pv in (".", "::", "?."):
                continue
            edits.append((t["s"], t["e"], mapping[t["v"]]))
    return apply_edits(src, edits)


_CMP_MIRROR = {"<": ">", ">": "<", "<=": ">=", ">=": "<=", "==": "==", "!=": "!=", "===": "===", "!==": "!=="}
_BOUND_BEFORE = {"(", "&&", "||", ",", ";", "=", "return", "?", ":"}
_BOUND_AFTER = {")", "&&", "||", ";", ",", "?", ":"}
_MUTATING = {"pop", "shift", "push", "unshift", "splice", "next", "nextInt", "remove", "poll", "add", "put",
             "append", "insert", "offer", "set", "clear", "sort", "reverse", "delete", "addAll", "removeAll",
             "random", "read", "readLine", "push_back", "fill", "replace"}


def _operand_end(toks, i, lang):
    """If toks[i:] starts a side-effect-free primary-chain operand, return its end index (exclusive)."""
    t = toks[i]
    if t["t"] == "id":
        if t["v"] in (JS_RESERVED | JAVA_RESERVED) - {"this", "true", "false", "null", "length", "undefined"}:
            return None
    elif t["t"] not in ("num", "str"):
        return None
    j = i + 1
    while j < len(toks):
        v = toks[j]["v"] if toks[j]["t"] == "op" else None
        if v == "." and j + 1 < len(toks) and toks[j + 1]["t"] == "id":
            if toks[j + 1]["v"] in _MUTATING:
                return None
            j += 2
        elif v in ("(", "["):
            close = _match(toks, j)
            if close is None:
                return None
            inner = toks[j + 1:close]
            if any(x["t"] == "op" and x["v"] in ("=", "++", "--", "+=", "-=", "=>", "->", "*=", "/=") or
                   (x["t"] == "id" and x["v"] in {"new", "function"} | _MUTATING) for x in inner):
                return None
            j = close + 1
        else:
            break
    return j


def c_cmpswaps(src: str, lang: str) -> list[str]:
    toks = _code_tokens(src, lang)
    out = []
    for k, t in enumerate(toks):
        if t["t"] != "op" or t["v"] not in _CMP_MIRROR or k == 0:
            continue
        # left operand: find the start s with _operand_end(s) == k and a boundary before s
        left = None
        for s in range(k - 1, max(-1, k - 40), -1):
            if _operand_end(toks, s, lang) == k:
                if s > 0 and toks[s - 1]["v"] in _BOUND_BEFORE:
                    left = s
        if left is None:
            continue
        rend = _operand_end(toks, k + 1, lang) if k + 1 < len(toks) else None
        if rend is None or rend >= len(toks) or toks[rend]["v"] not in _BOUND_AFTER:
            continue
        lt = toks[left:k]
        rt = toks[k + 1:rend]
        if any(x["t"] == "id" and x["v"][:1].isupper() for x in (lt[0], rt[0])) and t["v"] in ("<", ">") and (
                lang == "java"):
            continue  # possibly generics (List<Integer>)
        ls, le = lt[0]["s"], lt[-1]["e"]
        rs, re_ = rt[0]["s"], rt[-1]["e"]
        if "\n" in src[ls:re_]:
            continue
        cand = src[:ls] + src[rs:re_] + src[le:t["s"]] + _CMP_MIRROR[t["v"]] + src[t["e"]:rs] + src[ls:le] + src[re_:]
        out.append(cand)
    return out


_JS_DECL = re.compile(r"^(?P<ind>[ \t]*)(?P<kw>let|var|const)\s+(?P<name>[A-Za-z_$][\w$]*)\s*=\s*(?P<rhs>.+?);[ \t]*$")
_JAVA_DECL = re.compile(r"^(?P<ind>[ \t]*)(?P<kw>(?:final\s+)?[A-Za-z_][\w]*(?:\s*<[\w<>,\s?\[\]]*>)?(?:\[\])*)\s+"
                        r"(?P<name>[a-z_][\w]*)\s*=\s*(?P<rhs>.+?);[ \t]*$")
_SAFE_RHS = re.compile(r"""^(-?\d+(\.\d+)?[lLfFdD]?|true|false|null|""|''|"[^"\\]*"|'[^'\\]*'|\[\]|\{\}|
    new\s+[A-Za-z_]\w*\s*(<[\w<>,\s]*>)?\s*\(\s*\)|[a-z_$][\w$]*|-?Infinity)$""", re.X)


def c_stmtswaps(src: str, lang: str) -> list[str]:
    lines = src.split("\n")
    pat = _JS_DECL if lang == "js" else _JAVA_DECL
    out = []
    for i in range(len(lines) - 1):
        a, b = pat.match(lines[i]), pat.match(lines[i + 1])
        if not (a and b) or a.group("ind") != b.group("ind"):
            continue
        if lang == "java" and (a.group("kw").split()[-1] in ("return", "throw") or b.group("kw") in ("return",)):
            continue
        ra, rb = a.group("rhs").strip(), b.group("rhs").strip()
        if not (_SAFE_RHS.match(ra) and _SAFE_RHS.match(rb)):
            continue
        na, nb = a.group("name"), b.group("name")
        if na == nb or re.search(rf"\b{re.escape(nb)}\b", ra) or re.search(rf"\b{re.escape(na)}\b", rb):
            continue
        if "//" in lines[i] or "//" in lines[i + 1]:
            continue
        new = list(lines)
        new[i], new[i + 1] = lines[i + 1], lines[i]
        out.append("\n".join(new))
    return out


def c_for2while(src: str, lang: str) -> list[str]:
    toks = _code_tokens(src, lang)
    out = []
    for k, t in enumerate(toks):
        if not (t["t"] == "id" and t["v"] == "for" and k + 1 < len(toks) and toks[k + 1]["v"] == "("):
            continue
        close = _match(toks, k + 1)
        if close is None or close + 1 >= len(toks) or toks[close + 1]["v"] != "{":
            continue
        header = toks[k + 2:close]
        # split on top-level ';'
        parts, cur, d = [], [], 0
        for x in header:
            if x["t"] == "op" and x["v"] in "([{":
                d += 1
            if x["t"] == "op" and x["v"] in ")]}":
                d -= 1
            if d == 0 and x["t"] == "op" and x["v"] == ";":
                parts.append(cur)
                cur = []
            else:
                cur.append(x)
        parts.append(cur)
        if len(parts) != 3 or not parts[1]:
            continue
        init, cond, upd = parts
        if any(x["t"] == "op" and x["v"] == "," for x in upd):
            continue
        bopen = close + 1
        bclose = _match(toks, bopen)
        if bclose is None:
            continue
        body = toks[bopen + 1:bclose]
        if any(x["t"] == "id" and x["v"] in ("continue", "function") or x["t"] == "op" and x["v"] in ("=>", "->")
               for x in body):
            continue
        # names declared in init must not be used after the loop (scope would widen)
        init_names = set()
        if init:
            if lang == "js" and init[0]["v"] in ("let", "var", "const"):
                init_names = {x["v"] for j, x in enumerate(init) if x["t"] == "id" and j > 0 and
                              init[j - 1]["v"] in ("let", "var", "const", ",")}
            elif lang == "java" and len(init) >= 2 and init[1]["t"] == "id":
                init_names = {x["v"] for j, x in enumerate(init) if x["t"] == "id" and j + 1 < len(init)
                              and init[j + 1]["v"] in ("=",)}
        # end of the enclosing block
        dd, enc_end = 0, len(toks)
        for j in range(bclose + 1, len(toks)):
            if toks[j]["v"] == "{" and toks[j]["t"] == "op":
                dd += 1
            elif toks[j]["v"] == "}" and toks[j]["t"] == "op":
                if dd == 0:
                    enc_end = j
                    break
                dd -= 1
        if any(x["t"] == "id" and x["v"] in init_names for x in toks[bclose + 1:enc_end]):
            continue
        line_start = src.rfind("\n", 0, t["s"]) + 1
        ind = src[line_start:t["s"]]
        if ind.strip():
            continue
        brace_ls = src.rfind("\n", 0, toks[bclose]["s"]) + 1
        if src[brace_ls:toks[bclose]["s"]].strip():
            continue  # closing brace not on its own line
        after_open = src[toks[bopen]["e"]:src.find("\n", toks[bopen]["e"]) if "\n" in src[toks[bopen]["e"]:] else len(src)]
        if after_open.strip():
            continue
        body_text = src[toks[bopen]["e"]:brace_ls]
        first_body = next((ln for ln in body_text.split("\n") if ln.strip()), None)
        inner = re.match(r"[ \t]*", first_body).group(0) if first_body else ind + "    "
        init_src = src[init[0]["s"]:init[-1]["e"]] if init else ""
        cond_src = src[cond[0]["s"]:cond[-1]["e"]]
        upd_src = src[upd[0]["s"]:upd[-1]["e"]] if upd else ""
        new = ""
        if init_src:
            new += f"{init_src};\n{ind}"
        new += f"while ({cond_src}) {{" + body_text
        if upd_src:
            new += f"{inner}{upd_src};\n"
        new += f"{ind}}}"
        cand = src[:t["s"]] + new + src[toks[bclose]["e"]:]
        out.append(cand)
    return out


def c_variants(src: str, test: str, lang: str) -> dict[str, list[str]]:
    reserved = JS_RESERVED if lang == "js" else JAVA_RESERVED
    taken = taken_names(src, test) | reserved
    cands = c_local_candidates(src, test, lang)
    mapping = {}
    for n in cands:
        mapping[n] = fresh_name(n, taken | set(mapping.values()))
    out = {k: [] for k in KINDS}
    for n in cands:
        v = c_rename(src, lang, {n: mapping[n]})
        if v != src:
            out["rename1"].append(v)
    if len(cands) >= 2:
        out["renameall"].append(c_rename(src, lang, mapping))
    out["cmpswap"] = c_cmpswaps(src, lang)
    out["stmtswap"] = c_stmtswaps(src, lang)
    out["for2while"] = c_for2while(src, lang)
    return out


# --------------------------------------------------------------------------- driver


def build(lang: str) -> pd.DataFrame:
    df = load_language(lang)
    docs, _ = build_corpus(lang)
    tests = dict(zip(df["problem"], df["test"]))
    correct = docs[docs.variant == "correct"].set_index("problem")["code"]
    rows = []
    for p, src in correct.items():
        try:
            vs = python_variants(src, tests[p]) if lang in ("python", "classeval") else c_variants(src, tests[p], lang)
        except SyntaxError as e:
            print(f"[{lang} {p}] syntax error: {e}")
            continue
        seen = {src}
        for kind in KINDS:
            n = 0
            for v in vs[kind]:
                if v in seen or n >= MAX_PER_KIND[kind]:
                    continue
                seen.add(v)
                rows.append({"lang": lang, "problem": int(p), "kind": kind, "idx": n, "code": v})
                n += 1
    out = pd.DataFrame(rows)
    res = run_many([(lang, r.code, tests[r.problem]) for r in out.itertuples()], workers=8)
    out["passed"] = [r["passed"] for r in res]
    out["status"] = [r["status"] for r in res]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="all", choices=LANGS + ["all"])
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    langs = LANGS if args.lang == "all" else [args.lang]
    frames = []
    for lang in langs:
        f = build(lang)
        frames.append(f)
        print(lang, f.groupby("kind")["passed"].agg(["sum", "count"]).to_dict())
    allv = pd.concat(frames, ignore_index=True)
    path = os.path.join(OUT_DIR, "variants.parquet" if args.lang == "all" else f"variants_{args.lang}.parquet")
    allv.to_parquet(path)
    counts = {}
    for lang, g in allv.groupby("lang"):
        counts[lang] = {}
        for kind in KINDS:
            gk = g[g.kind == kind]
            counts[lang][kind] = {"generated": int(len(gk)), "passed": int(gk.passed.sum()),
                                  "problems_with_valid": int(gk[gk.passed].problem.nunique())}
        counts[lang]["any"] = {"generated": int(len(g)), "passed": int(g.passed.sum()),
                               "problems_with_valid": int(g[g.passed].problem.nunique())}
    if args.lang == "all":
        with open(os.path.join(OUT_DIR, "variant_counts.json"), "w") as fh:
            json.dump(counts, fh, indent=2)
    print(json.dumps(counts, indent=2))


if __name__ == "__main__":
    main()
