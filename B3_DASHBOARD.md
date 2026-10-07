# B3 dashboard note: `/api/actions` (not built; for the dashboard's owner)

B3 (0.22.4) adds the action table document. The dashboard (`minecraft-bot/dashboard/Dashboard.java`) should serve it
as `GET /api/actions` (key required, like `/api/state`):

1. First the mod's fast channel: `GET http://127.0.0.1:<fast port>/actions` with the header `X-Bot-Key` (the same
   call shape as `/state` in `Dashboard.java`). 200: pass the body through with `Content-Type: application/json` and
   `X-Actions-Via: fast`. 503 means "not in a world".
2. Else (old mod, game starting): read `<instance>\minecraft\entropybot\actions.json` and send it with
   `X-Actions-Via: file` (it is rewritten when the state summary changes, checked every 5 s, so it may be a few seconds
   old: its `t` field is the write time in ms).
3. Neither: 503 `{"error":"no actions document (mod 0.22.4+)"}`.

Shape: `{version, how, actions:[{id, verb, in?, pre, eff, cost, plan}], verbs:[{v, use, who, act|not}], goals, state:{stage,
bag, freeSlots, at, needs, goals, areas, places, routines, job, brain, why}, t}`. The table part is under 30 KB
(JUnit `documentShapeAndSize`); the state adds a few KB. Never cache it longer than 5 s. No page is needed; a model or
`curl` reads it.
