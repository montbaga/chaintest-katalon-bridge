# ChainTest-Katalon Bridge

Turn any Katalon Studio project into a ChainTest-reporting project by
double-clicking one file - no plugin installation from the Katalon Store, no
manual Test Listener authoring, no `CustomKeywords` calls pasted into your
test cases, no changes to existing Test Cases or Test Suites.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for how this is actually built
under the hood.

## Why this exists

Katalon Studio has no ChainTest integration of its own, official or
community. Without this bridge, wiring ChainTest into a Katalon project
means hand-authoring a Test Listener against ChainTest's own plugin-authoring
API (the same primitives its official TestNG/JUnit5/Cucumber integrations
are built on) and hand-managing its jars. This bridge ships all of that
pre-wired instead.

| Problem without this bridge | How this bridge solves it |
|---|---|
| No Katalon-specific ChainTest integration exists anywhere, official or community | Ships one, built directly on ChainTest's own plugin-authoring primitives (`Test`/`Build`/`ChainPluginService`) |
| Hand-manage ChainTest's jars and their real (non-obvious) runtime footprint yourself | Ships exactly the jars actually needed in `payload/Drivers/` - verified by running the generator standalone, not assumed from its Maven POM |
| Write your own Test Listener by hand | Ships a pre-wired one - `Test Listeners/ChainTestListener.groovy` |
| Manually convert Katalon's own execution log into report steps | Auto-converts it into nested ChainTest steps - zero-touch by default |
| A reporting bug could fail or change the outcome of a real test | Every hook catches its own exceptions and only logs a warning |

## Requirements

- Katalon Studio (built and tested against its Java 17/21 runtime)
- Windows, macOS, or Linux
- Nothing else for the default static report - no external commandline
  tool, no server, no Docker. Report generation happens entirely in-process
  using the jars in `payload/Drivers/`
- Docker only if you additionally turn on ChainLP (see below) for real-time
  analytics/history - the static report never needs it

## Install / Uninstall

<details>
<summary><b>📦 npm (any OS)</b></summary>

Needs Node.js. Works identically on Windows, macOS, and Linux.

```
npx chaintest-katalon-bridge install "/path/to/your/katalon/project"      # add --force to also overwrite a customized chaintest.properties
npx chaintest-katalon-bridge uninstall "/path/to/your/katalon/project"    # add --remove-config to also delete chaintest.properties/failure-tags.json
```

`npx` fetches and runs it without installing anything globally. To install
it once and reuse it: `npm install -g chaintest-katalon-bridge`, then run
`chaintest-katalon-bridge install ...` directly.
</details>

<details>
<summary><b>🪟 Windows/</b></summary>

**Just click:** double-click **`Windows\Install.bat`**, then pick your
Katalon project folder in the dialog that opens. Uninstall the same way
with **`Windows\Uninstall.bat`**.

**Drag-and-drop:** drop your project folder onto `Install.bat` (or
`Uninstall.bat`) to skip the dialog entirely.

**Scripted / CI** (run from the repo root):
```powershell
.\Windows\install.ps1 -ProjectPath "C:\path\to\your\katalon\project"      # add -Force to also overwrite a customized chaintest.properties
.\Windows\uninstall.ps1 -ProjectPath "C:\path\to\your\katalon\project"    # add -RemoveConfig to also delete chaintest.properties/failure-tags.json
```
</details>

<details>
<summary><b>🍎 macOS/</b></summary>

**Just click:** double-click **`macOS\Install.command`**, then pick your
Katalon project folder in the dialog that opens. Uninstall the same way
with **`macOS\Uninstall.command`**.

**Scripted / CI** - macOS shares the Linux bash engine below:
```bash
./Linux/install.sh /path/to/your/katalon/project      # add --force to also overwrite a customized chaintest.properties
./Linux/uninstall.sh /path/to/your/katalon/project    # add --remove-config to also delete chaintest.properties/failure-tags.json
```
</details>

<details>
<summary><b>🐧 Linux/</b></summary>

```bash
./Linux/install.sh /path/to/your/katalon/project      # add --force to also overwrite a customized chaintest.properties
./Linux/uninstall.sh /path/to/your/katalon/project    # add --remove-config to also delete chaintest.properties/failure-tags.json
```
Run either script with no path argument and it'll prompt you to paste one instead.
</details>

Uninstalling only removes what was installed, and keeps your
`chaintest.properties`/`failure-tags.json` and any generated
`chaintest-results/`/`chaintest-report/` in place unless you pass the
force/remove-config flag shown above.

