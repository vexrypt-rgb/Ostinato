# Command center

The command center is a separate desktop window. It is not a Minecraft screen. It stays closed until you open it. Closing it does not stop the swarm or any bot.

## Open

In chat:

```
#center
#center close
#center layout
#center layout wall
#center layout map
```

`wall` is the mission-control grid and the default. `map` is a war-room placeholder (no map, no click-to-command). The last choice is saved per OS user as `command-center-layout-<user>.txt` under the Ostinato config directory (`<game>/baritone`).

The same open path is `baritone.swarm.center.CenterRuntime#open`. `#center` calls that hook. The window installs itself as the host when the client starts and does not show until the hook runs.

## Placement and focus

The window follows `-Dostinato.windowPos=x,y`. When that property is unset, the default is `2600,40`, beside the usual game-window position. If that point is not on any monitor and a second monitor is present, the window sits on the second monitor. It does not take focus when it opens. Nothing in the window raises an alert dialog. A click in the window can focus a field so you can type.

## Buttons

Spawn, despawn, goto, mine, cancel, stop-all, and the layout controls are forms on the same registry as the chat commands. A button cannot name a command that chat does not have. Owner-only commands, including spawn and despawn, run from this window only for the owner. They are refused for a swarm member, an AI tool call, or a public caller.

Goto and mine on another bot's tile are sent as `#swarm send <bot> <line>` after both that line and the send are allowed. Despawn stays `#swarm despawn`. Spawn still goes through `#swarm spawn`, including the memory guard.

## Accounts

Bot accounts are added from this window. Sign-in needs an Azure app registration that you configure. There is no built-in client id. See [bot-accounts.md](bot-accounts.md).
