#!/usr/bin/env python3
"""bench/track/foresttree.py - write generated tables as a tree of the journal (journal/, a Forester forest).

The notes of the project live in the journal (root AGENTS.md); a table that a tool generates is emitted
as a tree, not as a Markdown file. The generators of bench/track build their text as a list of Markdown
lines in a small, fixed subset; this module turns that list into Forester markup (journal/AGENTS.md,
"Markup"):

    # Title                 the title of the tree (the first one; the frontmatter's \\title)
    ## Heading              a section: \\subtree{\\title{Heading} ...} up to the next heading of that depth
    ### Heading             a section inside a section
    | a | b |               a table: \\table{\\tr{\\th{a}\\th{b}} \\tr{\\td{..}..}} (the |---| line is dropped)
    - item                  \\ul{\\li{..}}; nested by indentation; continuation lines belong to their item
    ```                     a code block: \\pre{\\verb~|..~}
    anything else           paragraphs: \\p{..}, one per block of lines, joined into one line
    inline                  `code` -> \\code{\\verb~|code~}, **strong**, *emphasis*, [text](target): a URL
                            becomes an html:a, a path of the repository \\src{path}, a tree address a link
    <!-- ... -->            dropped
    %%tree: MARKUP          a line of Forester markup for the tree alone, emitted as it is (the generators
                            use it for \\transclude{address}); a Markdown output drops the line

Every character that is syntax in Forester (% \\ { } [ ] # and an unbalanced parenthesis) is escaped or
put into a verbatim span. Python stdlib only; nothing here reads or writes the journal by itself.

    foresttree.py --selftest          converts a sample and checks its shape
    foresttree.py FILE.md ADDRESS --author A [--contributor C] [--taxon Insight] [--tag t]...   > ADDRESS.tree
"""

from __future__ import annotations

import datetime
import re
import sys

_HERALDS = ["~", "!!", "@@@", "<<>>", "~!~!~", "@~@~@~@"]
_SYNTAX = set("\\{}[]#")
_REPO_PATH = re.compile(r"(?:docs|bench|scripts|spikes|profiles|journal|rlvm-[a-z]+|\.github)/[A-Za-z0-9_./-]+\.[A-Za-z0-9]+|README\.md|LICENSE|AGENTS\.md|pom\.xml|env\.sh")


def verb(body: str) -> str:
    """A verbatim span whose terminator does not occur in the body."""
    for herald in _HERALDS:
        if herald not in body and not (body + herald).startswith(herald):
            return f"\\verb{herald}|{body}{herald}"
    raise ValueError("no usable verbatim herald for: " + body[:40])


def _unbalanced(s: str) -> set:
    """Positions of the parentheses of s that have no partner, code spans not counted."""
    bad, stack, in_code = set(), [], False
    for i, ch in enumerate(s):
        if ch == "`":
            in_code = not in_code
        elif in_code:
            continue
        elif ch == "(":
            stack.append(i)
        elif ch == ")":
            if stack:
                stack.pop()
            else:
                bad.add(i)
    bad.update(stack)
    return bad


def _text(s: str, offset: int = 0, bad: frozenset | set = frozenset()) -> str:
    """Prose: syntax characters and unbalanced parentheses (positions offset + i in bad) go into
    verbatim spans, % is escaped."""
    out, run = [], []

    def flush():
        if run:
            out.append(verb("".join(run)))
            run.clear()

    for i, ch in enumerate(s):
        if ch in _SYNTAX or (offset + i) in bad:
            run.append(ch)
            continue
        flush()
        out.append("\\%" if ch == "%" else ch)
    flush()
    return "".join(out)


_INLINE = re.compile(r"`([^`]+)`|\*\*(.+?)\*\*|(?<![\w*])\*(?!\s)([^*]+?)(?<!\s)\*(?![\w*])|\[([^\]]+)\]\(([^)\s]+)\)")


