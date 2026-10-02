# Web shell checklist for the owner

From this milestone on, the `twinBook daily` app opens straight into the site's mobile version,
with uBlock Origin built in. This page updates the app on your phone, then lists what to try for
about fifteen minutes and what to report back. Nothing here records traffic.

Read it once before you start. Commands run from the repository root (`/home/wael/twinBook`)
on the development machine, in any shell.

## Rules

- Use the same **secondary test account** as for the captures. You stay logged in through the
  update; you should not need to type your password.
- Never uninstall `twinBook daily`, never clear its data ("Clear storage"), never use a phone
  reset. Any of these logs you out for good.
- Report only what the last section asks for: counts and yes/no answers. No screenshots, no
  names, no post texts, no links you opened.

## 1. Update the app on the phone

1. On the phone: Settings, Developer options, **Wireless debugging** on (as for the phone
   capture). Note the address shown there (for example `192.168.1.23:41234`).
2. On the development machine, if the phone is not listed by `adb devices` yet:
   ```bash
   source tools/env.sh
   adb pair <address and port from "Pair device with pairing code">   # only if adb asks for it
   adb connect <address:port from the Wireless debugging screen>
   adb devices                                                         # the phone appears
   ```
3. Update in place, keeping your login:
   ```bash
   ANDROID_SERIAL=<the phone's serial from adb devices> tools/daily-install.sh
   ```
   It ends with `Success` and prints `firstInstallTime` (unchanged from before) and a new
   `lastUpdateTime`. If it prints `the install was refused`, stop and report the message; do not
   uninstall anything.

## 2. First start

Tap **twinBook daily** on the phone.

- You see a plain **twinBook** screen with a small spinner, then the site.
- The very first start after the update takes longer: uBlock Origin is installed and prepares
  its filter lists (on the emulator about 10 seconds; on the phone, check: up to 30 seconds is
  fine). Later starts take a few seconds.
- You are still logged in: your home feed shows, not the login page.
- The app has no address bar. The site's own header and tabs are there as before. The only
  thing the app adds is a small round **⋮** button in the bottom-right corner: Reload, Home,
  Settings, Developer screens.

## 3. Fifteen minutes of trying

Do these in order; note the answers for section 4.

1. **Sponsored posts with ad hiding on.** On the home feed, scroll down past about fifty posts.
   Count the posts marked **Sponsored** (or the word in your language) that you still see. Also
   note anything that looks broken: empty gaps, posts cut in half, the feed stopping to load.
2. **The same with ad hiding off.** **⋮**, **Settings**, switch **Ad hiding** off, go back
   (the page reloads). Scroll past about fifty posts again and count the Sponsored posts. Then
   switch **Ad hiding** on again. (Strict mode stays off for this whole trial.)
3. **An outbound link.** Open a post or comment with a link to another website and tap it. It
   should open in your phone's normal browser, not inside twinBook. In the browser's address bar,
   check that the address has no `fbclid=` in it. Come back to twinBook: it is where you left it.
4. **Kill and reopen.** Open some page deeper in the site (a profile, a group, a post). Swipe
   twinBook away in the recent-apps view, then open it again. It should come back on the same
   page, and back should go to the page before it.
5. **Restart the phone and reopen.** Restart the phone, open twinBook: same page, still logged
   in.
6. **Rotate.** With auto-rotate on, turn the phone sideways and back. The page should not
   reload (scroll position kept, nothing flashes).
7. **A comment draft.** Open the comments of a post and type a few words into the comment box.
   **Do not send.** Switch to another app and back: the text should still be there. Then delete
   the text.
8. **Light and dark.** Switch the phone between light and dark mode (quick settings). The
   twinBook splash, menu and settings follow at once. Note whether the site itself turns dark
   too (it does only if the site supports it).
9. **Back.** Tap through a few pages, then use the back gesture: it should go back page by page,
   and leave the app only when there is nothing left to go back to.
10. **Settings.** **⋮**, **Settings**: note the uBlock Origin line under "Versions" (it should
    say `Ready`). Optional: tap **uBlock Origin dashboard**, look, and come back with **← Back**.

## 4. What to report

Copy this, fill it in, and paste it to the planner:

```
Update: Success / refused (message: ...)
First start: seconds until the feed showed (rough): __ ; still logged in: yes/no
Ad hiding on:  posts scrolled ~__, Sponsored still visible __; anything broken: ...
Ad hiding off: posts scrolled ~__, Sponsored visible __
Outbound link: opened in the browser yes/no; fbclid in the address yes/no; came back fine yes/no
Kill and reopen: same page yes/no; back to the previous page yes/no
Phone restart: same page yes/no; still logged in yes/no
Rotation: reloaded yes/no
Comment draft: kept after switching apps yes/no
Dark mode: app followed yes/no; site turned dark yes/no
Back gesture: page by page yes/no; left the app only at the end yes/no
Settings, uBlock Origin line: ...
The ⋮ button: in the way of something on the site? yes/no (where)
Anything else odd (no personal data):
```
