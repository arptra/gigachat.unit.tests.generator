# GigaChat Unit Tests Generator

This repository hosts the TestRepairAgent entrypoint module together with an example Java project used for scanner validation.

## Requirements

* Java 21
* Gradle 8+

## Running the Entrypoint

Use Gradle to invoke the entrypoint module. For example, to scan the whole example project:

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --project"
```

### Sequential Single-File Mode

Use `--single-file` to enable sequential scanning. In this mode the entrypoint
parses one Java file at a time, feeds its classes into the pipeline, waits for
the generation step to finish, and only then moves to the next file. The flag
has no arguments:

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --single-file"
```

This mode is useful when you want to control resource usage or stop the run
after processing a subset of files because the scanner never loads the whole
project at once.

### Scan a Single Class from the Example Project

To limit the pipeline to a single class (e.g., `com.example.app.service.UserService`), run:

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --class com.example.app.service.UserService"
```

This command parses only the requested class, prepares its test DTOs, and executes the pipeline stages.

### Scan Multiple Classes at Once

Pass a comma-separated list to `--class` to scan several classes in one run:

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --class com.example.app.service.UserService,com.example.app.service.NotificationService"
```

The entrypoint filters discovered classes to the provided set and generates DTOs only for those targets.


### Diff Generation Mode (`diffGenUnitTest`)

Для генерации тестов только по изменениям между двумя ветками используйте режим `diffGenUnitTest`.

В этом режиме агент:
* строит git diff между веткой-источником (`--source-branch`) и целевой веткой (`--target-branch`),
* выбирает только изменённые Java-классы,
* запускает стандартный pipeline генерации юнит-тестов для найденных классов с учётом всех остальных флагов (`--compile`, `--execute`, `--single-file`, фильтры и т.д.).

Пример запуска:

```bash
./gradlew :entrypoint:run --args="--mode diffGenUnitTest --path ./example-project --source-branch feature/my-change --target-branch main --compile --execute"
```

Параметры `--source-branch` и `--target-branch` обязательны для режима `diffGenUnitTest`.

### Cleaner Mode

Pass `--clean` (or `--mode clean`) to enable the cleaner pipeline. The cleaner iterates through all
detected tests, removes invalid imports, stray `@Test` annotations and placeholder assertions, and then
compiles and executes the remaining tests. Methods (or entire classes) that still fail during either
stage are deleted from the test sources.

### Using the Real GigaChat LLM

By default the pipeline relies on a stubbed LLM implementation. Supply credentials to enable the official
GigaChat client:

* **Token-based authentication**

  ```bash
  ./gradlew :entrypoint:run --args="--mode scan --path ./example-project --token <your_token>"
  ```

* **mTLS authentication**

  ```bash
  ./gradlew :entrypoint:run --args="--mode scan --path ./example-project --cert path/to/cert.pem --rootCert path/to/root.pem --key path/to/key.pem"
  ```

Optional switches:

* `--ssl` &mdash; enable strict certificate verification when talking to the GigaChat API (defaults to `false`).
* `--model` &mdash; override the model name (defaults to `GIGA_CHAT_MAX_2`).

When credentials are missing the generator continues to operate with the deterministic stub so pipeline runs remain reproducible.

## Running Tests

```bash
./gradlew :entrypoint:test --console=plain
```

## Reasoning Documentation

Подробное описание текущего reasoning-флоу (`AS-IS`) и плана полной переработки (`TO-BE`) находится в:

- `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/docs/reasoning/REASONING_FLOW_AS_IS_AND_TO_BE.md`
- `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/docs/reasoning/IMPLEMENTATION_ITERATIONS.md` (журнал итераций внедрения)
