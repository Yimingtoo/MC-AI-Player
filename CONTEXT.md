# MCP Host

A MCP Host application that connects to Minecraft AI servers via the MCP protocol, using a large language model (DeepSeek) to drive an AI agent that performs tasks in Minecraft.

## Language

**User**:
A person interacting with the AI agent through the REPL.
_Avoid_: Player, operator, client

**Agent**:
The AI system (LLM + MCP tools) that receives user requests and executes them in Minecraft.
_Avoid_: AI assistant, bot

**Task**:
A single user request processed by the Agent, consisting of zero or more roundtrips.
_Avoid_: Job, mission, session

**Roundtrip**:
One LLM API call followed by zero or more MCP tool executions, within a single Task.
_Avoid_: Iteration, cycle, step

**Interrupt**:
A user-initiated (Ctrl+C) stop of the currently executing Task. An Interrupt discards all partial results from the current Task and returns control to the REPL.
_Avoid_: Cancel, abort, stop, kill

## Relationships

- A **User** submits a **Task** to the **Agent**
- A **Task** consists of one or more **Roundtrips**
- The **User** can **Interrupt** the **Agent** during any **Roundtrip**
- An **Interrupt** terminates the current **Task** and discards its state

## Example dialogue

> **Dev:** "When the User presses Ctrl+C during a long Task, what happens to the state?"
> **Domain expert:** "The Interrupt terminates the current Task. All partial results are discarded — the Agent's conversation state for that Task is rolled back. The User gets a clean prompt for the next Task."
>
> **Dev:** "What if an MCP tool call is already executing — say, the Agent already placed blocks in the world?"
> **Domain expert:** "The Host-side waits are cancelled, but the Minecraft-side effects that already happened are not rolled back. The Host cannot undo what the MCP Server already executed. The Interrupt only affects what the Host is waiting for."

# World Operations

## Region

A cubic volume of the Minecraft world defined by two corner coordinates (`from` and `to`), inclusive on all axes.

A **Region** is specified as a pair of `BlockPos` values — the minimum and maximum corners are computed internally (`min`/`max` of each axis), so the caller may supply any two opposite corners in any order.

_Avoid_: Area, zone, selection, box

## Scan

A read-only operation that iterates over all block positions within a **Region** and collects information about each block.

Initial implementation collects block names (aggregated count per block type). Future extensions may collect block states, light levels, biome data, or other properties.

_Avoid_: Query, inspect, survey, audit

## Monitor

A time-extended observation of a **Region** across multiple Minecraft game ticks (GT).

A **Monitor** captures an initial full snapshot of every block (including air) at GT=0, then for each subsequent GT up to a specified `durationTicks` count, records only the blocks that changed during that GT.

_Avoid_: Watch, observe, track

## MonitoringSession

The runtime state of a single **Monitor** instance. Created by `monitor_region_start`, queried by `monitor_region_get`, and automatically cleaned up when the session completes or expires.

A **MonitoringSession** is a singleton — only one active session may exist at a time. Starting a new session while another is active implicitly stops the previous one.

## Tick Sprint (future)

A mode where the server processes game ticks as fast as possible without waiting for real-time delay, used to accelerate **Monitor** completion. Not yet implemented.

## Flagged ambiguities

- "cancel" was used interchangeably with "Interrupt" during design — resolved: **Interrupt** is the user-facing action (Ctrl+C), distinct from general cancellation of individual operations.
