# Nextflow LLM Agent — Design

**Date:** 2026-05-04
**Status:** Draft (pending implementation plan)
**Author:** Paolo Di Tommaso

## 1. Goal & non-goals

### Goal

Add a new top-level `agent` construct to the Nextflow DSL — a process-shaped primitive that wraps an LLM-driven tool-calling loop. Each invocation of an agent receives one input record, runs an LLM with access to a declared set of Nextflow modules as tools, and emits one output record. Agents compose with processes and other agents through the existing channel/workflow model.

The defining property of the design: **a tool call from the LLM is not a function call — it is a real Nextflow module invocation.** The args are wrapped as channel values, the module runs through the standard dataflow runtime (executor, container, retries, work dir, caching), and the resulting output channel record is serialized back to the LLM. The harness is the adapter between the LLM tool-call protocol and Nextflow's dataflow model.

### Non-goals (v1)

- Long-lived conversational agents (one input → one output is the contract)
- Channel-aware orchestrator agents (agents do not subscribe to channels mid-loop)
- Built-in multi-provider LLM abstraction (delegated to docker-agent)
- Agent-to-agent invocation as tools (composition is via channels only)
- Cost tracking, prompt analytics, fine-grained token budgets
- RAG, persistent memory, conversation state across invocations
- Typed structured outputs from the LLM (deferred until record-types lands)

## 2. DSL surface

A new top-level `agent` definition, parsed by `nf-lang` as a sibling of `processDef` and `workflowDef`. Tools are referenced as **modules** (registry coordinate or local include), so they bring their own description and I/O metadata via `meta.yml`.

```groovy
include { fastqc }     from 'nf-core/fastqc'
include { multiqc }    from 'nf-core/multiqc'
include { fetch_sra }  from './local/fetch_sra'

agent eval_agent {
    model 'openai/gpt-5-mini'
    instruction 'You break biological questions into structured plans.'
    tools fastqc, multiqc, fetch_sra
    maxIterations 20

    input:
        val question

    output:
        val plan

    prompt:
    """
    Question: ${question}
    Plan the steps to answer this using the available tools.
    """
}

workflow {
    Channel.of(params.question)
        | eval_agent
        | view
}
```

### Directives

| Directive | Required | Meaning |
|---|---|---|
| `model` | yes | Passed through to docker-agent (e.g. `openai/gpt-5-mini`, `anthropic/claude-opus-4-7`) |
| `instruction` | yes | System prompt (agent role/persona) |
| `tools` | yes | Comma-separated module references resolved through standard include/registry machinery |
| `maxIterations` | no | LLM-loop iteration cap (default from `agent.maxIterationsDefault` config) |
| `input:` / `output:` | yes | Standard process-style channel I/O |
| `prompt:` | yes | Templated user prompt with `${var}` interpolation over input bindings |

### I/O contract (v1)

- Input: any number of `val` declarations bound into the `prompt:` template via `${name}` interpolation. `path` inputs are not supported in v1 (out of scope).
- Output: a single `val` declaration that receives the LLM's final assistant message as a string. Multiple outputs and typed/structured outputs are deferred — see §9.

Standard process directives (`errorStrategy`, `maxRetries`, `time`, `cache`) apply to agents as they do to processes; agent failures use existing retry/error machinery without a parallel control path.

## 3. Architecture