## What's next

1. **Reopen your Katalon project** in Katalon Studio (or refresh it if
   already open).
2. **Run any Test Suite as usual** - no changes needed to existing tests.
3. **Check the static report** - a `chaintest-report/<Name>_<timestamp>/`
   folder appears automatically. Open `Index.html` inside it directly in
   a browser - no server, no further setup. **If this is all you wanted,
   you're already finished - stop here.**
4. **Only if you also want the real-time ChainLP dashboard**, pick
   whichever of these two applies to you:
   - **No ChainLP yet, or don't care about a password**: `cd chainlp`,
     then `docker compose up -d`, then in `chaintest.properties`:
     ```properties
     chaintest.generator.chainlp.enabled=true
     chaintest.generator.chainlp.host.url=http://localhost:8085/
     ```
   - **Your ChainLP already exists and has a login**: run
     `chainlp/write-proxy/setup.sh` (or `.ps1`) once, answer its three
     prompts, then:
     ```properties
     chaintest.generator.chainlp.enabled=true
     chaintest.generator.chainlp.host.url=http://localhost:8086/
     ```
   See [`chainlp/README.md`](chainlp/README.md) for the full picture -
   every scenario, every CI platform, all in one cheat sheet.
5. **Run your tests again** - results now also appear in ChainLP.

## What the installer actually does

1. Verifies the target folder is a real Katalon project (looks for a
   `*.prj` file) before writing anything - refuses to run otherwise.
2. Copies the Test Listener, Keywords, config, and Drivers jars into it.
3. Leaves an existing, customized `chaintest.properties` alone (pass
   `-Force` / `--force` to overwrite it too).
4. Records everything it installed in
   `<project>/.chaintest-bridge/manifest.txt`, so uninstall can remove
   exactly that later - nothing else in the project is ever touched.
5. Registers the Drivers jars in `.classpath` if one exists, so Katalon's
   editor resolves the ChainTest classes without a manual refresh.

Re-running install against the same project **upgrades** it in place.

## What gets installed

```
Test Listeners/ChainTestListener.groovy         auto-discovered by Katalon - the only wiring needed
Keywords/chaintest/ChainTestReportBridge.groovy  engine: lifecycle mapping, step capture, report assembly
Keywords/chaintest/ChainTestConfig.groovy        chaintest.properties reader, with CHAINTEST_* env var overrides
Keywords/chaintest/ChainTestKeywords.groovy      optional: step(), info/warning/pass/fail(), attachScreenshot()
Include/config/chaintest/chaintest.properties    configuration
Include/config/chaintest/failure-tags.json       failure tagging tuned to Katalon/Selenium exception types
Drivers/chaintest-core-1.0.12.jar                Apache-2.0, Anshoo Arora
Drivers/freemarker-2.3.33.jar                    Apache-2.0, Apache Software Foundation
Drivers/jackson-databind-2.18.0.jar              Apache-2.0, FasterXML
Drivers/jackson-core-2.18.0.jar                  Apache-2.0, FasterXML
Drivers/jackson-annotations-2.18.0.jar           Apache-2.0, FasterXML
Drivers/snakeyaml-2.3.jar                        Apache-2.0, SnakeYAML contributors
Drivers/slf4j-api-2.0.16.jar                     MIT, QOS.ch
View ChainTest Report.bat / .command             optional convenience: opens the most recent report for you (Windows / macOS)
view-chaintest-report.sh                         same, for Linux/CI or manual use
```

### Do you actually need "View ChainTest Report"?

No. Every run writes its own self-contained
`chaintest-report/<Name>_<timestamp>/` folder - open `Index.html` inside it
directly in a browser, no server needed. `View ChainTest Report` is a
convenience that finds the newest one for you so you don't have to hunt
through timestamped folder names.

## Using it

**Zero-touch (default):** every test suite run automatically produces one
ChainTest entry per test case - status, timing, a failure screenshot (WebUI
only), the full nested step tree pulled straight from Katalon's own
execution log, and automatic failure tagging (Object Repository issues,
timeouts, assertion failures, environment/infrastructure issues,
script/data issues - tuned for Katalon/Selenium, editable in
`Include/config/chaintest/failure-tags.json`).

`<Name>` in the report path is a Test Suite Collection's name if you ran
one, otherwise the Test Suite's name. A Test Suite Collection produces
exactly one combined report, not one per Test Suite. Suites are listed
alphabetically by name in the report; a suite that runs more than once in
one Collection (e.g. the same suite with two different browsers) shows up
as its own separate entry each time, labeled by browser where that's enough
to tell the occurrences apart.

