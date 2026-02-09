# Reasoning Refactor: Iteration Log

Назначение: фиксировать каждую итерацию переработки reasoning-флоу так, чтобы в любой момент было понятно:

1. Что уже изменено.
2. На каком шаге остановились.
3. Какие ошибки/риски сейчас блокируют следующий шаг.
4. Что делаем следующим коммитом.

## Формат записи

Для каждой итерации заполнять:

1. `ID` и `Date`.
2. `Goal`.
3. `Changes` (по файлам).
4. `Verification` (что запускали и результат).
5. `Stop Point` (где остановились).
6. `Open Errors/Risks`.
7. `Next Iteration`.

---

## Iteration 001

- Date: 2026-02-09
- Goal: начать Stage 0 из TO-BE документа: добавить трассируемость reasoning-сессий и снимки итераций.

### Changes

1. Добавлен идентификатор сессии `FixSessionId`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/FixSessionId.java`
2. Добавлен DTO снимка итерации `ReasoningIterationSnapshot`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/ReasoningIterationSnapshot.java`
3. `CompilationPipelineOrchestrator` теперь:
   - принимает `fixSessionId` в конструкторе;
   - накапливает `iterationSnapshots`;
   - записывает snapshot на каждом шаге (decision/outcome/actions/error signature);
   - пробрасывает snapshots в `FixingFailureException`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
4. `FixingFailureException` расширен полем `iterationSnapshots`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/workflow/exception/FixingFailureException.java`
5. `InitialGenerationStep`:
   - генерирует `fixSessionId` для каждой outer-attempt;
   - прокидывает ID в orchestrator;
   - логирует structured summary по каждой snapshot-итерации (успех/провал).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/pipeline/InitialGenerationStep.java`
6. Обновлен тест создания orchestrator под новую сигнатуру.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestratorTest.java`
7. Добавлен тест на сохранение iteration snapshots при исчерпании context budget.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestratorTest.java`

### Verification

- `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.

### Stop Point

- Остановлено после внедрения телеметрии Stage 0 и подтверждения snapshot coverage unit-тестом.

### Open Errors/Risks

1. Нужно проверить, что объем новых structured logs приемлем на длинных прогонов и не перегружает `pipeline.log`.
2. Пока не запускался полный `:entrypoint:test` в рамках этой итерации (известно, что в проекте есть unrelated нестабильный тест сканера).

### Next Iteration

1. Начать Stage 1: ввести `FixSession` runtime-модель и связать outer/inner reasoning в единую сессию.
2. Добавить state transition tests для `Observe -> Diagnose -> Plan -> Apply -> Verify -> Learn`.
3. Перенести structured iteration snapshot из ad-hoc логирования в единый session journal объект.

---

## Iteration 002

- Date: 2026-02-09
- Goal: завершить Stage 1 миграцию на `FixSession` и закрыть разрыв по тестам/контрактам.

### Changes

1. Обновлены reasoning-тесты под новый конструктор orchestrator (`FixSession` вместо `String`).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestratorTest.java`
2. Добавлен unit-тест state machine `FixSession`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/model/FixSessionTest.java`
3. Подтверждено, что `CompilationPipelineOrchestrator` и `InitialGenerationStep` работают через единый объект сессии и журнал переходов/итераций.

### Verification

- `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.

### Stop Point

- Stage 1 завершён: сессия, переходы и snapshots стабильно покрыты тестами.

### Open Errors/Risks

1. Без enriched context LLM всё ещё может принимать решения без привязки к методу под тестом и mock-plan.

### Next Iteration

1. Добавить обязательный enriched context в `ReasoningLoopContext` и prompt.
2. Прокинуть методный/моковый контекст в compile и execute ветки reasoning.

---

## Iteration 003

- Date: 2026-02-09
- Goal: сделать контекст reasoning непрерывным и привязанным к реальному target-методу/мокам.

### Changes