```
┌─────────────────────────── Nextflow JVM ─────────────────────────────┐
│                                                                       │
│  workflow ──► AgentTaskRun  (one per input record)                   │
│                    │                                                  │
│                    ├── resolves tool list                            │
│                    │     • each ref → ModuleSpec (from meta.yml)     │
│                    │     • spec → MCP tool descriptor                │
│                    │                                                  │
│                    ├── starts in-process MCP server (stdio)          │
│                    │                                                  │
│                    ├── writes transient agent.yaml                   │
│                    │     (model, instruction, mcp toolset ref)       │
│                    │                                                  │
│                    └── spawns: docker agent run agent.yaml           │
│                          stdin: rendered prompt                       │
│                          stdout: final assistant message              │
│                                                                       │
│  on tool call from LLM:                                              │
│      MCP request ─► ToolDispatcher                                   │
│         1. validate args against ModuleSpec.input schema             │
│         2. wrap each arg as a one-shot channel value                 │
│              (val→Channel.value, path→Channel.fromPath, etc.)        │
│         3. invoke ModuleDef.apply(channels) — full dataflow path:    │
│              executor, container, retries, work dir, caching all     │
│              behave exactly as a normal pipeline invocation          │
│         4. await ChannelOut, serialize each output per .output       │
│              schema (path → absolute string handle, val → JSON)      │
│         5. return MCP tool result                                    │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
                           │ MCP over stdio
                           ▼
                   docker-agent (Go subprocess)
                   ├── LLM client (per `model`)
                   └── MCP toolset (connects back via stdio)
```

### Per-invocation lifecycle

