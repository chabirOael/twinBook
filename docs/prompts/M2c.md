# Fix-up prompt M2c: after the M2b review

You are the build agent for a short fix-up. You work alone in the repository at
`/home/wael/twinBook`, on the existing branch `m2b-findings`, which is not merged yet.
A separate planner reviewed the M2b report, accepted its findings, fixtures and gate
verdict, and found one defect plus three small things worth doing before the branch
is merged. The planner will re-run your checks in a fresh clone.

Read `docs/PLAN.md` section 5 for the standing rules, `docs/SETUP.md`, and
`docs/reports/M2b.md` sections 8 and 9.

## 1. What the planner found

M2b made the capture browser reload the page when a capture starts. An older
instrumented test, `CaptureBrowserScreenTest.textInputFromInputMethodAndKeyEvents`,
starts a capture and then taps and types straight away. The reload now wipes the
field's focus, so the test fails every time: `timed out waiting for email focused`,
with two `layout` logs from the page. Run alone it failed 3 of 3 times on the planner's
emulator. When the whole class runs, the failure leaves the screen in a state that
makes two more tests fail with `Failed to inject touch input`. The new test
`captureStartReloadsThePageSoItsDocumentIsRecorded` passes alone, 2 of 2.

M2b ran only its new device test, not the suite. From now on every milestone runs
the whole instrumented suite and the device scripts.

## 2. Rules

- Stay on `m2b-findings`. Add commits on top. Never push, never merge, never touch
  `main`. Do not edit `docs/PLAN.md` or any file under `docs/prompts/`.
- No traffic to the real site. Do not launch, install, uninstall or clear the `daily`
  app, and do not wipe or recreate the AVD. All device work uses the debug build, the
  engine test app and the mock.
- The rules of the M2b prompt on personal data and secrets still apply: nothing from
  `captures/` may appear in git, in output you quote, or in your report.
- The repository is public.
- Evidence over assertion. A check you did not run is "not run" with the reason.

## 3. Work to do

1. **The typing test.** Make `textInputFromInputMethodAndKeyEvents` correct for the new
   behaviour: after starting the capture it must wait until the reload has finished
   before it touches the page. Check the other tests of the class for the same
   assumption. A failing test must not leave state behind that breaks the next test:
   make each test of the class start from a known state.
2. **Run everything on the emulator.** `tools/connected-test.sh`, then
   `tools/persistence-test.sh`, `tools/extension-update-test.sh`,
   `tools/capture-kill-test.sh` and `tools/daily-survival-test.sh`. Run the whole
   instrumented suite three times in a row and report every result, including any
   failure that does not repeat. Do not hide a flaky test by retrying inside the test.
   If the typing test is still flaky after your change, say so with the failure
   message. Its root cause is assigned to M3.
3. **Tainted bare numbers.** At finalize, layer 2 replaces a remembered secret wherever
   it occurs. Where the secret is a JSON number, for example the viewer id, the
   placeholder is written without quotes and the document stops being valid JSON:
   765 places in the owner's recordings, see `docs/CAPTURE.md` "Known defect". Change
   both implementations, Kotlin and TypeScript, so the result stays valid JSON and the
   label is not lost, add shared test vectors for it, and keep the tools able to read
   sessions finalized before this change.
4. **Leak test without the originals.** The original pulled sessions still hold a few
   values that the first redaction rules did not cover. The planner wants to delete
   them and keep only the `-rescrub` copies. Make sure the leak test, the fixture
   generator and the findings tool work when only the `-rescrub` copies are present,
   and say in `fixtures/README.md` and `docs/CAPTURE.md` which directories may be
   deleted. Do not delete anything under `captures/` yourself.

## 4. Acceptance checks

| ID | Check | Evidence required |
|---|---|---|
| X1 | `env -i HOME=$HOME bash tools/check.sh` passes from a clean clone | tail of output, test counts |
| X2 | The whole instrumented suite passes three times in a row | three summaries with test counts per class |
| X3 | The four device scripts pass | last lines of each |
| X4 | A tainted bare number leaves valid JSON after finalize, in both implementations, with shared vectors; older sessions still readable | test output |
| X5 | Leak test, fixture generation and findings tool work with only the `-rescrub` copies visible | output with the originals moved aside temporarily, then moved back |
| X6 | Branch clean, nothing pushed, `main` untouched, `daily` app install time unchanged | `git status`, `git log --oneline -8`, the install time before and after |

## 5. Report

Append a section "M2c fix-up" to `docs/reports/M2b.md`, commit it, and print that
section as your final message. Sections: outcome in two sentences; the acceptance
table with evidence; what you changed, file by file; anything still flaky; questions
for the planner.
