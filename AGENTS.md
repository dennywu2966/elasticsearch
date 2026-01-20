# Repository Guidelines

## Project Structure & Module Organization
- `server/` is the core Elasticsearch server; `modules/` and `plugins/` hold built-in modules and plugins.
- `x-pack/` contains commercial features; `libs/` hosts shared libraries; `client/` contains client code.
- `rest-api-spec/` stores REST specs and YAML tests; `qa/` contains integration, upgrade, and REST test suites.
- `test/` provides shared test fixtures; `docs/` and `README.asciidoc` cover documentation.

## Build, Test, and Development Commands
- `./gradlew run` starts a dev cluster from source; use `-Dtests.es.*` for settings.
- `./gradlew test` runs unit tests; scope to a module with `:server:test`.
- `./gradlew internalClusterTest` runs in-memory cluster integration tests.
- `./gradlew check` runs full verification (static checks + tests).
- `./gradlew precommit` runs formatting and precommit checks.
- `./gradlew localDistro` builds a local distribution; use `./gradlew :distribution:archives:linux-tar:assemble` (or platform variant) for archives.

## Coding Style & Naming Conventions
- Java is formatted with Spotless; use `./gradlew spotlessApply` or `./gradlew spotlessJavaCheck`.
- Indent is 4 spaces, line width 140, no wildcard imports, and negative booleans use `foo == false`.
- Use `// tag::noformat` only when necessary; doc snippets (`// tag::NAME`) stay within 76 chars.
- Follow existing naming patterns: unit tests typically end in `*Tests`, integration tests in `*IT` (especially under `qa/`).

## Testing Guidelines
- Framework: JUnit with randomized tests.
- REST YAML tests live in `rest-api-spec/`; run via `./gradlew :rest-api-spec:yamlRestTest`.
- No explicit coverage target is documented; rely on `check`, `precommit`, and module suites.

## Commit & Pull Request Guidelines
- Recent commit subjects are short, imperative, often include a scope tag (e.g., `[ES|QL]`) and a PR number `(#12345)`; follow that pattern.
- Open or reference a GitHub issue for larger changes; use "Closes #123" in PRs when applicable.
- Sign the CLA, avoid force-pushing shared branches, and keep initial changes squashed unless reviewers request otherwise.
- PRs should explain the change, list tests run, and link relevant issues.

## Environment & Tooling
- JDK 21 is required; use the Gradle wrapper `./gradlew`.
- Docker is needed for some builds and test fixtures.