1. `ReasoningLoopContext` расширен полем `additionalContext` (с backward-compatible конструктором).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/ReasoningLoopContext.java`
2. `NextContextBuilder` научен собирать контекст с `additionalContext`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/NextContextBuilder.java`
3. `CompilationReasoningPromptBuilder` теперь включает `Reasoning session context (JSON)` в prompt.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilder.java`
4. `CompilationPipelineOrchestrator`:
   - принимает `staticReasoningContext`;
   - добавляет runtime контекст сессии (`fixSession state`, хвост переходов, хвост snapshots) в каждую итерацию.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
5. `InitialGenerationStep`:
   - строит `buildReasoningStaticContext(...)` из `methodInfo`, `analysisSummary`, `mockPlan`, текущего snippet;
   - прокидывает его в orchestrator и в execute-ветку `triggerReasoningWorkflow`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/pipeline/InitialGenerationStep.java`
6. Обновлён тест prompt builder на наличие нового контекстного блока.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilderTest.java`

### Verification

- `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.

### Stop Point

- Контекст больше не "оторван": в каждом reasoning-шаге присутствуют метод под тестом, mock-plan и хвост истории сессии.

### Open Errors/Risks

1. До policy-слоя LLM всё ещё может предлагать невалидные или циклические действия.

### Next Iteration

1. Внедрить deterministic policy layer перед исполнением действий.
2. Добавить anti-loop (fingerprints + no-progress guard).

---

## Iteration 004

- Date: 2026-02-09
- Goal: устранить рандомность применения действий и петли фиксов через policy engine и anti-loop механику.

### Changes

1. `ReasoningMemory` расширен anti-loop данными:
   - `fixFingerprintAttempts`;
   - `blockedFixFingerprints`;
   - `noProgressStreak`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/ReasoningMemory.java`
2. Prompt дополнен диагностикой anti-loop памяти (`NO_PROGRESS_STREAK`, `BLOCKED_FIX_FINGERPRINTS`).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilder.java`
3. Добавлен `ReasoningDecisionPolicyEngine`:
   - нормализует решения/действия;
   - валидирует preconditions (`ADD_IMPORT`, `ADD_DEPENDENCY`, `APPLY_PATCH`);
   - форсирует `REQUEST_CONTEXT` fallback вместо бессмысленных `APPLY_FIX/STOP`;
   - экспортирует action fingerprints для anti-loop.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
4. `CompilationPipelineOrchestrator` интегрирован с policy engine:
   - применяет `normalize(...)` перед выполнением actions;
   - учитывает fingerprints (`attempts`, `block on no-effect`);
   - вводит `MAX_NO_PROGRESS_STREAK` guard (`ABORT` с explicit outcome).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
5. Добавлен unit-тест policy engine (валидация фиксов, fallback, блокировка fingerprint).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test` -> `FAILED` из-за известного не связанного теста:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- Stage 2/3 базово внедрён: перед выполнением есть deterministic валидация действий, на повторяющихся no-op фикcах включается блокировка/остановка.

### Open Errors/Risks

1. Пока нет полноценного rollback к "last known good" snapshot (есть блокировка/abort, но не откат правок).
2. Контракт ответа ещё v1 (`decision/actions/memory_updates`), v2 (`hypothesis/expected_delta`) пока не введён.

### Next Iteration

1. Добавить rollback к последнему успешному снапшоту при regress/no-progress.
2. Перейти на JSON-контракт v2 и строгую валидацию `expected_delta`.
3. Расширить детерминированные кандидаты для mock-repair сценариев.

---

## Iteration 005

- Date: 2026-02-09
- Goal: довести сходимость reasoning-цикла за счет детерминированного fallback fix и rollback при abort.

### Changes

1. `ReasoningDecisionPolicyEngine` усилен:
   - fallback больше не только `REQUEST_CONTEXT`;
   - если уже есть `SEARCH_SYMBOL=FOUND_ONE` и неразрешенный missing symbol, policy сам строит deterministic `APPLY_FIX -> ADD_IMPORT`;
   - учитываются уже известные imports, чтобы не пытаться добавить существующий import.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
2. Добавлены новые unit-тесты policy для deterministic stop->fix сценария.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`
3. В `CompilationPipelineOrchestrator` реализован best-effort rollback test-файла к лучшему снимку при `ABORT`:
   - no-progress limit;
   - context budget exhausted;
   - unsupported decision;
   - max iterations exhausted.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
