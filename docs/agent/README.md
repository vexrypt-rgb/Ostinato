# AI agent API

Foundation for in-game chat and later overnight hypothesis races. Both use the same provider settings. Nothing here runs inside pathfinding. `agentApi` and `agentChat` default off, so chat, paths, and the Pi search heap stay as they are.

## Switches

| Setting | Default | Role |
| --- | --- | --- |
| `agentApi` | off | Master switch. Off, `#agent` does not open a connection. |
| `agentChat` | off | In-game chat hook. Needs `agentApi` as well. |
| `agentProvider` | empty | `groq`, `gemini`, `openrouter`, `ollama`, or empty for auto. |
| `agentBaseUrl` | empty | OpenAI-compatible base URL. Empty uses the preset. |
| `agentModel` | empty | Model id. Empty uses the preset default. |
| `agentApiKey` | empty | Key. Prefer an environment variable. Never printed in chat, tab-complete, or the settings screen. |
| `agentDryRun` | on | Print queued commands. Do not run them until this is turned off. |
| `agentOwnerOnly` | on | Only the local player, or `agentOwner` when set, may use `#agent`. |
| `agentOwner` | empty | Name that must match the sender. Empty allows the local player. |
| `agentKeyFile` | empty | File whose first line is a key, after the setting and the environment. Never logged. Does not by itself pick a cloud host. |
| `agentOneSession` | on | One provider call at a time. Status and routes do not take the lock. |
| `agent429ToOllama` | on | On HTTP 429, retry that turn once against Ollama. |
| `agentMemory` | on | Last few turns in `baritone/agent-memory.jsonl`. Role and text only. |
| `agentTools` | `goto,mine,follow,settings,route` | Whitelist. Unknown names are ignored. `set` means settings. |
| `agentRouteDir` | `baritone/agent-routes` | Saved task routes, relative to the game directory. |

These options are in the settings screen under Advanced, with the other Ostinato settings. `agentApi` and `agentChat` stay off until you turn them on. The other defaults apply once those two are on.

Environment variables, used when `agentApiKey` is empty:

- `GROQ_API_KEY`
- `GEMINI_API_KEY`
- `OPENROUTER_API_KEY`
- `OSTINATO_AGENT_API_KEY` (used for the selected provider, or with `agentBaseUrl`)

Auto, with no provider and no base URL, picks the first cloud key it finds, in the order Groq, Gemini, OpenRouter. If none is set, it uses Ollama. A named cloud provider with no key also falls back to Ollama. `agentProvider ollama` stays on Ollama even when a cloud key exists.

`#agent status` prints the resolved provider, model, and base URL, and whether a key is present. It does not print the key and it does not call the provider.

## Providers

The client is `POST {baseUrl}/chat/completions` with `java.net.http`, async, 10s connect timeout and 45s request timeout. Redirects are not followed. The key is sent only as `Authorization: Bearer`, and only when one is configured.

| Provider | Base URL | Default model |
| --- | --- | --- |
| Groq | `https://api.groq.com/openai/v1` | `openai/gpt-oss-20b` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-3.8-flash` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openrouter/free` |
| Ollama | `http://localhost:11434/v1` | `llama3.2` |

Groq, Gemini, and OpenRouter keys come from each vendor's console. OpenRouter's `openrouter/free` model routes across the current free models. Ollama needs a local server (`ollama serve`) and a pulled model (`ollama pull llama3.2`). Set `agentModel` to the tag you pulled. Ollama is not sent a key.

Any other OpenAI-compatible server works by setting `agentBaseUrl` and, if it needs one, `agentApiKey` or `OSTINATO_AGENT_API_KEY`. A non-local URL with no key falls back to Ollama.

## Chat

Turn both switches on, then:

```
#agent walk to 100 64 100 and mine some stone
#agent status
```

The request leaves the game thread. The reply is printed in chat, truncated to 500 characters. The model can enqueue up to four commands through the tool interface:

- `goto` maps to `#goto`
- `mine` maps to `#mine`
- `follow` maps to `#follow`
- `settings` maps to `#set`

`settings` refuses `agent*` and any name containing key, secret, password, or token, so a reply cannot move the endpoint or reveal the key. A text line `TOOL mine block=stone count=4` is accepted when the provider returns no tool calls. Names outside `agentTools` are skipped. A full multi-step tool loop is not implemented.

With dry-run on (the default), queued commands are printed as `agent dry-run:` and are not executed.

Saved routes are local. They do not call the provider:

```
#agent route save wood mine 2 oak_log ; goto 1 64 2
#agent route run wood
#agent route list
```

Each save bumps a version in `{agentRouteDir}/{name}.json`. A step must be a whitelist command (`goto`, `mine`, `follow`, `set`), at most 200 characters, with no `#` and no newlines. `set` still refuses agent and secret settings. A `route` tool call is noted in chat and is not sent to Baritone; use the commands above to save and run.

HTTP 429 retries the same turn once on Ollama, with no key and no further retry. If the selected provider is already Ollama, a 429 is just an error.

Chat memory, when `agentMemory` is on, loads the last eight turns into the next request and appends the user line and the reply after a successful turn. The key is replaced with `(hidden)` before it is written.

## Races

`RaceRun` / `RaceRecorder` record a run id, hypothesis, agent, provider, model, metrics, and outcome. `JsonlRaceRecorder` appends one JSON line. There is no API-key field.

`every_block_item` is an opt-in training circuit that writes those same lines. It is off unless `raceEveryBlockItem` (or `-Dostinato.raceEveryBlockItem=true`) is set, and it is not part of the default suite. Coverage, the exclusion list, and the score fields are in [../race/README.md](../race/README.md).

`RaceWorldCleanup` deletes only direct child folders named `race_*`, `hyp_*`, or `agentrun_*`. It always keeps `vexflat` (any name starting with that), `pk_*`, `pkrun_*`, `SigilTest`, and `gs_world`. Other folders, including `New World`, stay. Deletion runs only when the directory contains `ostinato-race-root.txt`, so a normal saves folder is left alone. Tests use a temp directory.

## Safety

- Default off. Pathing sources do not reference this package.
- The key is stored in the settings file if you set it there. Chat, the GUI, tab-complete, and error text show `(hidden)`.
- No server-specific code. The bot does not join a server from this hook.
- One Gradle test run at a time, BelowNormal, `--max-workers=2`.

## Roadmap

Not in this change:

- Overnight multi-agent hypothesis races on the race recorder.
- Driving the live client UI to create and delete worlds. Cleanup of leftover race worlds is the policy above.
- A full tool-calling loop (combat, gear, and settings beyond the stubs).
- GPU batch sims and swappable tiny models.
