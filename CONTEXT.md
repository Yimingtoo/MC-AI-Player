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

## Flagged ambiguities

- "cancel" was used interchangeably with "Interrupt" during design — resolved: **Interrupt** is the user-facing action (Ctrl+C), distinct from general cancellation of individual operations.