**Opt-in extra detail**, from inside a Test Case script or Cucumber glue:

```groovy
CustomKeywords.'chaintest.ChainTestKeywords.step'('Log in as admin', {
    WebUI.setText(findTestObject('Page/input_Username'), 'admin')
    WebUI.click(findTestObject('Page/button_Login'))
})
CustomKeywords.'chaintest.ChainTestKeywords.info'('Using seeded test account #4')
CustomKeywords.'chaintest.ChainTestKeywords.attachScreenshot'('Before checkout')
```

This is a deliberately small, opt-in API - `step()` for nested detail,
`info`/`warning`/`pass`/`fail()` for a manual log line, and
`attachScreenshot()` for an extra capture. Nothing here is required; every
test case already gets a full report entry with zero code changes.

## Configuration

Edit `Include/config/chaintest/chaintest.properties` in the target project,
or override any key per environment with
`CHAINTEST_<KEY_IN_UPPER_SNAKE_CASE>` (dots and hyphens both fold to
underscores, e.g. `chaintest.generator.simple.output-file` ->
`CHAINTEST_GENERATOR_SIMPLE_OUTPUT_FILE`):

| Key | Default | Meaning |
|---|---|---|
| `chaintest.bridge.enabled` | `true` | Master switch |
| `chaintest.bridge.results.dir` | `chaintest-results` | Working storage for intermediate per-test-case records - not meant to be read directly |
| `chaintest.bridge.clean.results.before.run` | `true` | Clear last run's records before each new (non-continuing) run |
| `chaintest.bridge.attach.screenshot.on.failure` | `true` | Screenshot on any non-PASSED status (WebUI only) |
| `chaintest.bridge.attach.screenshot.always` | `false` | Screenshot on every test case |
| `chaintest.bridge.capture.steps` | `true` | Auto-convert Katalon's execution log into nested ChainTest steps |
| `chaintest.bridge.tags.file` | `Include/config/chaintest/failure-tags.json` | Failure tagging template |
| `chaintest.bridge.report.dir` | `chaintest-report` | Base folder for the generated static report; each run writes its own `<Name>_<timestamp>/` folder here |
| `chaintest.project.name` | auto-detected from the Katalon project | Shown in the report and (if ChainLP is enabled) used server-side |
| `chaintest.generator.simple.enabled` | `true` | The static, self-contained HTML report - what makes this zero-touch |
| `chaintest.generator.simple.offline` | `true` | Bundle CSS/JS/fonts alongside the report instead of a CDN (one exception - see ARCHITECTURE.md) |
| `chaintest.generator.simple.dark-theme` | `false` | Report colour theme |
| `chaintest.generator.chainlp.enabled` | `false` | Also push results to a ChainLP server for real-time analytics/history - see below |
| `chaintest.generator.chainlp.host.url` | `http://localhost:8085/` | Where that ChainLP server is - `8085` already matches this bridge's own `chainlp/docker-compose.yml` out of the box |

## Real-time analytics and history (ChainLP)

ChainTest's static report is complete on its own and needs nothing beyond
this bridge. Separately, ChainTest also offers **ChainLP**: an optional
server component (Java/Spring backend, Angular frontend, distributed as a
Docker image) for real-time dashboards and cross-run historical trends -
the kind of thing a static HTML file can't do.

This bridge can push results to a ChainLP instance in addition to (not
instead of) generating the static report. It's off by default because it's
the one part of this bridge that needs infrastructure beyond the test run
itself:

1. Bring up a ChainLP instance - see [`chainlp/`](chainlp/) in this
   repository for a ready-to-run Docker Compose setup.
2. Set `chaintest.generator.chainlp.enabled=true` and
   `chaintest.generator.chainlp.host.url=<your ChainLP URL>` in
   `chaintest.properties` (or the equivalent `CHAINTEST_*` env vars).
3. Run your tests as usual. Both the static report and ChainLP receive the
   same results independently - if ChainLP is unreachable, the static
   report still generates normally (see ARCHITECTURE.md for exactly how
   that isolation works).

## CI setup

`executor.json`-equivalent detection is automatic: the generated report's
System Info panel picks up Jenkins, Azure Pipelines, GitHub Actions, and
GitLab CI from each platform's own standard environment variables, so it's
clear which build produced it - nothing to configure for that part on any
of them.

