# Working style: batch-first fixing

Slow feedback loops (CI, full builds, game/server boots, deploys) must never be spent on one fix at a time.

1. **Surface every problem before fixing any.** Before kicking off a slow run, make it report *all* failures, not just the first:
   - prefer static checks that need no run at all (e.g. audit every mixin target/descriptor against the real target jar, grep for every use of a renamed API, type-check/lint the whole tree);
   - run builds with `--continue` / keep-going flags; boot games with fail-fast checks relaxed (e.g. `-Dmixin.ignoreRequired=true`) so one boot logs every failure;
   - write a deduplicated summary of all errors from each run.
2. **Fix in batches by pattern.** Group errors by root cause, fix the pattern everywhere it occurs (search the whole codebase, not just the reported line), and push one batch per iteration.
3. **Reason statically first.** Read the source/patches/diffs to predict breakage instead of waiting on CI to discover it.
4. **Don't redo unchanged work.** Cache or reuse artifacts of components whose inputs didn't change; only rebuild what changed.
5. **Parallelise independent work** (separate repos/modules) with subagents, each owning distinct files/branches.
6. A one-line fix followed by a full rebuild cycle is a smell — stop and batch.
