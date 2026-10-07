# B2 verb rows for the merge agent (VerbTable / Texts / COMMANDS.md / MENU.md are owned by V1a)

Wired in B2 (dispatch only): `why threats` (Commands.java, before the plain `why`), `debug threats` (DebugVerbs).

VerbTable rows to add or extend:
- `why`: usage `why | why threats`; what: "what the autominer decided and why; `why threats`: the threat test's last
  fight-or-flee verdict, each mob near now and the last 32 changes, with aggro y/n, path N (straight M), distance -1/s ->
  counts|noted"; examples `why threats`.
- `debug`: add `debug threats` to the usage ("the threat test's counters: grid copies and ms, search ms, mobs, counted /
  noted, fallbacks, errors"); `debug threats x y z`: the last search at one block (walk moves to me, the column's cell codes).

COMMANDS.md row (defence section):
| what are you scared of? / why didn't you fight that zombie? | `why threats` (each mob: aggro, path to me, closing in -> counts or noted; a zombie on a roof with no path is noted, not fought) |

MENU.md line: `why threats` - which mobs count as threats and why; `debug threats` - its timings.

state.json `mobs[]` gained `reach` (blocks, null = no path), `closing`, `threat` (absent until sampled);
`defence.threat` = {grid: ok|fallback, ms, errors}.
