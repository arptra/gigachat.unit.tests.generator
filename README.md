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

### Scan a Single Java File

When you only want to analyse one source file, pass its path via `--single-file`.
The file must belong to the project directory provided via `--path`.

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --single-file ./example-project/src/main/java/com/example/app/service/UserService.java"
```

The entrypoint parses just the supplied file and immediately feeds the discovered class metadata into the pipeline.

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
