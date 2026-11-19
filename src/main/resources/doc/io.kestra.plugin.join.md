# How to use the Join plugin

Join two ION files with various join operations.

## Join types

Several join types are supported, allowing different operations to be applied on the input data:

- **inner join (default)**
- outer joins: full outer, left outer, right outer
- anti joins: left anti, right anti
- semi joins: left semi, right semi

Note that **anti** and **semi** joins are not *merge-type* joins, which means that they only filter rows from one side (left or right) without combining data from both inputs.
<!-- You will find more information [here (to be completed)](). -->

## Join implementations

These are algorithmic strategies for performing the join.\
The choice does not affect the output content, but it can influence performance, depending on dataset size and whether inputs are sorted.\
Output row order may also vary depending on this.
<!-- You will find more information [here (to be completed)](). -->

Available implementations:

- **hash (default)**
- nested loop
- sorted merge

## Output mode

This property allows you to configure the output structure to differentiate fields by side.\
It can have the following values:
- **overwrite (default)**: with [merge-type joins](#join-types), fields that have identical names between inputs will be overwritten in matched rows.\
As fields from **left** are written before **right** ones, the remaining fields will be those from **right** in the case of overwriting.
- **prefix**: adds a prefix to fields names according to side with `leftLabel` and `rightLabel` values, and a separator character specified by the `prefixSeparator` property.\
For example, with common `name` field, `leftLabel = "L"`, `rightLabel = "R"` and `prefixSeparator = "_"`, output could be:
```json
{"L_name": "x", "R_name": "y", ...}
...
```
- **nest**: nest fields in two objects for each side named by `leftLabel` and `rightLabel`.\
For example, with common `name` field and `leftLabel = "left"` and `rightLabel = "right"`, output could be:
```json
{"left": {"name":"x", ...}, "right": {"name":"y", ...}
...
```

## Accepted key types

Following **ION** types are accepted for key fields:
- `BOOL`,
- `DECIMAL`,
- `FLOAT`,
- `INT`,
- `STRING`,
- `TIMESTAMP`.

Additionally, `NULL` type is accepted only if `onMissingKey` value is `IGNORE` or `WARNING`, which is not the case by default (see [Missing key](#missing-key)).

## Special cases

### Nested key

You can use a nested field as join key with an object-like syntax.

For example, for this input:

```json
{"id": {"a": 1, "b": "x"}}
...
```

The `a` field can be accessed with the following key: `id.a`.\
This works with more levels of nesting too.\
Note however that key is not truly evaluated as a programming expression, it is just a raw string that is split on the dot sign.

The task could fail in the following contexts of nested fields:
- using a nested key that is too deep (not reachable) for the actual row of input, for example: `id.a.z` for the previous input.
- using a key that is too shallow for the actual row of input, meaning the value is still a nested row (which is not a valid type), for example: `id` for the previous input.

### Empty input

The task will **not fail** if one or both input(s) is empty, as this case is not strictly invalid for join algorithms.\
However you will get a log message for each empty input.

### Missing key

By default, the task will **fail** if the key field does not exist in at least one row of an input or has a `NULL` value.

This behaviour is defined by the `onMissingKey` optional property, which has the `FAIL` default value.\
Other possible values are:
- `WARNING`: sets the task state to `WARNING`, and a log will be emitted for the first corresponding row of each input.
- `IGNORE`: allows the task to continue with no state change, and a log will be emitted for the first corresponding row of each input.

If there are missing keys in both inputs, they will not match.

### Semi joins identical rows

By default, semi joins can output multiple identical rows, as one row can match with several rows from other side, but without retrieving the fields from the other side.

You can change this behaviour to get only the first matched rows, by putting the value of
`onlyFirstMatchOnSemi` property to `true`.\
This property is optional, and its default is `false`.

### Sorted merge specific errors

Some errors will occur only with sorted merge implementation, as it needs a stricter data consistency by design:
- different key types between rows from a single input,
- different key types between inputs.