4. Добавлен unit-тест rollback на no-progress abort.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestratorTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.orchestrator.CompilationPipelineOrchestratorTest'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
4. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- Reasoning-ядро теперь не зависит полностью от качества ответа LLM: при достаточном контексте fix строится детерминированно, а при неуспехе проект не остается в ухудшенном состоянии.

### Open Errors/Risks

1. Контракт ответа LLM всё еще v1; поля `hypothesis/expected_delta` пока не обязательны.
2. Нет специализированного deterministic mock-repair planner (пока только общий контекст метода+mock-plan в prompt/policy).

### Next Iteration

1. Перевести ответ reasoning на JSON v2 (`hypothesis + expected_delta + preconditions`) и сделать строгую валидацию.
2. Добавить deterministic ветку для mock-signature mismatches и collaborator alignment.

---

## Iteration 006

- Date: 2026-02-09
- Goal: перейти на минимальный JSON v2 контракт для `APPLY_FIX`, чтобы решения имели объяснение и ожидаемый эффект.

### Changes

1. `ReasoningResponse` расширен v2-полями:
   - `hypothesis`;
   - `expected_delta` (`compile_errors`, `symbol`).
   - `preconditions` для action-ов.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/ReasoningResponse.java`
2. `ReasoningResponseParser` теперь валидирует `APPLY_FIX` строго:
   - `hypothesis` обязателен;
   - `expected_delta` обязателен и не может быть пустым.
   - каждый fix-action обязан иметь непустой `preconditions`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningResponseParser.java`
3. `CompilationReasoningPromptBuilder` обновлен под v2 контракт (требует `hypothesis` и `expected_delta` в JSON-схеме).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilder.java`
4. Обновлены тестовые payload-ы `APPLY_FIX` под новый контракт.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningResponseParserTest.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/CompilationReasoningServiceTest.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestratorTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- Контракт `APPLY_FIX` стал строгим и объяснимым: любое фикc-действие теперь обязано нести гипотезу и ожидаемую дельту.

### Open Errors/Risks

1. Deterministic ветка для mock-signature repair ещё не реализована.

### Next Iteration

1. Реализовать mock-focused deterministic planner (signature mismatch / collaborator alignment).

---

## Iteration 007

- Date: 2026-02-09
- Goal: убрать blind-stop в сигнатурных и mock-сценариях и начать строго выполнять `preconditions` не только parser-ом, но и policy engine-ом.

### Changes

1. `ReasoningDecisionPolicyEngine`:
   - `normalize(...)` расширен опциональным `iterationContext`;
   - при `STOP` теперь выполняется детерминированный fallback (если budget > 0), а не немедленная остановка;
   - добавлен deterministic planner для `METHOD_SIGNATURE_MISMATCH` / `TYPE_MISMATCH` / `ACCESS_VIOLATION`, который собирает корректный контекст:
     - `SHOW_FILE`, `SHOW_IMPORTS`,
     - `READ_METHOD`, `LIST_METHODS` по `repairTargetContext`,
     - `READ_CLASS` для mock collaborators.
   - добавлена runtime-проверка `preconditions` перед выполнением действий (`symbol_resolved_unique`, `dependency_missing_package`, `patch_applies_cleanly`, `test_file_targeted`, `method_signature_mismatch`).
   - deterministic `ADD_IMPORT` fallback теперь заполняет `hypothesis/expected_delta` и strict preconditions.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
2. `CompilationPipelineOrchestrator` прокидывает `iterationContext` в policy normalization.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
3. Добавлены/обновлены тесты policy:
   - fallback для сигнатурного mismatch в method/mock context branch;
   - проверки `symbol_resolved_unique` precondition в import-fix кейсах.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`
