---
title: Docs semantic search (qmd)
tags: [tooling, docs, search, hooks, claude, qmd]
summary: How docs/ is indexed by qmd, how the reindex hooks fire, and how to rebuild the index from scratch.
---

# Docs semantic search (qmd)

`docs/` is indexed by [qmd](https://github.com/tobi/qmd) — a local-first hybrid search engine
(BM25 + vector embeddings + optional LLM reranking) that runs entirely on-device. CLAUDE.md's
"How to search for information" now points at it, and the `docs-search` skill in
`.claude/skills/docs-search/` documents query usage. This article covers the *setup*: what is
indexed, how it stays fresh, and how to rebuild it.

## What is indexed

One collection, **`redstonespecs-docs`**, rooted at `docs/` with the glob:

```
!(superpowers)/**/*.md
```

49 live articles. `src/` is never indexed — semantic search over Kotlin duplicates what Grep
already does well, and would swamp the corpus.

**`docs/superpowers/` is excluded deliberately.** Those 53 specs and plans are commit-time
snapshots; CLAUDE.md already calls them historical artifacts. Indexed, they would have been 53 of
95 files — a majority of hits pointing at decisions that may since have been reversed.

The root `CLAUDE.md` is also not indexed, for a simpler reason: it is loaded into every session
verbatim, so retrieving it would return content that is already in context.

## Why the category is `tooling/` and not `build/`

qmd hardcodes an exclude list and offers no way to override it — `dist/store.js`, in
`reindexCollection`:

```js
const excludeDirs = ["node_modules", ".git", ".cache", "vendor", "dist", "build"];
const allIgnore = [
    ...excludeDirs.map(d => `**/${d}/**`),
    ...(options?.ignorePatterns || []),
];
```

`ignorePatterns` only *appends*, so `**/build/**` always wins. While this category was named
`docs/build/`, its articles were silently absent from every search result — the index reported
success and simply never saw them. Renaming the directory is the only fix.

This is worth remembering beyond qmd: **a directory named `build`, `dist`, `vendor`, or `cache`
anywhere under `docs/` will be invisible to this and most other indexers.** Do not create one.

## Freshness: two hooks, one script

`.claude/hooks/qmd-reindex.sh` is wired to two events in `.claude/settings.json`:

| Event | Matcher | Role |
|---|---|---|
| `PostToolUse` | `Write\|Edit` | Primary. Filtered to `docs/**/*.md`, so an article is searchable seconds after it's written. |
| `SessionStart` | — | Catch-up. Sees edits the write hook structurally cannot: `git pull`, rebases, branch switches, IDE edits. |

Both entries set `"async": true`, which is what keeps a reindex off the turn's critical path. A
synchronous hook here would stall every file write behind an embedding run.

### Non-obvious details in the script

These cost time to discover and are invisible in the code itself:

- **`jq` is not installed on this machine**; the script parses its stdin payload with `python3`.
  Most hook examples in the wild use `jq` — one written that way fails silently, and the index
  quietly goes stale with no error anywhere.
- **Hooks run non-interactively, so `~/.zshrc` is never sourced.** qmd lives in the Node install
  directory and is simply absent from a hook's `PATH`. The script prepends the likely install
  dirs itself before probing for the binary.
- **Node's bin is prepended last, so it lands first in `PATH`.** A stray qmd from another package
  manager must not win: a shadowing install whose native SQLite addon was never built fails at
  runtime with a stack trace rather than being skipped cleanly.
- **`flock -n` is deliberately non-blocking.** Writing several docs in quick succession would
  otherwise start overlapping reindexes racing on the same SQLite database. An overlapping run
  *skips* rather than queues, because the in-flight run rescans by mtime and picks up the newer
  file anyway.
- **The script always exits 0**, and no-ops when `qmd` is absent. qmd is not a build dependency —
  a fresh clone works without it, and search degrades to the `INDEX.md`/grep fallback.
- **The settings watcher only watches directories that had a settings file when the session
  started.** After `.claude/settings.json` is first added, hooks may not fire until `/hooks` is
  opened once or the session restarts.

## Rebuilding from scratch

qmd needs Node ≥22 or Bun ≥1.0. On this machine the only Node was a Windows mise install whose
shims fail under WSL (`exec: mise: not found`), so a WSL-local Node was installed to
`~/.local/lib/node-v24.18.0-linux-x64/` and put on `PATH` in `~/.zshrc`.

Installing qmd under **Bun** does not work here: its postinstall scripts shell out to `node`, and
without a WSL Node they fail with exit 127, leaving a broken half-install whose `better-sqlite3`
native addon is missing. Use npm.

```bash
npm install -g @tobilu/qmd
cd /path/to/RedstoneSpecs
qmd init
qmd collection add ./docs --name redstonespecs-docs
qmd context add ./docs "<one-paragraph description of the project>"
qmd update && qmd embed
```

Then edit `.qmd/index.yml` to set the collection pattern to `!(superpowers)/**/*.md`.

First `qmd embed` downloads ~333 MB of embedding model into `~/.cache/qmd/models/`; the reranking
and query-expansion models (another ~1.7 GB) download on first `qmd query`.

`.qmd/index.yml` is committed. The SQLite database, lock, and log are gitignored — they rebuild in
about 90 seconds.

## Performance, and why `--no-rerank` is the default

This machine has **no GPU acceleration**; all three models run on CPU. Measured on this corpus:

| Command | Time |
|---|---|
| `qmd search` (BM25) | 0.2s |
| `qmd query --no-rerank` | 2.5–7.4s |
| `qmd vsearch` | 3–7s |
| `qmd query` (with rerank) | **70–90s** |

The reranker is the entire difference. **qmd caches results per query string**, so re-running an
identical query returns in ~3s — which makes the reranker look far cheaper than it is. Always
measure with a novel query.

One more trap: `qmd query` prints the `hyde:` expansion it invented before searching. The
expansion model knows nothing about this project and mangles domain jargon — asked about "dirty
structure sidecar" it expanded to *"unclean code"*, poisoning the query. When the printed
expansion is about the wrong subject, rerun with `qmd search` or `qmd vsearch`, which skip
expansion entirely.

## When search seems stale

1. `tail .qmd/reindex.log` — every hook run appends a timestamped header, so an empty or old log
   means the hook isn't firing (see the settings-watcher note above).
2. `qmd collection list` — check the file count and "Updated" age.
3. `qmd ls redstonespecs-docs` — confirm the specific article is actually in the index. A file
   present on disk but absent here means a glob or exclude-list problem, not an embedding problem.
