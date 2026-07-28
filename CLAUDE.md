# CLAUDE.md

Project documentation lives in `docs/`. This file is the entry point: it indexes the **folder structure**, not individual articles. Each category has its own `INDEX.md` listing the articles inside.

## How to search for information

1. Skim the **Category index** below and pick the folder whose tags match your task.
2. Open that folder's `INDEX.md` to see article-level entries with one-line summaries.
3. If the topic spans multiple categories, check each candidate `INDEX.md` — articles are hyperspecialized and a topic may have entries in 2+ folders.
4. If nothing matches, `grep -ri "<keyword>" docs/` across all article bodies before assuming the doc doesn't exist.
5. Source code is the source of truth. Docs explain *why* / non-obvious *how*. Verify file:line citations against current code before relying on them — articles can drift.

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

Skip steps 1–5 only when the source change is purely internal (an unobservable refactor) AND no doc currently references the changed code. If unsure, default to checking.

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
