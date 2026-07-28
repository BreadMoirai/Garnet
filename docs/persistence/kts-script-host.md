---
title: .spec.kts script host
tags: [persistence, scripting, dsl]
summary: How the kotlin-scripting host loads .spec.kts files; why a custom host (vs JSR-223); file contract; threat model.
---

# .spec.kts Script Host

`GarnetSpec` is authored as `.spec.kts` files in the world directory and
loaded at runtime by `KtsSpecLoader` (in `persistence/`). Files are evaluated
by a custom `BasicJvmScriptingHost` configured via `SpecScriptCompilationConfig`.

## Why a custom host (vs JSR-223)

- Pre-imports the DSL (`com.breadmoirai.garnet.dsl.*`) so script
  authors don't need import lines.
- Better error reporting: diagnostics flow through `ResultWithDiagnostics`,
  not buried in `ScriptException`.
- Tighter control over the classpath surface (currently
  `dependenciesFromClassContext(GarnetSpec::class, wholeClasspath = true)`;
  can be narrowed later if sandboxing is needed).

## Classloader pinning

The script host's evaluation config pins `baseClassLoader` to
`GarnetSpec::class.java.classLoader`. Without this pin the script's
GarnetSpec can come from the system classloader while the host's
GarnetSpec comes from Fabric's mod ("knot") classloader, and the cast
`rv.value as GarnetSpec` fails at runtime even though the class file
is identical. JVM treats two classes loaded by different loaders as
distinct types.

## File contract

Every `.spec.kts` file MUST evaluate, as its last expression, to a
`GarnetSpec`. The standard form is:

```kotlin
garnetSpec("my_id") {
    bounds(5, 4, 5)
    lifespan = 20
    structure = "garnet:my_id"
    input(...) { ... }
    output(...) { ... }
}
```

Errors:
- If the last expression is `Unit` (e.g., the script forgot `garnetSpec(...)`),
  loading fails with "script must end with garnetSpec(...) expression".
- If compilation fails, all diagnostics are joined into the exception message.

## Threat model

`.spec.kts` files come from the user's own world directory — same trust
boundary as any other file the user saves. We do NOT sandbox arbitrary
JVM access. If a future change wants to load specs from untrusted sources
(e.g., shared maps), the classpath surface should be narrowed to expose
only the DSL package.

## Cost

The kotlin-scripting JVM host adds ~30–50 MB to the final jar (JIJ).
First-load latency for compiling a single `.spec.kts` is 1–3s on warmup,
then sub-100ms once cached.
