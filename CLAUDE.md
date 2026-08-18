# CLAUDE.md

Project documentation lives in `docs/`. This file is the entry point: it indexes the **folder structure**, not individual articles. Each category has its own `INDEX.md` listing the articles inside.

One rule here is not about docs at all: **[Fix what you find](#fix-what-you-find)** — anything broken you notice while working gets fixed in the same piece of work.

## How to search for information

`docs/` is indexed for semantic search by [qmd](https://github.com/tobi/qmd) as the collection `garnet-docs`. **Query it first** — don't hand-walk the folder table.

1. `qmd query "<your question>" -c garnet-docs -n 8 --no-rerank --format md`. The **docs-search** skill covers mode selection (`qmd search` for exact identifiers, `qmd query` for concepts) and why `--no-rerank` is the default.
2. `Read` the articles it names. Excerpts are for ranking; the file is what you cite.
3. **If qmd returns nothing** — or isn't installed, it's an optional local tool — fall back: pick the folder from the **Category index** below, open its `INDEX.md`, then `grep -ri "<keyword>" docs/`. Articles are hyperspecialized and a topic may have entries in 2+ folders.
4. Source code is the source of truth. Docs explain *why* / non-obvious *how*. Verify file:line citations against current code before relying on them — articles can drift.

`docs/superpowers/` is deliberately **not** indexed — those specs and plans are commit-time snapshots, not live docs. Read them by path when you want history.

## How to write docs

- **One article = one topic.** If a doc starts to cover two distinct subjects, split it. Long, multi-topic files defeat the index.
- **Place by primary concern.** Pick the single best category. Cross-reference from other categories with a one-line `INDEX.md` entry rather than duplicating content.
- **Keep the ratio balanced.** Aim for a similar article count across categories. If one folder is ballooning past ~2× another, look for sub-topics worth promoting to a sibling category (and update this file).
- **Article frontmatter** (top of every article):
  ```
  ---
  title: <short title>
  tags: [tag1, tag2, ...]
  summary: <one-line, used in INDEX.md>
  ---
  ```
- **Register every new article** in the category's `INDEX.md` as: `- [Title](filename.md) — summary` plus its tags.
- **Filename convention**: `kebab-case.md`, named after the topic (not the date or PR). No prefixes.

## Keep docs in sync with code

**After ANY source-code change, before claiming the task is complete, audit the docs.** This is a mandatory step — add it to the todo list at the start of any task that touches source code, and tick it off only when each sub-check has been done:

1. **New behavior, file, or public API?** Add or extend a `docs/<category>/<topic>.md` article. Register it in the category's `INDEX.md`.
2. **Renamed / moved / split a file?** Grep `docs/` for the old name and update every hit. `grep -rn "<OldName>" docs/` should return zero results that refer to the file's *old* role (existing-file mentions are fine).
3. **Removed an API, file, or behavior?** Find any article that documented it and either delete the obsolete section or update it to point at the replacement. Do not leave a dangling reference.
4. **Changed something a doc cites by file:line?** Either update the citation to the new location or convert it to a description that survives refactors.
5. **Cross-references stay valid:** `INDEX.md` titles, "See also" sections, and inline `[link](path.md)` references must all resolve to a real article with matching summary text.
6. **Superpowers `specs/` and `plans/` are historical artifacts.** Leave them as-is; treat them as commit-time snapshots, not living docs.

Newly written articles are indexed automatically on save — there is no manual reindex step, and running `qmd embed` by hand only contends for the reindex lock.

Skip steps 1–5 only when the source change is purely internal (an unobservable refactor) AND no doc currently references the changed code. If unsure, default to checking.

## Fix what you find

**Any issue you discover while working is an immediate must-fix in the same piece of work** — however you found it, whoever caused it, whatever it touches. If you find it, you fix it.

These are **not** valid reasons to defer, and none of them may be used to close out a task with the issue still present:

- "It was already broken."
- "My change didn't cause it."
- "It's pre-existing."
- "That's out of scope."
- "It's only a comment / doc / cosmetic."

A stale KDoc link, a lying filename, a dead import, a doc that describes an architecture the code abandoned — these count. Cheap to fix is a reason to fix it now, not a reason to rank it below the "real" work.

**If a fix genuinely cannot be done in the current change** — it needs a design decision only the user can make, or the fix is larger than the task that surfaced it — then say so **explicitly in your response to the user**: name what you are leaving broken, where it is, and why it cannot be done here. Silently deferring, quietly parking it, or burying it in a TODO comment is not permitted; an unreported issue is indistinguishable from an undiscovered one, and the next person pays for it twice.

The failure mode this prevents: known defects accumulate across changes because every individual change had a locally reasonable excuse to skip them, and the tree ends up with a documented architecture nobody follows and comments nobody trusts.

## How to save learnings

When you discover something non-obvious during development (an MC API quirk, a subtle invariant, a gotcha that cost time), capture it:

1. Decide if it extends an existing article (add a section) or warrants a new one (new file).
2. Save what's *non-obvious* — not what the code already shows. The bar: would a future reader, looking only at the code, miss this?
3. Include *why* it matters: the failure mode it prevents, the alternative that didn't work, or the constraint that forced the choice.
4. If it generalizes (a pattern, not a one-off), promote it from a buried section to its own article.
5. Always update the relevant `INDEX.md` so the learning is discoverable.

Don't save: code patterns the code itself shows, transient task state, recent-change summaries (use git log), or anything already in source comments.

## Category index

| Folder | Tags | What lives here |
|---|---|---|
| [architecture/](docs/architecture/INDEX.md) | design, data-model, lifecycle, modules | High-level system design, how the modules fit together, data flow, key invariants |
| [ui/](docs/ui/INDEX.md) | screens, widgets, rendering, layout, input | Client-side editor/runner screens, custom widgets, MC GUI integration quirks |
| [runner/](docs/runner/INDEX.md) | execution, conditions, verification, finalization | Spec execution engine: condition evaluation, output verification, recording finalization |
| [persistence/](docs/persistence/INDEX.md) | storage, networking, payloads, serialization | How specs are saved/loaded, C2S/S2C payloads, on-disk formats |
| [gametest/](docs/gametest/INDEX.md) | testing, fixtures, helpers, harness | Game-test infrastructure, test contexts, conventions for new tests |
| [minecraft/](docs/minecraft/INDEX.md) | mc-api, mixins, versions, quirks | MC source patterns, mixin gotchas, render-state API, version-specific behavior |
| [tooling/](docs/tooling/INDEX.md) | gradle, stonecutter, fabric, deps, tooling | Multi-version build setup, Stonecutter task paths, dependency choices, docs-search index |
| [use-cases/](docs/use-cases/INDEX.md) | use-cases, scenarios, coverage, audit, test-matrix | User journeys decomposed into system interactions, with a per-journey test-coverage audit |
