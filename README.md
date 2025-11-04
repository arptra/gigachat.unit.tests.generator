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

### Scan a Single Class from the Example Project

To limit the pipeline to a single class (e.g., `com.example.app.service.UserService`), run:

```bash
./gradlew :entrypoint:run --args="--mode scan --path ./example-project --class com.example.app.service.UserService"
```

This command parses only the requested class, prepares its test DTOs, and executes the pipeline stages.

## Running Tests

```bash
./gradlew :entrypoint:test --console=plain
```
