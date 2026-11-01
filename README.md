<p align="center"><img src="modrinth/icon.png" width="180" alt="Dramatic Faces logo"></p>

<h1 align="center">Dramatic Faces</h1>

<p align="center"><b>Beta.</b> Your Minecraft skin reacts to your voice and to the game.</p>

> **This is a beta.** Things can change, and detection is still being tuned. Multiplayer hasn't been tested much yet.

## Features

- **Voice emotions:** laughing, shouting, panicking, whispering and sad faces, all from how you sound
- **Lip sync:** the mouth changes shape with the sounds you make
- **Living eyes:** your skin's own eyes blink, look around and turn toward whoever is talking
- **Game reactions:** faces react to getting hit, creepers, eating, drinking and low health
- **Awkward silence:** when a conversation dies, everyone starts sweating
- **Settings:** switch every feature on or off through Mod Menu

## Privacy

Dramatic Faces only measures how loud and how high a voice is. Words are never understood, and nothing is recorded or sent anywhere.

## Requirements

- Minecraft 26.3 with Fabric Loader 0.20.1 or newer
- Java 25
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat), on the server too
- [Mod Menu](https://modrinth.com/mod/modmenu) (optional, for the settings screen)

It's a client-side mod: the server doesn't need Dramatic Faces, but other players need it to see the faces. If you're muted, your face won't react to your voice. Game reactions still work.

## Building

```
./gradlew build
```

The jar ends up in `build/libs`.

## License

Copyright (c) 2026 qfxdev. All rights reserved. See [LICENSE](LICENSE). The code is public so you can read it, not to be reused or redistributed.