4. Prompt уточнен:
   - добавлен whitelist допустимых preconditions tokens для LLM.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilder.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- В сигнатурных/mock ошибках reasoning больше не уходит в бесполезный `STOP`: всегда есть детерминированный сбор релевантного контекста.

### Open Errors/Risks

1. Пока нет deterministic **apply-fix** стратегии для mock/signature mismatch (есть deterministic context-collection и policy validation, но не автоматическая правка).

### Next Iteration

1. Добавить constrained deterministic fix candidates для mock/signature mismatch (например, safe import/stub alignment без raw patch-инъекций).

---

## Iteration 008

- Date: 2026-02-09
- Goal: добить управляемость policy-layer и снизить повторные циклы контекстных действий.

### Changes

1. `ReasoningDecisionPolicyEngine`:
   - `STOP` при доступном контекстном бюджете больше не останавливает цикл сразу: теперь всегда пробуем deterministic fallback;
   - включена runtime-валидация `preconditions` перед принятием действий (`APPLY_FIX` и `REQUEST_CONTEXT`);
   - method/mock context fallback учитывает уже собранный `contextCache` и не повторяет:
     - `READ_METHOD`, если метод уже прочитан;
     - `LIST_METHODS`, если список методов класса уже в кэше;
     - `READ_CLASS`, если класс уже прочитан.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
2. Prompt уточнен whitelist-ом preconditions токенов (синхронизирован с policy runtime checks).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/prompt/CompilationReasoningPromptBuilder.java`
3. `CompilationPipelineOrchestrator` прокидывает `iterationContext` в policy engine normalize.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
4. Добавлены unit-тесты на:
   - fallback для signature mismatch;
   - non-repeating behavior при уже заполненном context cache.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- Policy engine стал state-aware и context-aware: решения валидируются не только структурно, но и по реальным preconditions и уже собранному контексту.

### Open Errors/Risks

1. В signature/mock ветке пока deterministic только на уровне context-collection; safe auto-apply fix кандидаты ещё не добавлены.

### Next Iteration

1. Ввести constrained deterministic apply-candidates для mock/signature mismatch (safe rewrite шаблоны без произвольных patch-ов от LLM).

---

## Iteration 009

- Date: 2026-02-09
- Goal: убрать разрыв памяти между execute/compile ветками и между повторными вызовами orchestrator в одной fix-сессии.

### Changes

1. `FixSession` расширен runtime-состоянием:
   - persistent `ReasoningMemory`;
   - накопленный `ActionExecutionResult` (cumulative execution context).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/FixSession.java`
2. `CompilationPipelineOrchestrator` теперь работает не с локальными временными объектами, а с состоянием из `FixSession`:
   - использует `fixSession.getMemory()`;
   - использует и пополняет `fixSession.getCumulativeExecutionResult()`;
   - учитывает forbidden actions из execution results.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
3. `InitialGenerationStep` execute-failure ветка:
   - нормализует ответ через `ReasoningDecisionPolicyEngine` (тот же policy pipeline, что и в compile-loop);
   - использует `FixSession` memory/cumulative context;
   - после `actionExecutor.execute(...)` сохраняет результат в `FixSession` и применяет memory updates.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/pipeline/InitialGenerationStep.java`
4. Добавлен unit-тест на persistent state в `FixSession`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/model/FixSessionTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.model.FixSessionTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- Основной разрыв “LLM не понимает, что было до этого” закрыт: в пределах одной outer-attempt сессия сохраняет память и накопленный контекст между execute и compile reasoning-проходами.

### Open Errors/Risks

1. Для signature/mock mismatch всё ещё нет deterministic auto-apply фикса (есть deterministic context collection + strict policy validation).

### Next Iteration

1. Добавить constrained deterministic apply-fix шаблоны для signature/mock mismatch (минимальные безопасные трансформации тестового метода).

---

## Iteration 010

- Date: 2026-02-09
- Goal: расширить deterministic apply-fix для mock/dependency кейсов и закрепить межветочный (execute->compile) контекст в сессии.

### Changes

1. `FixSession` теперь хранит:
   - persistent `ReasoningMemory`;
   - persistent cumulative `ActionExecutionResult`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/FixSession.java`
