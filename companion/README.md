# Entropy Companion

A client-only NeoForge mod (Minecraft 1.21.1) for the owner's own game. Every couple of seconds it POSTs
`{"name","x","y","z","dim","at"}` to the Entropy Bot dashboard (`<url>/api/owner`, key in an `X-Key` header),
so the bot knows where the owner is beyond its own render distance. It needs nothing on the server and
nothing from the bot's mod.

Config: `config/entropy-companion.json` (`enabled`, `url`, `key`, `intervalSeconds`), created on the first
run; nothing is sent while `url` or `key` is empty. Build: `gradlew build` (JDK 21). Details and the
bot-side pieces: `docs/companion.md` in the minecraft-bot repo.
