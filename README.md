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


### Gradle Task Mode (`./gradlew genAiTest`)


> Внутри отдельного модуля `plugin` задачи реализованы как отдельные Groovy-классы, унаследованные от `DefaultTask` (без `JavaExec`).
> В `TaskAction` они вызывают Java API `EntryPointTaskApi` из модуля `entrypoint` через `new`, а для конфигурации используется отдельный класс пропертей `GeneratorTaskProperties` (в модуле `plugin`).

Эта задача запускает генерацию в режиме `scan` (эквивалент `--mode scan`) и так же читает параметры из `gradle.properties` (`gigachat.*`) или из `-P` флагов.

```bash
./gradlew genAiTest
```

Примеры:

```bash
./gradlew genAiTest -Pgigachat.path=./example-project -Pgigachat.project=true
./gradlew genAiTest -Pgigachat.path=./example-project -Pgigachat.class=com.example.app.service.UserService
./gradlew genAiTest -Pgigachat.path=./example-project -Pgigachat.compile=true -Pgigachat.execute=true
```

### Gradle Task Mode (`./gradlew diffGenUnitTest`)

Проект можно подключить как модуль в другой Gradle-проект и вызывать генерацию напрямую через задачу:

```bash
./gradlew diffGenUnitTest
```

Задача прокидывает параметры в entrypoint из `gradle.properties` (ключи с префиксом `gigachat.`).
Если `gigachat.path` не задан, используется текущий проект (директория запуска Gradle).
Если `gigachat.sourceBranch` и `gigachat.targetBranch` не заданы, используются значения по умолчанию:
* source branch = текущая git-ветка,
* target branch = `master`.

Пример переопределения параметров на запуске:

```bash
./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.compile=true -Pgigachat.execute=true -Pgigachat.targetBranch=main
```


#### `gradle.properties` example (all supported `gigachat.*` keys)

Use [gradle.properties.example](./gradle.properties.example) as a template for both `genAiTest` and `diffGenUnitTest`.

```properties
# Path to scanned project (optional)
# if omitted -> current project directory where task is started
# gigachat.path=./example-project

# Optional filters
gigachat.includeModules=
gigachat.includeClasses=
gigachat.class=

# Pipeline flags
gigachat.project=true
gigachat.singleFile=false
gigachat.parallel=false
gigachat.compile=false
gigachat.execute=false

# Diff branches (optional)
# defaults: source=current branch, target=master
gigachat.sourceBranch=
gigachat.targetBranch=

# Token auth (optional)
gigachat.token=
gigachat.endpoint=
gigachat.authUrl=

# mTLS auth (optional)
gigachat.cert=
gigachat.rootCert=
gigachat.key=

# Optional extras
gigachat.ssl=false
gigachat.proxy=false
gigachat.model=
```

#### Command examples for `./gradlew diffGenUnitTest`

* Default run (uses values from `gradle.properties`):

  ```bash
  ./gradlew diffGenUnitTest
  ```

* Run against current branch vs `master` (no branches specified, path = current project):

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project
  ```

* Explicit branches:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.sourceBranch=feature/my-change -Pgigachat.targetBranch=main
  ```

* Enable compile and execute checks:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.compile=true -Pgigachat.execute=true
  ```

* Single target class:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.class=com.example.app.service.UserService
  ```

* Multiple target classes:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.class=com.example.app.service.UserService,com.example.app.service.NotificationService
  ```

* Limit scan to selected modules:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.includeModules=module-a,module-b
  ```

* Token authentication:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.token=<token> -Pgigachat.endpoint=https://gigachat.example -Pgigachat.authUrl=https://oauth.example
  ```

* mTLS authentication:

  ```bash
  ./gradlew diffGenUnitTest -Pgigachat.path=./example-project -Pgigachat.cert=/path/client.pem -Pgigachat.rootCert=/path/root.pem -Pgigachat.key=/path/private.key
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

Если флаги `--source-branch` / `--target-branch` не переданы, используются значения по умолчанию: текущая ветка и `master` соответственно.

Параметры, которые задача читает из `gradle.properties`:
* `gigachat.path`
* `gigachat.includeModules`
* `gigachat.includeClasses`
* `gigachat.class`
* `gigachat.singleFile`
* `gigachat.parallel`
* `gigachat.project`
* `gigachat.compile`
* `gigachat.execute`
* `gigachat.sourceBranch`
* `gigachat.targetBranch`
* `gigachat.token`, `gigachat.endpoint`, `gigachat.authUrl`, `gigachat.cert`, `gigachat.rootCert`, `gigachat.key`, `gigachat.ssl`, `gigachat.proxy`, `gigachat.model`


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
* `--proxy` &mdash; switch to unauthenticated HTTP proxy LLM client (no token/certificates required).
* `--model` &mdash; override the model name (defaults to `GIGA_CHAT_MAX_2`).

When credentials are missing the generator continues to operate with the deterministic stub so pipeline runs remain reproducible.

## Running Tests

```bash
./gradlew :entrypoint:test --console=plain
```