2. `CompilationPipelineOrchestrator`:
   - использует session-memory/session-cumulative-result вместо локального ephemeral состояния;
   - сохраняет execution results и forbidden actions в `FixSession`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/orchestrator/CompilationPipelineOrchestrator.java`
3. `InitialGenerationStep` execute-failure ветка:
   - normalizes reasoning response через policy engine;
   - сливает execution actions + memory updates обратно в `FixSession` перед повторным compile-loop.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/pipeline/InitialGenerationStep.java`
4. `ReasoningDecisionPolicyEngine` расширен deterministic apply-fix кандидатом для missing package:
   - `org.mockito.junit.jupiter` -> `org.mockito:mockito-junit-jupiter:5.11.0`
   - `org.mockito` -> `org.mockito:mockito-core:5.11.0`
   - `org.junit.jupiter` -> `org.junit.jupiter:junit-jupiter:5.10.2`
   - `org.assertj.core` -> `org.assertj:assertj-core:3.26.0`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
5. Новые/обновленные тесты:
   - persistence состояния `FixSession`;
   - deterministic dependency fallback для missing mockito package.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/model/FixSessionTest.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.model.FixSessionTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.
4. `./gradlew :entrypoint:test` -> `FAILED` только на известном unrelated тесте:
   - `JavaProjectScannerTest.diffModeScansOnlyChangedJavaFilesBetweenBranches()` (`IOException`).

### Stop Point

- В рамках одной outer-attempt reasoning реально сохраняет накопленный контекст и память между execute/compile шагами; для missing packages появился deterministic auto-fix path.

### Open Errors/Risks

1. Для signature/mock mismatch по-прежнему нет deterministic auto-apply patch/template для самого тестового вызова (пока deterministic context collection + policy).

### Next Iteration

1. Добавить безопасные constrained patch-кандидаты для signature mismatch (например, controlled invocation alignment по target method arity при подтвержденном method context).

---

## Iteration 011

- Date: 2026-02-09
- Goal: повысить качество mock-repair на execute стадии, чтобы `ALIGN_MOCKS` работал на реальных тестах (включая уже существующие, но неаннотированные поля).

### Changes

1. `ReasoningDecisionPolicyEngine` усилен в deterministic mock fallback:
   - `ALIGN_MOCKS` теперь может строиться не только из `mockPlan.targets`, но и из `testedMethod.dependencies`, если `mockPlan` пустой;
   - добавлена нормализация mock-targets с поддержкой `qualifiedType/className` и `identifier/variableName`;
   - добавлен безопасный default strategy (`MOCKITO`) для `ALIGN_MOCKS`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
