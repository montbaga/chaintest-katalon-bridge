# ChainLP for this bridge

ChainLP is ChainTest's own optional server component for real-time
dashboards and cross-run historical trends. It is not required for the
static HTML report this bridge generates by default - only bring this up
if you specifically want that extra analytics/history layer.

## Before anything else: two addresses, one rule

Everything below - the default local setup, the optional tunnel, ChainLP
in CI - is one idea applied in different places. Understanding it now
makes every section below self-explanatory instead of a list of commands
to copy without knowing why.

**The rule:** there is no credential anywhere in this bridge's connection
to ChainLP - no API key, no token, no password to generate, store in a CI
variable, rotate, or ever risk leaking. Neither this bridge nor
`ChainTestApiClient` (chaintest-core's own HTTP client, used by every
generator that talks to ChainLP) authenticates with one at all. That's
one less secret to manage per project, and one less thing that can leak
out of a CI config across your entire fleet of runners - reporting
traffic just doesn't carry a credential worth stealing in the first
place. The trade-off: since there's no login to check, wherever CI or a
local Katalon run *writes* results must instead have **no login in
front of it** - safety comes from nothing untrusted being able to reach
that address at all, not from a password. A *person* opening a browser is
a completely different case: a human can type a password, so a login
there is normal and expected.

That gives exactly two addresses, always - never one address trying to
serve both jobs:

| | **Write address** (CI / local Katalon runs push results here) | **View address** (a person opens this in a browser) |
|---|---|---|
| **This bridge's default setup, one machine** | `http://localhost:8085/` - no login. Safe because nothing outside this machine can reach `localhost`. | The same `http://localhost:8085/` is fine too, *if* you're viewing from that same machine - see "Remote access" below only once you need to view it from somewhere else. |
| **This bridge's default setup, viewed remotely** | Still `http://localhost:8085/` - unchanged. | The Cloudflare Tunnel URL (`https://*.trycloudflare.com`) from "Remote access" below - login required, because a public tunnel URL is reachable by anyone. |
| **A real deployment, your own infrastructure** | A private address only your own network can route to - e.g. `http://chainlp.internal.yourco.net:8080/` - still **no login**, but now a firewall/VPC/VPN (not a password) is what keeps outsiders out. Every CI runner's `CHAINTEST_GENERATOR_CHAINLP_HOST_URL` points here. | A real public or company-internal domain - e.g. `https://chainlp.yourco.com/` - sitting behind a real login (basic auth, or more likely your company's own SSO/reverse proxy). Teammates open this and authenticate normally. |
| **ChainLP already exists elsewhere, already behind a login you don't control** | A small local proxy (`http://localhost:8086/`, see "Writing to a ChainLP that's behind a login" below) that holds the real credential and injects it - still no login *from the bridge's side*, satisfying the rule without touching the real login at all. | Whatever login that existing ChainLP already has - unchanged, nothing to set up. |