def inline(s: str) -> str:
    """One line of Markdown inline text as Forester markup. Parentheses must balance within a
    paragraph: one without a partner (over the whole line, whatever markup stands between) is verbatim."""
    bad = _unbalanced(s)
    out, pos = [], 0
    for m in _INLINE.finditer(s):
        out.append(_text(s[pos:m.start()], pos, bad))
        code, strong, em, label, target = m.groups()
        if code is not None:
            out.append("\\src{" + code + "}" if _REPO_PATH.fullmatch(code) else "\\code{" + verb(code) + "}")
        elif strong is not None:
            out.append("\\strong{" + inline(strong) + "}")
        elif em is not None:
            out.append("\\em{" + inline(em) + "}")
        elif re.match(r"[a-z][a-z0-9+.-]*://", target):
            out.append("\\<html:a>[href]{" + verb(target) + "}{" + inline(label) + "}")
        elif _REPO_PATH.fullmatch(target):
            out.append("\\src{" + target + "}")
        elif re.fullmatch(r"[A-Za-z0-9-]+", target):
            out.append(f"[{inline(label)}]({target})")
        else:
            out.append(inline(label) + " (" + "\\code{" + verb(target) + "}" + ")")
        pos = m.end()
    out.append(_text(s[pos:], pos, bad))
    return "".join(out)


def _cells(line: str) -> list[str]:
    """The cells of a Markdown table row; a | inside `code` does not split."""
    cells, cur, in_code = [], [], False
    body = line.strip()
    body = body[1:] if body.startswith("|") else body
    body = body[:-1] if body.endswith("|") else body
    for ch in body:
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            cells.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    cells.append("".join(cur).strip())
    return cells


def _list(items: list[tuple[int, str]]) -> list[str]:
    """(indent, text) items in order -> nested \\ul."""
    out = ["\\ul{"]
    base = items[0][0]
    i = 0
    while i < len(items):
        indent, text = items[i]
        j = i + 1
        while j < len(items) and items[j][0] > base:
            j += 1
        nested = _list(items[i + 1:j]) if j > i + 1 else []
        out.append("\\li{" + inline(text) + ("" if not nested else "\n" + "\n".join(nested) + "\n") + "}")
        i = j
    out.append("}")
    return out


def body(lines: list[str]) -> tuple[str | None, list[str]]:
    """Markdown lines -> (title of the first '# ' heading, Forester lines of the body)."""
    lines = [part for line in lines for part in line.split("\n")]   # an element may hold several lines
    title = None
    out: list[str] = []
    open_sections: list[int] = []   # heading depths of the \subtree blocks that are open
    i = 0

    def close(depth: int):
        while open_sections and open_sections[-1] >= depth:
            open_sections.pop()
            out.append("}")

    while i < len(lines):
        line = lines[i].rstrip("\n")
        stripped = line.strip()
        if not stripped:
            i += 1
            continue
        if stripped.startswith("%%tree:"):
            out.append(stripped[len("%%tree:"):].strip())
            i += 1
            continue
        if stripped.startswith("<!--"):
            while i < len(lines) and "-->" not in lines[i]:
                i += 1
            i += 1
            continue
        m = re.match(r"(#{1,6})\s+(.*)$", stripped)
        if m:
            depth = len(m.group(1))
            if depth == 1 and title is None:
                title = m.group(2).strip()
            else:
                close(depth)
                out.append("\\subtree{\\title{" + inline(m.group(2).strip()) + "}")
                open_sections.append(depth)
            i += 1
            continue
        if stripped.startswith("```"):
            code = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i].rstrip("\n"))
                i += 1
            i += 1
            out.append("\\pre{" + verb("\n" + "\n".join(code) + "\n") + "}")
            continue
        if stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(_cells(lines[i]))
                i += 1
            head = True
            out.append("\\table{")
            for row in rows:
                if all(re.fullmatch(r":?-{2,}:?", c) for c in row):
                    continue
                tag = "th" if head else "td"
                out.append("\\tr{" + "".join(f"\\{tag}{{{inline(c)}}}" for c in row) + "}")
                head = False
            out.append("}")
            continue
        if re.match(r"\s*[-*]\s+", line):
            items: list[tuple[int, str]] = []
            while i < len(lines) and lines[i].strip() and not lines[i].strip().startswith(("|", "#", "```", "<!--")):
                raw = lines[i].rstrip("\n")
                m2 = re.match(r"(\s*)[-*]\s+(.*)$", raw)
                if m2:
                    items.append((len(m2.group(1)), m2.group(2).strip()))
                elif items:
                    items[-1] = (items[-1][0], items[-1][1] + " " + raw.strip())   # a continuation line
                i += 1
            out += _list(items)
            continue
        para = [stripped]   # this line is none of the blocks above: it starts a paragraph (always progress)
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r"\s*([-*]\s+|\||#{1,6}\s|```|<!--)", lines[i]):
            para.append(lines[i].strip())
            i += 1
        out.append("\\p{" + inline(" ".join(para)) + "}")
    close(0)
    return title, out


