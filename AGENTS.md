# Kestra Join Plugin

## What

- Provides plugin components under `io.kestra.plugin.join`.
- Includes one task, `Join`, which joins two ION files from internal storage (inner, outer, anti and semi joins).

## Why

- What user problem does this solve? Flows often need to combine related data from different sources on a common key, without loading it into a database or writing a custom script.
- Why would a team adopt this plugin in a workflow? It joins the ION files that other Kestra tasks already produce, in a single declarative task.
- What operational/business outcome does it enable? Simpler data pipelines with fewer moving parts, and joins that are explicit and reviewable in the flow YAML.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.join`:

- root: the `Join` task and `package-info.java`
- `algo`: join algorithms (`HashJoin`, `NestedLoopJoin`, `SortedMergeJoin`)
- `io`: ION reading and writing through the run context (`JoinIOHandler`, `IterableIonReader`)
- `model`: join data structures (`JoinKey`, `HashTableRow`)
- `util`: enums (`JoinType`, `JoinImplementation`, `OutputMode`, `MissingKeyAction`, `Side`, `JoinState`), ION type helpers and `Messages`
- `exception`: task-specific runtime exceptions

Infrastructure dependencies (Docker Compose services):

- `app`: a local Kestra instance for manual QA

### Key Plugin Classes

- `io.kestra.plugin.join.Join`

### Project Structure

```
plugin-join/
├── src/main/java/io/kestra/plugin/join/
│   ├── algo/
│   ├── exception/
│   ├── io/
│   ├── model/
│   └── util/
├── src/main/resources/
│   ├── doc/
│   ├── icons/
│   └── metadata/
├── src/test/java/io/kestra/plugin/join/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
