# qmd as the docs retrieval layer — design

**Date:** 2026-07-27
**Status:** Approved for planning

## Goal

Make `docs/` **searchable by meaning, in every session, without being told to**. Today CLAUDE.md
asks the agent to hand-walk a folder table → `INDEX.md` → `grep -ri` ladder. That ladder is
skippable (the agent has to remember to climb it) and lexical (it misses articles that describe the
right concept in different words).

Integrating [qmd](https://github.com/tobi/qmd) — a local-first hybrid search engine for markdown —
replaces the ladder with a single query. qmd combines BM25 keyword search, vector embeddings, and
LLM reranking, all running on-device, so no doc content leaves the machine.

Two halves have to land together:

- **Retrieval:** a qmd index over `docs/`, reachable from a session.
- **Discoverability:** CLAUDE.md and a project skill that make querying it the *default* first move,
  so no reminder is needed.

Non-goals: an MCP server; indexing Kotlin source; reindexing on every turn regardless of whether
docs changed; replacing the `INDEX.md` files (they stay as the human-browsable table of contents
and the qmd fallback).

## Prerequisite: a WSL-side JS runtime

qmd requires **Node ≥22 or Bun ≥1.0**. This machine has neither *inside WSL* — the only Node
(v24.13.1) is a Windows mise install whose shims fail under this shell (`exec: mise: not found`).

Install **Bun**, not Node: one self-contained binary, no version manager to maintain, and qmd's
own docs support it as a first-class install path.

```sh
curl -fsSL https://bun.sh/install | bash
bun install -g @tobilu/qmd
```

First embedding run auto-downloads ~2 GB of GGUF models (embedding 300 MB, reranker 640 MB, query
expansion 1.1 GB) into the user cache. This step is **manual and interactive** — it is the one
part the user runs in their own shell.

**Gate:** before any wiring, prove the install with a real query returning real hits. If Bun or the
model download fails, the whole design stops here rather than half-landing.

## The collection

Project-local index, created at the repo root:

```sh
qmd init                                     # creates ./.qmd/index.yml
qmd collection add . --name redstonespecs-docs
```

Scoped to `docs/**/*.md` plus the root `CLAUDE.md`. Project-local (`.qmd/`) rather than
`~/.config/qmd/index.yml` so the configuration travels with the repo and a fresh clone can rebuild
the index with one command.

The collection gets a **context description** — qmd feeds it into relevance scoring:

> Minecraft Fabric mod (Kotlin, Stonecutter multi-version). Internals documentation: architecture
> and data model, client editor/runner UI, spec execution engine, persistence and network payloads,
> gametest harness, MC API quirks and mixins, Gradle build, and user-journey coverage audits.

Corpus size today: ~100 articles, 1.7 MB. Small enough that a full re-index and re-embed is cheap,
which is what makes the once-per-session refresh in the next section viable.

## Access: CLI through a project skill

No MCP server. qmd is invoked as a plain command via Bash, and a **project skill** at
`.claude/skills/docs-search/SKILL.md` tells the agent when and how.

Rejected alternative: the MCP server. It would put query tools in the tool list natively, but it
costs a stdio process per session and its schemas consume context on every turn — for a tool used a
handful of times per session, a skill that loads on demand is the better trade.

The skill's `description` is the trigger surface. It must fire on questions about *this codebase's*
behavior, architecture, and conventions — the cases where an article probably exists — e.g. "how
does X work", "where is Y handled", "why was Z done this way", plus any task that will touch
`docs/`.

Body content:

1. **The query.** `qmd query "<question>" -c redstonespecs-docs -n 8 --format md`, then `Read` the
   top hits and cite `file:line` from the actual file, never from the qmd excerpt.
2. **Mode selection**, the non-obvious part:
   - `qmd search` — BM25, for exact identifiers: `SpecBlockEntity`, `DiodeBlock.FACING`, `ClientSpec`.
   - `qmd vsearch` — pure vector, for conceptual questions when the reranker is not worth its latency.
   - `qmd query` — expansion + rerank, the default for natural-language questions.
   - `--no-rerank` as the escape hatch when latency hurts.
3. **Fallback.** If qmd returns nothing above the score floor, fall back to the existing ladder:
   category `INDEX.md`, then `grep -ri`. A miss must degrade, not dead-end.
4. **Freshness note.** The index refreshes automatically on every write to `docs/`, so articles are
   searchable within seconds of being written. No manual reindex step, and none should be added.

**Latency is measured, not assumed.** During implementation, benchmark all three modes (and
`--no-rerank`) against this corpus on this machine, and write the observed timings into the skill.
Guidance about which mode to reach for is only useful if the numbers behind it are real. CPU-only
inference under WSL2 is the risk: if `qmd query` proves unusably slow, the skill's documented
default changes to `vsearch`/`--no-rerank`, and the design still stands.

## Freshness: reindex on doc write

The index refreshes **when a doc is written**, so an article is searchable in the same session it
is created. Both triggers run one shared script, `.claude/hooks/qmd-reindex.sh`, registered in
`.claude/settings.json`:

**`PostToolUse` on `Write|Edit`** — the primary trigger. The hook receives the tool call as JSON on
stdin; the script reads `tool_input.file_path` and **exits immediately unless the path is a `.md`
file under `docs/`**. This filter is the whole point: a hook that reindexed after every source edit
would fire constantly for nothing.

**`SessionStart`** — the catch-up trigger, invoked with no path. Covers edits qmd never saw a tool
call for: `git pull`, branch switches, rebases, edits made in an IDE. The write hook structurally
cannot see those.

The script's three guards:

1. **Availability** — `command -v qmd >/dev/null 2>&1 || exit 0`. A machine without qmd installed
   exits cleanly, so the repo stays usable by anyone (or any agent) who skipped the manual install.
   Degradation is to plain grep, never an error on every write.
2. **Serialization** — `flock -n` on a lock file. Writing several docs in quick succession would
   otherwise start overlapping reindexes racing on the same SQLite database. Non-blocking, so a run
   that arrives while another holds the lock **skips** rather than queues; the run already in
   progress rescans the tree by mtime and picks up the newer file anyway.
3. **Detachment** — the reindex runs backgrounded with output to `.qmd/reindex.log`. It must never
   block the agent's next action, and a hook that stalls a turn is worse than a stale index.

Rejected alternative: a `Stop` hook reindexing after every turn. Docs change far less often than
turns end, so nearly every run would be a no-op scan — and it would still leave the index stale for
the remainder of the turn that wrote the doc.

## Discoverability: the CLAUDE.md rewrite

The skill alone is not enough — CLAUDE.md is what every session reads unconditionally. Its
**"How to search for information"** section is rewritten qmd-first:

1. Query the index (`qmd query ... -c redstonespecs-docs`).
2. `Read` the hits it names; source code remains the source of truth, and `file:line` citations in
   articles must still be verified against current code.
3. Only if qmd returns nothing: browse the category `INDEX.md` files, then `grep -ri` across
   `docs/`.

The **"Keep docs in sync with code"** section is left untouched. It is what keeps the corpus worth
indexing in the first place — a stale corpus searched well is still stale. It needs no reindex
instruction: the write hook handles that invisibly, and telling the agent to reindex by hand would
invite redundant runs that fight the lock.

The **"Category index"** table stays. It is the fallback path and the human-facing map.

## Git hygiene

| Path | Disposition |
|---|---|
| `.qmd/index.yml` | **Committed** — collection definition, rebuildable config |
| `.qmd/*.db`, `.qmd/reindex.log`, `.qmd/*.lock` | **Ignored** — machine-local build artifacts, regenerated in seconds |
| `.claude/skills/docs-search/SKILL.md` | **Committed** |
| `.claude/hooks/qmd-reindex.sh` | **Committed** — executable bit included |
| `.claude/settings.json` (hook) | **Committed** — shared project setting, distinct from the existing gitignored `settings.local.json` |

## Verification

The work is done when, from a **fresh** session with no prompting about docs:

1. A natural-language question about mod internals results in a qmd query being run before any grep.
2. The query returns a relevant article, and the answer cites `file:line` from the real file.
3. A question whose answer is *not* in `docs/` degrades to the `INDEX.md`/grep fallback instead of
   stalling or fabricating.
4. Writing a brand-new article to `docs/` makes it findable by `qmd query` **in the same session**,
   with no manual reindex.
5. Editing a source file under `src/` triggers **no** reindex — the path filter holds.
6. `git status` is clean of `.qmd` database, lock, and log artifacts.
7. Temporarily renaming `qmd` off `PATH` leaves both hooks silent and the repo fully usable.
