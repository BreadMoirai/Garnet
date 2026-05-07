---
title: Mixin must target the declaring class for inherited methods
tags: [mixins, mc-api, gotchas]
summary: Inject into the class that declares the method (e.g. Level), not a subclass (ServerLevel); use instanceof inside the inject body to scope behavior.
---

# Mixin must target the declaring class for inherited methods

When writing a `@Mixin` that injects into a method declared on a *superclass*, the
target class must be that superclass. Targeting a subclass that merely *inherits*
the method causes Mixin to fail at apply time with `InvalidInjectionException`,
because the method bytecode does not exist on the subclass.

## The rule

- The `@Mixin(...)` target must be the class where the method is **declared**.
- If you only want behavior on a specific subclass, use a runtime `instanceof` check
  inside the inject body.

## Concrete example in this repo

We record block changes via `Level.setBlock(BlockPos, BlockState, int)`. The method
is declared on `Level`, not `ServerLevel` — so the recorder mixin targets
`Level.class` and guards with `instanceof ServerLevel`:

`src/main/java/com/breadmoirai/redstonespecs/mixin/ServerLevelSetBlockMixin.java`:

```java
@Mixin(Level.class)
abstract class ServerLevelSetBlockMixin {
    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void redstonespecs$captureBeforeState(...) {
        if (!(((Object) this) instanceof ServerLevel)) {
            BEFORE_STATE_STACK.get().push(SKIP_SENTINEL);
            return;
        }
        // ... real recording logic
    }
}
```

A sibling mixin that targets a method declared on `ServerLevel` itself
(`ServerLevelPhaseMixin`) correctly uses `@Mixin(ServerLevel.class)` — the rule
is "declaring class", not "always pick the base class".

## Why the failure mode is sneaky

The compiler accepts `@Mixin(ServerLevel.class)` with `method = "setBlock(...)"`
without complaint: from Java's perspective `ServerLevel.setBlock` *does* resolve.
Mixin's apply step, however, scans the *bytecode* of the target class for the
named method descriptor and fails because `setBlock` only exists on `Level`.

## Heuristic when adding a new mixin

1. Find the method in the decompiled MC source (see `mc-source-jars.md`).
2. The class containing the literal method body is your `@Mixin` target.
3. If you need a narrower scope, branch with `instanceof` or a property check
   inside the injector — never by changing the mixin target.