2. `ToolActionExecutor.ALIGN_MOCKS` исправлен:
   - если поле уже существует, но без `@Mock`/`@InjectMocks`, аннотация теперь добавляется над существующим полем;
   - исключено дублирование полей при повторном применении `ALIGN_MOCKS`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ToolActionExecutor.java`
3. Добавлены unit-тесты:
   - policy: derive `mockTargets` из `testedMethod.dependencies`, когда `mockPlan.targets` пуст;
   - executor: аннотирование существующих полей без их дублирования.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ToolActionExecutorTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutorTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.

### Stop Point

- `ALIGN_MOCKS` стал практичнее для execute-failure сценариев:
  - работает при пустом `mockPlan.targets` за счет fallback по зависимостям метода;
  - исправляет уже существующие поля, а не только добавляет новые.

### Open Errors/Risks

1. Пока не добавлен deterministic генератор stubbing (`when(...).thenReturn(...)`) на основе invocation/return type; это может оставлять часть execute-падений (из-за отсутствия конкретных stub-значений).
2. Память reasoning сохраняется в пределах одной fix-сессии (outer-attempt), но не между независимыми API-запусками процесса.

### Next Iteration

1. Добавить constrained stubbing planner для `ALIGN_MOCKS` (по `testedMethod.invocations` + сигнатурам из контекста), чтобы закрывать NPE/`wanted but not invoked` кейсы не только scaffold-ом, но и осмысленными stub-ами.

---

## Iteration 012

- Date: 2026-02-09
- Goal: расширить `ALIGN_MOCKS` от scaffold-фикса до базового stubbing, чтобы execute-падения из-за пустых моков закрывались автоматически.

### Changes

1. `ReasoningDecisionPolicyEngine`:
   - для deterministic `ALIGN_MOCKS` добавлено построение `mockStubs` из `testedMethod.invocations`;
   - `mockStubs` привязываются только к известным `mockTargets` по идентификатору;
   - `ALIGN_MOCKS` canonical args расширены ключом `mockStubs`.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngine.java`
2. `ToolActionExecutor.ALIGN_MOCKS`:
   - добавлен парсинг `mockStubs`;
   - добавлено вставление в тестовый метод (`methodName`) lenient-stub строк вида:
     - `Mockito.lenient().doAnswer(...RETURNS_DEFAULTS...).when(mock).method(matchers...)`;
   - добавлен matcher builder по `argTypes` (primitive-aware `anyInt/anyLong/...`, иначе `any()`).
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ToolActionExecutor.java`
3. Унификация аргументов `ALIGN_MOCKS`:
   - `ArgumentNormalizer` поддерживает `mockStubs/stubs`.
   - `ReasoningResponse` маппит `mockStubs` в canonical args.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/service/ArgumentNormalizer.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/main/java/com/gigachat/unit/tests/generator/reasoning/model/ReasoningResponse.java`
4. Добавлены/обновлены тесты:
   - policy: проверка derive `mockStubs` из `testedMethod.invocations`;
   - executor: проверка вставки lenient-stub в тестовый метод.
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ReasoningDecisionPolicyEngineTest.java`
   - `/Users/artapr/IdeaProjects/gigachat.unit.tests.generator/entrypoint/src/test/java/com/gigachat/unit/tests/generator/reasoning/service/ToolActionExecutorTest.java`

### Verification

1. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ToolActionExecutorTest'` -> `BUILD SUCCESSFUL`.
2. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.service.ReasoningDecisionPolicyEngineTest'` -> `BUILD SUCCESSFUL`.
3. `./gradlew :entrypoint:test --tests 'com.gigachat.unit.tests.generator.reasoning.**.*'` -> `BUILD SUCCESSFUL`.

### Stop Point

- `ALIGN_MOCKS` теперь закрывает два класса execute-проблем:
  - отсутствующие mock-поля/аннотации;
  - отсутствие базовых stub-ов для вызовов, найденных в `testedMethod.invocations`.

### Open Errors/Risks

1. Stub planner пока не использует фактические return-типы методов для domain-значений, применяет безопасный default-answer (`RETURNS_DEFAULTS`).
2. Память reasoning сохраняется в пределах одной fix-сессии, но не между независимыми API-запусками процесса.

### Next Iteration

1. Добавить типо-ориентированную генерацию `thenReturn(...)` для ключевых invocations (по `READ_METHOD/READ_CLASS` сигнатурам), чтобы уменьшить flaky execute-ошибки на бизнес-ассертах.