1. `AgentTaskRun` materializes the tool list, resolving each reference to a `ModuleSpec` and a callable `ModuleDef`.
2. Renders the `prompt:` template against input bindings.
3. Generates a transient `agent.yaml` (under the agent's work dir) referencing the in-process MCP toolset and the configured model + instruction.
4. Spawns `docker agent run` as a subprocess. Stdin = rendered prompt. Stdout = captured for the final assistant message.
5. While the subprocess runs, MCP tool-call requests arrive via stdio. Each is dispatched to a real Nextflow module invocation — see §4.
6. Subprocess exits. Final assistant message is bound to the agent's `output:` channel.

### Why subprocess-per-invocation

Matches Nextflow's task-isolation model: each agent invocation has its own work dir, its own logs, its own retry semantics. Lower throughput than a long-lived agent daemon, but operationally consistent with how every other task type runs. A pooled-runner optimization is a future change, not a v1 requirement.

## 4. Tool bridge: ModuleSpec → MCP tool

The bridge consumes the already-validated `ModuleSpec` (loaded by `ModuleSchemaValidator`, introduced in 26.04 via commit `571274552`) and produces:

| `ModuleSpec` field | MCP tool field |
|---|---|
| `name` | tool name (registry-qualified, e.g. `nf-core/fastqc`) |
| `description` | tool description |
| `tools[].description` (joined) | appended to tool description for richer LLM context |
| `input[]` (paramSpec list) | JSON Schema `properties` — each param's `name`, `type`, `description` |
| `output[]` (paramSpec list) | JSON Schema for the structured tool result |

### Per tool-call flow

The steps below describe how the harness handles a **single LLM tool-call** — i.e. one invocation of one of the modules listed in the agent's `tools`. They are distinct from the agent's own `input:` block (covered in §2), which is rendered into the prompt template once per agent invocation.

1. **Arg validation** — JSON Schema check of the LLM-provided args against `ModuleSpec.input`. Failure returns an MCP error to the LLM (counts toward `maxIterations`).
2. **Channel materialization** — for each tool-call arg, build a one-shot input channel matching the module's declared input shape:
   - `val` arg → `Channel.value(arg)`
   - `path` arg → `Channel.fromPath(arg)` (the LLM passes a path string previously emitted by another tool — a handle, not contents)
   - `tuple` arg → composed value channel matching the tuple shape
3. **Module invocation** — `ModuleDef.apply(channels)` returns a `ChannelOut`. The module runs on whatever executor and container the user has configured for it; nothing in the agent path overrides that.
4. **Output serialization** — collect the first record from each output channel; map back per `output[]` paramSpec:
   - `path` → absolute path string (handle)
   - `val` → JSON-serialized value
   - `tuple` → object with named fields
5. **MCP response** — single structured result; LLM continues its loop.

### Path handling (v1 contract)

Paths are **opaque handles**. The LLM sees absolute path strings, passes them between tools, and never reads or writes the contents directly. The system prompt the harness prepends to `instruction` should make this contract explicit so the LLM does not attempt to inline file contents.

## 5. Configuration & credentials

`nextflow.config` gains an `agent` scope:

```groovy
agent {
    defaultModel        = 'openai/gpt-5-mini'
    dockerAgentBinary   = 'docker'        // or 'docker-agent'
    maxIterationsDefault = 20
}
```

API keys are read from the launching environment (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.) and forwarded to the docker-agent subprocess. No new secret-management surface in v1.

Docker Desktop ≥ 4.63 (or a Homebrew `docker-agent` install) is a runtime prerequisite. If the binary is missing at script load, fail fast with a clear installation hint.

## 6. Error handling & limits

| Failure | Behavior |
|---|---|
| `docker-agent` binary missing | Fail fast at script load with installation hint |
| Tool (module) invocation fails | Return MCP error; LLM may recover or give up |
| LLM run exceeds `maxIterations` | Subprocess exits non-zero → AgentTaskRun fails (standard Nextflow retry/error strategy applies) |
| LLM provider error (rate limit, network) | Surface as task failure; user opts in via `errorStrategy 'retry'` |
| Schema validation fails on tool args | Return MCP error to LLM; counts toward iteration budget |
| MCP transport error / subprocess crash | AgentTaskRun fails with stderr captured |

Agents inherit the standard process directives `errorStrategy`, `maxRetries`, `time`, `cache`. No parallel retry logic.

## 7. Module layout

New code:

- `modules/nf-lang/src/main/antlr/ScriptParser.g4` — add `agentDef` rule, parallel to `processDef`. AST node + visitor support in the v2 parser package.
- `modules/nextflow/src/main/groovy/nextflow/script/AgentDef.groovy` — runtime model for an agent definition.
- `modules/nextflow/src/main/groovy/nextflow/script/AgentFactory.groovy` — construction from script body.
- `modules/nextflow/src/main/groovy/nextflow/agent/` — new package:
    - `AgentTaskRun.groovy` — per-invocation orchestrator.
    - `AgentConfig.groovy` — `agent` config scope binding.
    - `McpToolBridge.groovy` — in-process stdio MCP server.
    - `ModuleToolAdapter.groovy` — one `ModuleSpec` → one MCP tool descriptor + dispatcher.
    - `DockerAgentRunner.groovy` — subprocess management, transient `agent.yaml` generation.

Existing infrastructure reused:

- `nextflow.module.ModuleSpec` and `nextflow.module.ModuleSchemaValidator` (26.04+) — the spec source.
- Standard include/registry resolution machinery — for resolving `tools` references.
- `ProcessDef` / `ChannelOut` / dataflow runtime — for actually executing tool invocations.

### Why core module rather than plugin

The `agent` keyword is a language-level construct that requires parser support; it cannot live in a plugin. A future `nf-agent` plugin could host alternative LLM runners (in-JVM clients, alternative backends) by implementing a runner SPI. The bridge and DSL stay in core.

## 8. Open questions (resolved)

| # | Question | Resolution |
|---|---|---|
| 1 | Path representation | String handle (absolute path) — opaque to the LLM |
| 2 | Tool source | Modules from registry or local include — leverages `meta.yml` |
| 3 | LLM↔dataflow mapping | Adapter wraps args as channel values, awaits outputs, serializes back — see §3 |
| 4 | Agent-as-tool | Not in v1 |
| 5 | MCP transport | stdio (simpler for PoC; no port management) |

## 9. Future extensions

- **Typed/structured LLM outputs** — once record-types lands, agents declare typed `output:` and the runtime requests a JSON-schema response from the LLM, validated and bound to typed channel records.
- **Multiple outputs and `path` inputs/outputs at the agent boundary** — currently restricted to single `val` output for simplicity.
- **External MCP toolsets** — treat any MCP server (Seqera Cloud MCP, third-party) as another `tools` source via the same adapter shape. Aligns with the Foundry pattern, inverted (Nextflow as host).
- **Agent-as-tool** — allow one `agent` to be referenced in another agent's `tools` list, enabling hierarchical agent composition without channel plumbing.
- **Pooled / long-lived runner** — replace subprocess-per-invocation with a managed daemon for higher throughput when many short agent calls happen in a workflow.
- **Streaming partial outputs** — surface intermediate LLM tokens or tool-call traces to channels for live observability.