def tree(lines: list[str], *, author: str, contributor: str | None = None, taxon: str = "Insight", date: str | None = None,
         tags: tuple[str, ...] = (), meta: tuple[tuple[str, str], ...] = (), title: str | None = None) -> str:
    """The whole tree: the journal's frontmatter (journal/AGENTS.md) and the converted body."""
    found, converted = body(lines)
    head = ["\\title{" + inline(title or found or "Generated tables") + "}", f"\\taxon{{{taxon}}}",
            f"\\date{{{date or datetime.date.today().isoformat()}}}", f"\\author{{{author}}}"]
    if contributor:
        head.append(f"\\contributor{{{contributor}}}")
    head += [f"\\tag{{{t}}}" for t in tags]
    head += [f"\\meta{{{k}}}{{{v}}}" for k, v in meta]
    head += ["\\import{base-macros}", ""]
    return "\n".join(head + converted) + "\n"


SAMPLE = """# Sample tables

Generated by `tool.py` on 2026-11-25; 50 % of [the text](https://example.org/a#b) is **strong (very)** or *emphasis*.
A second line of the same paragraph: see `docs/BENCHMARKS.md` and a lone ) parenthesis.

## 1. Wall clock (s)

| Checker | init (348 MB) | `a|b` |
|---|--:|--:|
| **default** | **0.62** (0.61) | - |
| *ratio* | 0.87x | [x] |

- first item with `code` and
  a continuation line
  - nested item
- second item

```
rlvm --version   # {braces}
```
"""


def selftest() -> int:
    text = tree(SAMPLE.splitlines(), author="tester", contributor="someone", tags=("benchmark",), date="2026-11-24")
    checks = [
        ("title from the first heading", "\\title{Sample tables}" in text),
        ("frontmatter", "\\taxon{Insight}\n\\date{2026-11-24}\n\\author{tester}\n\\contributor{someone}\n\\tag{benchmark}\n\\import{base-macros}" in text),
        ("paragraph joined, percent escaped", "50 \\% of" in text and "A second line of the same paragraph" in text.split("\\p{")[1]),
        ("url link", "\\<html:a>[href]{\\verb~|https://example.org/a#b~}{the text}" in text),
        ("strong and emphasis", "\\strong{strong (very)}" in text and "\\em{emphasis}" in text),
        ("repository path", "\\src{docs/BENCHMARKS.md}" in text),
        ("lone parenthesis is verbatim", "a lone \\verb~|)~ parenthesis" in text),
        ("section", "\\subtree{\\title{1. Wall clock (s)}" in text and text.rstrip().endswith("}")),
        ("table head", "\\tr{\\th{Checker}\\th{init (348 MB)}\\th{\\code{\\verb~|a|b~}}}" in text),
        ("table row", "\\tr{\\td{\\strong{default}}\\td{\\strong{0.62} (0.61)}\\td{-}}" in text),
        ("brackets are verbatim", "\\td{\\verb~|[~x\\verb~|]~}" in text),
        ("no alignment row", "---" not in text),
        ("list with continuation and nesting", "\\li{first item with \\code{\\verb~|code~} and a continuation line\n\\ul{\n\\li{nested item}\n}\n}" in text),
        ("code block", "\\pre{\\verb~|\nrlvm --version   # {braces}\n~}" in text),
        ("a raw line for the tree", "\n\\transclude{some-notes}\n" in tree(["%%tree: \\transclude{some-notes}", "", "text"], author="t")),
    ]
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("ok   " if ok else "FAIL ") + name)
    if failed:
        print(text)
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    if len(argv) >= 2 and argv[1] == "--selftest":
        return selftest()
    import argparse
    parser = argparse.ArgumentParser(prog="bench/track/foresttree.py", description="Markdown subset -> a tree of the journal (stdout).")
    parser.add_argument("markdown")
    parser.add_argument("address", help="the address of the tree (its file is ADDRESS.tree; only used in the generated-by line)")
    parser.add_argument("--author", required=True, help="address of the author's identity tree (journal/trees/people/)")
    parser.add_argument("--contributor")
    parser.add_argument("--taxon", default="Insight")
    parser.add_argument("--date", help="creation date, YYYY-MM-DD (default: today, local time)")
    parser.add_argument("--tag", action="append", default=[])
    args = parser.parse_args(argv[1:])
    with open(args.markdown) as handle:
        lines = handle.read().splitlines()
    sys.stdout.write(tree(lines, author=args.author, contributor=args.contributor, taxon=args.taxon, date=args.date, tags=tuple(args.tag)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
