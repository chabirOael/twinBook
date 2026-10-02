# Capture checklist for the owner

You browse the real site for about ten minutes in the twinBook `daily` app on the emulator
while twin-bridge records what the site sends. Nothing on the site is changed. Afterwards you
pull the recording into `captures/` on this machine (it never enters git) and report a few
lines back to the planner.

Read it once before you start. Every command runs from the repository root
(`/home/wael/twinBook`) in any shell; the scripts set up their own environment.

## Rules

- Use the **secondary test account**, never your main account.
- Log in **before** you start the capture. Never type a password or a security code while a
  capture is running. If you must, press **Discard** first (it deletes the running capture),
  type, then start a new capture.
- Never uninstall the `twinBook daily` app, never clear its data ("Clear storage" in Android
  settings), never wipe or recreate the emulator. Any of these logs you out for good. The full
  list is in `docs/SETUP.md`, "Protecting the owner's session".
- Do not paste cookies, tokens, screenshots with personal data or the capture files into any
  chat. Report only what step 12 asks for.
- The recording contains everything the site shows you, including other people's posts, names
  and photos links. It stays on this machine under `captures/`, outside git. Do not copy it
  anywhere else.

## 1. Start the emulator in a window

```bash
tools/emulator-stop.sh            # only if it is already running without a window
tools/emulator-start.sh --window  # opens the emulator window on the Windows desktop
```

It prints `emulator emulator-5554 booted in ...s` after about a minute. A message about the Qt
"wayland" plugin is harmless. If it says `/dev/kvm is not readable`, run
`sudo chmod 666 /dev/kvm` once and start again.

## 2. Install or update the daily app

```bash
tools/daily-install.sh
```

The first time it says `installing ... for the first time`, later `updating ... in place (data
kept)`, and ends with `Success`. It never removes the app. If it prints `the install was
refused`, stop here and report the message.

## 3. Open the capture browser

```bash
tools/daily-launch.sh
```

or tap **twinBook daily** in the emulator's app list. On the start screen, tap **Capture
browser**. The top shows **Mobile site** and **Desktop site**, **Back** and **Reload**, the page
address, and **Start capture**. The address line says `waiting for the engine…` for a few
seconds, then the mobile site loads.

Typing: click into a field in the emulator window and type on your keyboard, or use the
on-screen keyboard. The app saves no passwords and fills nothing in.

## 4. Log in on the mobile site (capture off)

With **Mobile site** selected, log in with the test account. Notes:

- Typing the password: click into the field, wait a second, then type at normal speed. In
  automated tests under heavy machine load, a burst of keys sent right after a field got focus
  lost its first characters once. Human-speed typing never did. Before you submit, use the
  page's show-password control, if it has one, to check what was typed. If the login is
  rejected, check the password once and try one more time at most. Do not keep retrying:
  repeated failures can lock the account.

- If the site offers to open the Facebook app, or shows "Use the app" / "Open in app", choose
  the option to continue in the browser. Links into the Facebook app are ignored by twinBook
  and do nothing, which is expected.
- If the site asks whether to remember the login or save the browser, say yes or "OK": staying
  logged in is what we want.
- **Security check** (a code sent by SMS or e-mail, a captcha, "confirm it's you", a checkpoint):
  complete it here, with the capture off. If it keeps coming back, or the account is locked,
  stop and report what the screen said. Do not try a different account.

Then tap **Desktop site**. The desktop site opens in its own tab and shares the login; it
should show the logged-in desktop page (small text: it is laid out for a wide screen; pinch
to zoom). If it shows a login form instead, log in there too, then tap **Mobile site** again.

## 5. Start the capture

Tap **Start capture**. The status line below the buttons changes to
`Recording <id>: N records, X MB, R redactions, E errors` and the numbers grow as you browse.

The app then reloads the current page by itself. The home page was loaded before the capture
started, and the first batch of feed posts travels inside that page, so it is only recorded if
the page loads again. Wait until the page has loaded again before you start browsing. Only the
site you are looking at is reloaded, never both.