Both rows in any column point at the **same underlying ChainLP data** -
this is one ChainLP instance viewed through two different doors, not two
separate systems to keep in sync. Scaling this bridge up from a laptop to
real infrastructure means replacing the *mechanism* behind each column
(a private network instead of `localhost`, a real SSO gateway instead of
a Cloudflare tunnel's basic auth) - the shape never changes: one
open, trusted-network-only address for machines, one authenticated
address for humans.

If you only remember one sentence from this page: **never point
`CHAINTEST_GENERATOR_CHAINLP_HOST_URL` at an address that has a login in
front of it** - that push will simply fail with 401, on this laptop setup
or in a full production deployment alike.

## Configuration cheat sheet: every scenario, on any CI platform

There are exactly four configurations. Everything else in this document
is one of these four, explained in more depth.

| Scenario | `CHAINTEST_GENERATOR_CHAINLP_HOST_URL` | What else needs to exist |
|---|---|---|
| Local run, no ChainLP | *(unset - static report only)* | Nothing |
| Local run, ChainLP with no password | `http://localhost:8085/` | `docker compose up -d` in this folder |
| CI, ChainLP with no password | `http://localhost:8085/` (native/shell runner) or `http://host.docker.internal:8085/` (Docker-executor runner) | A self-hosted runner/agent on the same machine as ChainLP (see "ChainLP in CI" below) |
| CI, ChainLP has a password | `http://localhost:8086/` (native/shell runner) or `http://host.docker.internal:8086/` (Docker-executor runner) | Run `write-proxy/setup.sh` (or `.ps1`) once on the runner's machine - the only thing you'll be asked for is the real URL and credential (see "Writing to a ChainLP that's behind a login" below) |

`8085`/`8086` are this bridge's chosen defaults, not reserved or
guaranteed-free ports - if something else on your machine already uses
one, change it (see "Bring it up" / "Writing to a ChainLP that's behind
a login" below for exactly where).

**None of this changes between GitLab CI, GitHub Actions, and Azure
Pipelines.** The two environment variable names above, the four rows in
this table, and the write-proxy itself are identical on every platform -
there is nothing GitLab-specific about any of it, even though GitLab CI
happens to be the one with a fully worked example elsewhere in this repo
so far. What *does* differ per platform is only:

- **Where you register a self-hosted runner/agent** - GitLab: *Settings →
  CI/CD → Runners*; GitHub: *Settings → Actions → Runners*; Azure:
  *Organization Settings → Agent pools*.
- **Where you set CI secrets/variables** - GitLab: *Settings → CI/CD →
  Variables*; GitHub: *Settings → Secrets and variables → Actions*;
  Azure: *Pipelines → Library → Variable groups*.
- **The pipeline file's own YAML syntax** for declaring environment
  variables and which runner/agent a job uses.

Whichever platform you're on, once the runner/agent is registered and the
two variables above are set as CI secrets, the rest of this page applies
exactly as written.

## Bring it up

```bash
docker compose up -d
```

This starts two containers:

- `chainlp` - the real ChainLP server (pinned to `0.0.9`), with an embedded
  H2 database persisted in a named Docker volume, `chainlp-data`. Not
  published to the host directly.
- `chainlp-proxy` - a small nginx reverse proxy in front of it, published
  on `http://localhost:8085/`. It forwards everything through unchanged
  except for one small, well-contained fix - see "Known limitation" below.

Docker Compose waits for `chainlp` to report healthy before starting the
proxy, so a `curl`/browser hit immediately after `up -d` should already
work rather than racing a cold start.

**`8085` is just this bridge's chosen default, not a reserved or
guaranteed-free port.** If something else on your machine is already
using it, `docker compose up -d` fails outright with an error like
`Bind for 127.0.0.1:8085 failed: port is already allocated`. If that
happens, change it in **two places, kept in sync**:
1. `docker-compose.yml`, in this same folder - change `"127.0.0.1:8085:80"`
   under `chainlp-proxy` to whatever port you want instead.
2. `chaintest.properties` - change `chaintest.generator.chainlp.host.url`
   to match that same new port.

Then run `docker compose up -d` again.

## Point the bridge at it

In the target Katalon project's `Include/config/chaintest/chaintest.properties`:

```properties
chaintest.generator.chainlp.enabled=true
chaintest.generator.chainlp.host.url=http://localhost:8085/
```

Or the equivalent environment variables:

```
CHAINTEST_GENERATOR_CHAINLP_ENABLED=true
CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://localhost:8085/
```

Run your tests as usual - the static report still generates normally
alongside this.

<details>
<summary><b>Known limitation (fixed by the proxy)</b> - background info, nothing to do here</summary>

ChainLP's build-detail page shows 3 summary charts (pass/fail/skip counts
per hierarchy level). To label them, its frontend looks up the test
runner name in a hardcoded list - only `testng`/`junit`/`junit5`/
`cucumber-jvm` are in it. This bridge honestly reports itself as
`katalon` (it isn't any of those), so without a fix, that lookup returns
nothing and the entire summary section - titles, charts, and even the
pass/fail/skip counts underneath them - silently fails to render.
Confirmed directly with a headless-browser render of the real page, not
just by reading the source: the boxes come back completely empty, not
just missing a label.

Renaming ourselves to `"testng"` would make the boxes render, but would
mean every build in ChainLP permanently claims to come from TestNG, which
it doesn't - a bad trade for a cosmetic fix.

Instead, `chainlp-proxy`'s [`nginx.conf`](proxy/nginx.conf) makes one
targeted text substitution in ChainLP's own already-compiled frontend
JS response, adding a `katalon` entry right alongside the existing four:

```
testng:["Suite","Class","Method"]
  -> testng:["Suite","Class","Method"],katalon:["Suite","Test Case","Step"]
```

This is a plain substring replacement on the response body (`sub_filter`),
not a fork, patch, or rebuild of ChainLP's own source - nothing here
depends on a specific bundle filename (those are content-hashed and change
on every ChainLP release). If a future ChainLP version reformats that
object, the substitution simply stops matching and the page reverts to
today's default (blank summary boxes, same as without this fix) - it
cannot corrupt or break the response either way. Everything else -
project/build/test data, the test list, filtering, tags - is unaffected
by this either way, since it's a completely separate part of the same
page.

**If you already opened `localhost:8085` in a browser before running
`docker compose up -d` with this proxy in place**, hard-refresh
(Ctrl+Shift+R) once - your browser may have cached the unpatched JS file
from before the proxy existed.

**If you want to bypass the proxy** (e.g., to confirm this fix is really
what's responsible for something), temporarily add `ports: ["8086:80"]`
under the `chainlp` service in `docker-compose.yml` and compare
`localhost:8086` (direct, unpatched) against `localhost:8085` (through
the proxy).

</details>

<details>
<summary><b>Optional: viewing the dashboard from a different device</b> - skip this entirely if you only ever look at ChainLP from this same machine</summary>

## Remote access (viewing the dashboard from somewhere other than this machine)

By default ChainLP is only reachable as `http://localhost:8085/` - fine if
you're only ever looking at it from the same machine that's running
Docker. If you want a real, public URL (to check the dashboard from
another device, or share it), this repo includes an optional tunnel using
[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/)'s
free "quick tunnel" - no account, no domain, no router configuration.

**Important: ChainLP has no login of its own at all** - confirmed by
checking its backend, there's no authentication dependency anywhere in it.
Anyone who can reach it can view *and delete* every project/build/test.
That's a non-issue on `localhost`, but a real one the moment it's
reachable from the public internet - so the tunnel here is deliberately
built to always sit behind a login, with no way to start it without one.

### Set it up

```bash
# 1. Choose a username/password for the login (only needed once, or
#    whenever you want to change it):
./remote-access/generate-htpasswd.ps1 -Username yourname      # Windows
./remote-access/generate-htpasswd.sh yourname                 # macOS/Linux

# 2. Start the tunnel (not started by plain `docker compose up -d`):
docker compose --profile tunnel up -d

# 3. Find the URL it generated:
docker logs chaintest-katalon-chainlp-tunnel 2>&1 | grep trycloudflare
```

That last command prints a line like
`https://some-random-words.trycloudflare.com` - that's your real, public
HTTPS URL. Open it and you'll get a login prompt for the username/password
from step 1.

### How this fits together

- **This bridge's own results still go to `http://localhost:8085/`**,
  never through the tunnel or the login. `chaintest.properties` doesn't
  need to change at all for the tunnel to work. This is deliberate, not
  just simpler: ChainTest's own HTTP client (`ChainTestApiClient`, inside
  `chaintest-core`) has no way to send login credentials at all, so if the
  bridge's results had to go through the login, they'd simply fail with
  401 Unauthorized. Since your Katalon tests and this Docker stack run on
  the same machine, there's no need to route that traffic through the
  tunnel anyway.
- **The tunnel and its login are purely for *viewing*** the dashboard from
  somewhere else. `chainlp-tunnel-auth` (a second, separate nginx, only
  created when you pass `--profile tunnel`) is the only thing the tunnel
  talks to, and it's the only thing the login is checked against - the
  always-on local port 8085 is completely unaffected by any of this,
  running or not.

### Real limitations, stated plainly

- **The URL changes every time the tunnel restarts.** This is Cloudflare's
  free "quick tunnel" mode, which doesn't need an account - the trade-off
  is a random subdomain each time, not a stable address. A stable one
  (e.g. `chainlp.yourdomain.com`) is possible with a free Cloudflare
  account, but only if you already own a domain to attach it to.
- **The URL only works while your laptop and this Docker stack are
  running.** There's no separate hosting involved - this is your machine,
  tunneled out, not a hosted service.
- **A remote CI runner can't push results through this tunnel** - it's
  built for viewing only, per the two bullets above. If what you actually
  need is a CI runner pushing into a ChainLP that sits behind someone
  else's login (a company SSO, a different team's reverse proxy), see
  "Writing to a ChainLP that's behind a login" below instead - a
  different mechanism, not a limitation of this one.

### Stopping it

```bash
docker compose --profile tunnel down
```

This stops and removes the tunnel and its login proxy; `chainlp` and
`chainlp-proxy` (the always-on local path) keep running untouched.

</details>

<details>
<summary><b>Optional: your real ChainLP already exists elsewhere and has a login</b> - skip this if you're just using the ChainLP this bridge brings up itself</summary>

## Writing to a ChainLP that's behind a login

Everything above assumes the ChainLP you're pushing to is the one this
bridge's own `docker-compose.yml` brings up, always reachable without a
login. But if your real ChainLP already exists somewhere else - your
company hosts one behind its own SSO, a different team runs one behind a
reverse proxy with basic auth - the bridge still can't authenticate to it
directly, for the same reason as always: `ChainTestApiClient` has no code
path for sending credentials at all, so a direct push to a login-protected
URL just fails with 401.

The fix isn't to teach the bridge how to log in - it's a small,
credential-injecting proxy that sits between the bridge and that remote
ChainLP: the bridge talks to this proxy with **no credentials at all**
(same as it always does), and the proxy is the one thing that actually
holds the real login, attaching it to every request before forwarding
upstream. This is included at [`write-proxy/`](write-proxy/) -
`chainlp-write-proxy`, a small standalone nginx container, separate from
(and not started by) this folder's main `docker-compose.yml`.

This is identical on GitLab CI, GitHub Actions, and Azure Pipelines - the
write-proxy doesn't know or care which CI platform is talking to it, and
nothing about it is GitLab-specific. See the "Configuration cheat sheet"
table above for the exact value to set on any of them.

### Set it up

```bash
cd write-proxy
./setup.sh          # macOS/Linux
./setup.ps1         # Windows
```

This asks three questions - your real ChainLP's URL, whether it logs you
in with a username/password or a single API token, and that credential
itself - and handles everything else: encoding it into the correct
`Authorization` header format, writing `.env`, and starting the
container. It finishes by printing the exact value to set in your CI
pipeline. Re-run it any time to change the URL or credential.

The only manual step here, ever, is typing in your real credential -
nobody else can know that. Everything else (the encoding, the file, the
container) is one command, once.

<details>
<summary>Doing it by hand instead (if you'd rather not run the script)</summary>

```bash
cd write-proxy

# Real ChainLP address, and the exact Authorization header value it
# expects - e.g. for basic auth, base64-encode "user:password" yourself
# first (setup.sh/.ps1 does this step for you; doing it by hand doesn't):
echo 'CHAINLP_REMOTE_URL=https://chainlp.yourco.com' > .env
echo 'CHAINLP_REMOTE_AUTH_HEADER=Basic dXNlcjpwYXNz' >> .env

docker compose up -d
```
</details>

Then point the bridge at *this proxy*, never at the real remote URL
directly:

```
CHAINTEST_GENERATOR_CHAINLP_ENABLED=true
CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://localhost:8086/
```

**Same caveat as `chainlp-proxy`'s `8085`: `8086` is just a default, not
guaranteed free.** If something else is already using it, change
`"127.0.0.1:8086:80"` in `write-proxy/docker-compose.yml` to a different
port, update the URL above to match, and restart with
`docker compose up -d` (from inside `write-proxy/`).

### Where this needs to run

**Reaching this proxy's port is equivalent to having the real
password.** It doesn't check who's asking - every single request that
reaches it gets the stored credential attached and forwarded, a curl
command and a person's browser alike. Confirmed directly: opening
`http://localhost:8086/` in a plain browser tab loads the real ChainLP
dashboard with no login prompt at all, because the proxy already
authenticated on the browser's behalf without being asked to. That
makes this port strictly *more* sensitive than `chainlp-proxy`'s port
8085 earlier in this doc, not equally sensitive - 8085 only ever exposes
your own local, unauthenticated ChainLP, while this one is a working key
to someone else's real, otherwise-protected one.

So: put it somewhere only your own CI runners (and anyone else you'd
trust with the real password directly) can reach, never exposed to the
public internet, and never on a shared/multi-tenant machine where other
people's jobs could also reach `localhost`. Concretely, that's usually
right next to your CI runners themselves - on the runner
machine for a self-hosted setup, or somewhere on the runners' own private
network for a fleet of them. It does **not** need to run next to the real
ChainLP - that's the actual point of this: your CI runners' network and
wherever ChainLP is actually hosted no longer have to be the same place,
the way the plain "ChainLP in CI" section below still requires.

The credential itself lives only in `write-proxy/.env` on whichever
machine runs this proxy (already gitignored) - it's never visible to
Katalon, the bridge, or anything running inside your CI job.

</details>

<details>
<summary><b>Optional: pushing to ChainLP from CI</b> - not needed for local runs; read this once you're setting up a pipeline</summary>

## ChainLP in CI

CI pushing results into ChainLP is possible, but it only works if the CI
job and the ChainLP instance can reach each other **without** a login in
between - and per "Remote access" above, ChainLP has none of its own, so
that connection has to be inherently trusted rather than authenticated
(same machine, or the same private network). The one setup that satisfies
this without any new infrastructure is: **run the CI job itself on the
same machine that's hosting ChainLP**, using a self-hosted runner instead
of your CI platform's cloud-hosted ones. This works the same way on
GitLab CI (self-hosted runner), GitHub Actions (self-hosted runner), or
Azure Pipelines (self-hosted agent) - the underlying idea is identical,
only the registration steps differ.

A ready-to-copy template for GitLab CI specifically is included at
[`../gitlab-ci-selfhosted-chainlp.example.yml`](../gitlab-ci-selfhosted-chainlp.example.yml) -
every value that's specific to one setup (repo URL, test suite, runner
tag) is marked with an `<ANGLE_BRACKET>` placeholder rather than
hardcoded, since your own values for these will be different from anyone
else's. The walkthrough below is the platform-independent version of the
same idea, for GitHub Actions or Azure Pipelines instead.

### The shape of it, on any platform

1. Pick the machine: it needs Docker, and this bridge's `chainlp/` stack
   running (`docker compose up -d`). This can be (and usually is) your
   own laptop or workstation - nothing here requires a dedicated server,
   and there's no separate Katalon Studio install to set up on it: the
   pipeline itself runs Katalon inside Katalon's own official
   `katalonstudio/katalon` Docker image, so there's no local install path
   to know about or explain to anyone else running this.
2. Register a **self-hosted** runner/agent on that machine for your
   project, using the **Docker** executor.
3. In the pipeline, run the job inside `katalonstudio/katalon` (see the
   template for the exact `image:`/`before_script:` shape) and set:
   ```
   CHAINTEST_GENERATOR_CHAINLP_ENABLED=true
   CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:8085/
   ```
   **The host and port here are yours to determine, not a fixed value** -
   the port needs to match wherever *your own* `chainlp-proxy` actually
   ends up published, which comes from *your own*
   `chainlp/docker-compose.yml` (`8085` is only this bridge's shipped
   default under `ports:`). If you changed that mapping, use your own
   port instead. `host.docker.internal` (rather than `localhost`) is
   what lets the job's container reach back out to ChainLP running on the
   host machine itself - Docker Desktop for Windows/Mac resolves this
   automatically; native Linux Docker Engine needs
   `extra_hosts: ["host.docker.internal:host-gateway"]` added to the job.
4. Add your Katalon Runtime Engine API key as a masked/secret CI variable
   - used identically here as it would be anywhere else; switching to a
   Docker-image-based pipeline doesn't change anything about how the
   license itself is used.
5. This image only ships Chrome and Firefox, not Edge - use
   `-browserType="Chrome"` (or `"Firefox"`) rather than any Edge variant.

### What's different for someone else running this

Two people following this guide on two different machines/projects will
end up with two different, unrelated setups - nothing here is a shared
address. Concretely, each person's own copy will differ in:

- **The ChainLP URL itself**, per point 3 above - whatever port their own
  `docker-compose.yml` publishes it on. There's no shared or hosted
  ChainLP instance anywhere in this design; "put it at
  `http://host.docker.internal:8085/`" means *on that machine*, not a
  real address anyone else can reach.
- **The runner tag and any repo/project paths** in their pipeline config
  - these come from how *they* registered their own runner and laid out
    their own project, not from this bridge.
- **Whichever machine they personally open a browser to** for viewing the
  dashboard day-to-day - their own `localhost:8085` if they're on the
  runner machine itself, or their own tunnel URL from "Remote access"
  above if they set one up (which, per that section, also changes on
  every tunnel restart unless they've attached their own domain to it).

None of this needs to be reconciled between people or machines - each
setup is self-contained, exactly like running Katalon Studio itself
locally.

### Three non-obvious problems worth knowing about upfront

The Docker-image template above is the recommended default - it needs
nothing installed on the runner machine beyond Docker itself, so there's
no local Katalon Studio path to explain to anyone else running this. The
three problems below were found running an earlier variant of this same
idea that used a locally-installed Katalon Studio directly on a Windows
runner (via the shell executor) instead of this image - keeping them here
because the first two are specific to that path and worth knowing if you
ever have a reason to run Katalon natively on the runner instead of in a
container (e.g. you need Edge specifically, which this Linux image can't
provide):

- **Git checkout can silently fail on a locked-down Windows machine.**
  Some CI runners' default checkout (GitLab Runner in particular, using
  HTTPS with an embedded job token) can hit a Windows Application Control
  (WDAC) policy that blocks Git for Windows' own bundled `libcurl-4.dll` -
  the same failure this bridge's own git pushes could hit on such a
  machine. Confirmed via a real failed pipeline (`fatal: failed to load
  library 'libcurl-4.dll'`) and Windows' own CodeIntegrity event log, not
  guessed. This is specific to checkout running directly on a Windows
  host - inside a Linux container (the Docker-image template above),
  checkout uses the container's own Linux git instead, which isn't
  affected by a Windows host's WDAC policy. If you do hit this on a
  native Windows runner: disable the runner's automatic checkout (GitLab:
  `variables: GIT_STRATEGY: none`) and clone manually over SSH in the job
  script instead, using the machine's native OpenSSH client rather than
  Git's bundled one (`core.sshCommand` pointed at
  `C:\Windows\System32\OpenSSH\ssh.exe` avoids the same blocked DLL).
