---
title: testBridge source set wiring (retired)
tags: [retired]
summary: Retired; testBridge was merged into main in Plan A on 2026-05-07.
---

# testBridge source set wiring — Retired

The `testBridge` source set was dissolved in Plan A (2026-05-07). Testing infrastructure
(Kotest runner, assertions, coroutines) is now declared in `main` directly; the separate
source set and its `afterEvaluate` wiring no longer exist.

The plan that removed it (`docs/superpowers/plans/2026-05-07-plan-a-promote-testing-to-main.md`)
was itself deleted in a later docs cleanup, so there is no article to link to; `git log -- docs/superpowers/plans/`
still has it if the original reasoning is ever needed. The current source-set layout is described in
[local-verification-commands.md](local-verification-commands.md#why-five-source-sets).
