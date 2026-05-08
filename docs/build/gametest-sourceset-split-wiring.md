---
title: (Retired) Gametest source-set split wiring
tags: [retired]
summary: Documented the testBridge source set, which has been merged into main.
---

The `testBridge` source set was merged into `main` on 2026-05-07; see
`docs/superpowers/plans/2026-05-07-plan-a-promote-testing-to-main.md` for
the migration. Kotest is now a `main` `implementation` dependency, so all
source sets pick it up transitively.