A `daily` build older than M2b does not reload by itself: if the page does not reload when you
tap **Start capture**, tap **Reload** once yourself.

## 6. On the mobile site (about 5 minutes)

1. On the home feed, scroll slowly until you have passed at least **five sponsored posts**
   (marked "Sponsored"). Stop on each for a second or two.
2. Open the **comments of two posts** (tap the comment count or "Comment"), scroll the comments
   a little, then go back (**Back** button or the Android back gesture).
3. Open the **video** tab (or "Watch" / "Video"), and watch **three videos** for at least 10
   seconds each, scrolling from one to the next.
4. Open **notifications** and scroll the list a little.
5. Open **one profile** (tap a person's name on a post) and scroll it a little.

Do not post, react, comment, share or send anything.

## 7. Second capture: the desktop site (about 5 minutes)

Record the two sites as two separate captures. They stay small, they finalize faster, and a
problem with one does not cost you the other.

1. Tap **Stop and finalize** and wait for the `Last session <id>: FINALIZED ...` line
   (step 8 explains it). Note the id: this is the mobile capture.
2. Tap **Desktop site** at the top.
3. Tap **Start capture**. The desktop page reloads by itself, for the same reason as in step 5.
   On the emulator this reload is heavy: the logged-in desktop page is about 3 MB and once
   froze the emulated system while recording. Record the desktop site on a real phone if you
   can (set `ANDROID_SERIAL` to the phone for `tools/daily-install.sh` and
   `tools/capture-pull.sh`).
4. Do the same five things: feed past five sponsored posts, comments of two posts, three
   videos in the Watch/Video section, notifications, one profile.

## 8. Stop the capture

Tap **Stop and finalize**. The status shows `Finalizing x/y…`, for a few seconds up to about
a minute for a large capture, then:

```
Last session <id>: FINALIZED, <n> files, <x> MB, <r> taint replacements
```

**FINALIZED** means every remembered secret was scrubbed from the recording and it can be
pulled. If it says **NOT finalized** with a reason, the recording was deleted for safety:
report the reason, then you may repeat that capture once (steps 5 and 6 for the mobile
site, step 7 for the desktop site).

You may close the emulator window afterwards, or leave it running. The login stays.

## 9. Pull the recording

```bash
tools/capture-pull.sh list
tools/capture-pull.sh pull <id>
```

Pull both sessions, the mobile one and the desktop one.
`list` shows each session with `finalized` or `NOT-finalized` and its size. `pull` copies a
finalized session into `captures/<id>/`, checks every file against its checksum and prints a
summary. A good pull prints `checksums: <n> files verified, FINALIZED matches` and
`layer 2: ... verification hits 0`. It refuses unfinalized sessions.

For the full summary (hosts, content types, redaction report):

```bash
node tools/capture-summary.mjs captures/<id>
```

## 10. If something goes wrong

| What you see | What to do |
|---|---|
| The address line stays at `waiting for the engine…` for more than a minute | Close the app (swipe it away), open it again. Report it. |
| The page is blank or the app crashes | Reopen the app. Your login is kept. If a capture was running, it is deleted at the next start; start a new one. |
| `errors` in the status line grows | Note the number at the end and report it; continue. |
| A security check appears while capturing | Press **Discard**, complete the check, then start a new capture. |
| The site logs you out | Stop (or Discard), report it. Do not log in again with capture running. |

## 11. Older account (optional)

A fresh account may see few ads. If you have HAR files exported from desktop Firefox with an
older account (Network panel, "Save All As HAR"), import them; the result is redacted and
lands in `captures/`:

```bash
tools/har-import.sh /path/outside/the/repo/file.har
```

Then delete the HAR file: it contains live cookies.

## 12. What to report back

- For each of the two captures: the session id and its `Last session ...: FINALIZED ...`
  line.
- For each pull: the `checksums:` line and the three summary lines printed by
  `capture-pull.sh pull`.
- Roughly how many sponsored posts you saw on each site, and how long you browsed.
- Anything unusual: security checks, logouts, errors count, crashes, pages that did not load.

Nothing else: no screenshots, no file contents.
