---
title: testBridge source set wiring (retired)
tags: [retired]
summary: Retired; testBridge was merged into main in Plan A on 2026-05-07.
---

# testBridge source set wiring — Retired

The `testBridge` source set was dissolved in Plan A (2026-05-07). Testing infrastructure
(Kotest runner, assertions, coroutines) is now declared in `main` directly; the separate
source set and its `afterEvaluate` wiring no longer exist.

See [docs/superpowers/plans/2026-05-07-plan-a-promote-testing-to-main.md](../superpowers/plans/2026-05-07-plan-a-promote-testing-to-main.md)
for the change that removed it.
