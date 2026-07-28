---
name: docs-search
description: Use when answering any question about this mod's own behavior, architecture, conventions, or history — "how does X work", "where is Y handled", "why was Z done this way" — and before writing or editing anything under docs/. Searches the indexed docs/ corpus semantically instead of guessing at filenames or grepping.
---

# Searching the docs

`docs/` is indexed by [qmd](https://github.com/tobi/qmd) as the collection **`redstonespecs-docs`**
(49 live articles). Query it before grepping — articles are hyperspecialized and often describe a
concept in different words than the question uses, which is exactly what lexical search misses.

`docs/superpowers/` is **not** indexed. Those specs and plans are commit-time snapshots; searching
them surfaces decisions that may since have been reversed. Read them by path when you want history.

## Default query

```bash
qmd query "<the user's question, in natural language>" -c redstonespecs-docs -n 8 --no-rerank --format md
```

Then **`Read` the files it names.** qmd returns excerpts; the article is the source of truth and the
code is the source of truth above that. Cite `file:line` from the real file, never from an excerpt —
and per CLAUDE.md, verify a doc's own `file:line` citations against current code before relying on
them, since articles drift.

## Which mode

Measured on this corpus, this machine (WSL2, **CPU-only — no GPU acceleration**):

| Command | What it does | Time | Use for |
|---|---|---|---|
| `qmd search` | BM25 keyword | **0.2s** | Exact identifiers: `SpecBlockEntity`, `DiodeBlock.FACING`, `ClientSpec` |
| `qmd query --no-rerank` | Expansion, no rerank | **2.5–7.4s** | **The default.** Natural-language questions |
| `qmd vsearch` | Vector only | **3–7s** | Conceptual questions when you want no query expansion at all |
| `qmd query` | Expansion + rerank | **70–90s** | Rarely. Only when precision matters more than 90 seconds do |

**`--no-rerank` is the default for a reason.** The reranker is a 0.6B model running on CPU and it
costs 70–90 seconds on a novel query — an order of magnitude more than the search itself. Repeating
an identical query is fast (~3s) because qmd caches per query string, so a fast second run does not
mean the reranker is cheap.

Rules of thumb:

- Searching for a **symbol you can spell exactly**? `qmd search`. BM25 beats embeddings at this,
  and it is 300× faster than the reranking path.
- Searching for a **concept**? `qmd query --no-rerank`.
- Add `--intent "<what a good answer looks like>"` to sharpen a vague question.
- `--format md` for reading, `--format files` when you only want paths to feed into `Read`.

**Watch the expansion line.** `qmd query` prints the `hyde:` variant it invented before searching.
It is a general-purpose model with no knowledge of this project, so it mangles domain jargon —
asked about "dirty structure sidecar" it expanded to *"unclean code"*, poisoning the query. If the
printed expansion is about the wrong subject, rerun with `qmd search` or `qmd vsearch`, which skip
expansion entirely.

## When qmd finds nothing

Degrade, don't dead-end — fall back to the ladder in CLAUDE.md:

1. Open the category `INDEX.md` under the most likely `docs/` folder.
2. `grep -ri "<keyword>" docs/`.
3. Only then conclude the doc doesn't exist — and consider writing it, per CLAUDE.md's
   "How to save learnings".

## Freshness

The index reindexes automatically on every write to `docs/`, so an article is searchable within
seconds of being written. **Do not run `qmd update` or `qmd embed` by hand** — redundant runs just
contend for the reindex lock. If results look stale, check `.qmd/reindex.log`.