This repo includes ready-to-copy configs -
[`azure-pipelines.example.yml`](azure-pipelines.example.yml) and
[`gitlab-ci.example.yml`](gitlab-ci.example.yml) for the static report
only, plus [`gitlab-ci-selfhosted-chainlp.example.yml`](gitlab-ci-selfhosted-chainlp.example.yml)
if you also want CI to push into ChainLP (see "Pushing CI results into
ChainLP" below) - a GitHub Actions example is planned next. Pick the one
matching your platform and goal, copy it in under the filename your CI
expects, and fill in its TODOs (your Test Suite or Test Suite Collection
path, and - **important** - the bridge's own repo URL: this bridge isn't
published to npm yet, so all examples install it by cloning its repo
directly rather than via `npx`; each file's own comments explain exactly
what to change once it is published).

### Azure Pipelines

1. Copy `azure-pipelines.example.yml` into your repo as `azure-pipelines.yml`.
2. Install the **"Execute Katalon Studio Tests"** extension from the Azure
   DevOps Marketplace if your organization doesn't already have it.
3. Under **Pipelines → Library**, create a variable group named `Katalon`
   with a secret variable `KatalonApiKey` holding your Katalon Runtime
   Engine API key (Katalon Store → Profile → API Key).
4. Fill in the TODOs (your bridge repo URL, your Test Suite Collection
   path).
5. Commit and push - the pipeline runs on every push to `main`.

Reports show up on the pipeline run's **Summary** tab, in the small
"X published" artifacts panel near the top - both the raw Katalon output
(`katalon-reports`) and the generated ChainTest HTML (`chaintest-report`).

### GitLab CI

1. Copy `gitlab-ci.example.yml` into your repo as `.gitlab-ci.yml`.
2. Under **Settings → CI/CD → Variables**, add a variable named
   `KATALON_API_KEY` with your Katalon Runtime Engine API key, and check
   "Mask variable".
3. Fill in the TODOs (your bridge repo URL, your Test Suite Collection
   path).
4. Commit and push.

This one differs from Azure Pipelines in one respect: it uses Katalon's
own official Docker image (`katalonstudio/katalon`) instead of downloading
Katalon onto a hosted VM, so it runs on **Chrome, not Edge** - adjust
`-browserType` and any browser-specific test logic accordingly.

Reports show up on the pipeline job's page, in the **Job artifacts** panel.

### Pushing CI results into ChainLP

The two examples above cover the zero-touch static report only - neither
pushes into a ChainLP instance. Doing that from CI needs the CI job and
ChainLP to be able to reach each other **without** a login in between -
and ChainLP has no login of its own (see
[`chainlp/README.md`](chainlp/README.md)'s "Remote access" section), so
that connection has to be inherently trusted rather than authenticated.
The setup that satisfies this without any new infrastructure - **the
underlying idea verified end to end on a real GitLab pipeline**: run the
CI job on a **self-hosted runner on the same machine that's hosting
ChainLP**.

For GitLab CI, copy
[`gitlab-ci-selfhosted-chainlp.example.yml`](gitlab-ci-selfhosted-chainlp.example.yml)
instead of the plain `gitlab-ci.example.yml` above, and fill in its
placeholders. It runs Katalon inside the same official
`katalonstudio/katalon` Docker image the plain example uses, so there's
no local Katalon Studio install to explain to whoever else runs this -
the job's container reaches ChainLP on the host via
`host.docker.internal` rather than `localhost`. The same shape works on
GitHub Actions or Azure Pipelines via a self-hosted runner/agent instead
- see `chainlp/README.md`'s "ChainLP in CI" section for the
platform-independent walkthrough, the gotchas found while verifying the
same-machine-runner approach, and **importantly, what's different about
your own setup vs. this one**: your own ChainLP host/port depends on your
own `chainlp/docker-compose.yml`, and your own runner tags/paths depend
on how you registered your own runner - nothing here is a shared or fixed
address.

If instead you want your **existing cloud-hosted CI runners** (not a
self-hosted one) to push into ChainLP, that means hosting ChainLP
somewhere those runners and your team can reach without a login in front
of it (a private network/VPN, for example) - a separate, bigger
infrastructure decision each team makes for itself, distinct from
anything set up by this bridge. In that case the settings are the same
two as anywhere else, just pointed at that shared host instead of
`localhost`:

```
CHAINTEST_GENERATOR_CHAINLP_ENABLED=true
CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://<your-reachable-chainlp-host>/
```

## Support

Questions or bug reports: open an issue on this repo, or reach out
directly: **bagati.monty@gmail.com**.

## License

See [`LICENSE.md`](LICENSE.md).