- **`-browserType` needs Katalon's exact string.** `"Edge"` fails with
  `Invalid browser: 'Edge'` (KRE exit code 4) - the real value is
  `"Edge Chromium"`, confirmed against a real Katalon 11.4.0 run. Not
  relevant to the Docker-image template, which uses `"Chrome"` instead
  (Edge isn't available in that image at all).
- **A runner installed without admin rights runs as a plain process, not
  a service.** Applies regardless of executor. Fine for trying this out,
  but it won't survive a reboot or logout - install it as an actual
  Windows service (needs an elevated prompt) once you're past the "does
  this work at all" stage.

### What you get, and what you don't

Both the static report and ChainLP end up populated from the same CI run
- the static report as a normal job artifact, ChainLP as a new build under
your project, same as a local run would produce. What this setup does
*not* give you: a second, independent machine (a real cloud CI runner)
exercising your tests, or a ChainLP instance reachable by anyone besides
whoever has access to this one machine. Both are reasonable trade-offs
while you're validating that the ChainLP integration itself works end to
end; moving beyond them means hosting ChainLP on real shared
infrastructure your cloud runners can reach - a separate, bigger decision
each team makes for itself.

</details>

<details>
<summary><b>Compatibility note</b> - background info, nothing to do here</summary>

## Compatibility note

ChainTest's own published client/ChainLP compatibility matrix
(`ChainLPSupportMatrix.md` in the ChainTest project) stops at client
version 1.0.11; this bridge uses 1.0.12, one release newer, which isn't
listed there. Confirmed working in practice with ChainLP 0.0.9 (pinned
above) via a real end-to-end run: builds, tests (including the full
suite/test-case/step tree), tags, and timing all arrive correctly. The
one gap found - the summary-chart rendering issue above - was in ChainLP's
frontend, unrelated to the client/server wire protocol itself.

</details>

## Removing it

```bash
docker compose down          # stop, keep the data volume
docker compose down -v       # stop and delete all stored history
```
