# appblock

| ![AppBlock home screen](public/home.jpg) | ![Blocked screen](public/blocked.jpg) |
| ---------------------------------------- | ------------------------------------- |

Pick apps and give each a budget: **allow X in any rolling Y**, in minutes, hours or
days (say, 10 minutes in any 2 hours). When the budget is spent, opening the app puts a
"blocked, try again in N minutes" wall over it.

It can also block YouTube's Shorts and Instagram's Reels outright, even with time
left: switch on "Block YouTube Shorts" under YouTube, or "Block Instagram Reels"
under Instagram, on the home list. A wall goes up whenever Shorts or Reels is on
screen, however you got there (the tab, a link, a message), and comes down when
you go back. Thumbnails stay.

No dependencies, no account, no analytics. Nothing about you leaves your phone.
The one download is the rules for spotting Shorts and Reels, a signed file from
this repo (`rules/`, served at appblock.robinroy.xyz), so a redesign gets fixed
without an app update.

The blocker is an accessibility service: Android tells it which apps are on
screen (split screen and pop-up windows included) and lets it draw the wall over
one whose time is up. It only looks at which app each window belongs to, never at
what's inside, except for YouTube's Shorts player or Instagram's Reels player (by
its view id, no text) when they're blocked. Besides switching it on in Settings > Accessibility, it needs a
battery exemption so the phone doesn't put it to sleep. Usage access is optional,
for screen time: the home screen shows each day's, across the day, from a history
AppBlock keeps on the phone (copied from Android's usage events, which Android
itself drops after a week or so; left out of backups), any day of it a tap away on
a calendar, and each app's own page its patterns (daily average, most used day,
peak hours). Apps that shouldn't count, like maps while driving, can be left out
of the totals. While a limited app is on screen, a notification counts
down its time (with 50%/90% warnings); two limited apps side by side get one each.

Installing the APK from GitHub on Android 13 or later? Android greys out the
Accessibility switch for apps from outside an app store until you allow it: open
AppBlock's App info, tap ⋮, choose "Allow restricted settings", then switch it on.

Force-stopping AppBlock (App info > Force stop) switches its accessibility service
off; that's an Android rule. Blocking pauses, rules kept, until you switch it back
on from the checklist in AppBlock.

## Strict mode

| ![Strict mode's cooldown page](public/strict.jpg) | ![AppBlock's App info, walled in strict mode](public/strict-wall.jpg) |
| ------------------------------------------------- | --------------------------------------------------------------------- |

For when you don't trust yourself not to switch AppBlock off. Tap **Strict mode**
on the home screen and pick your way out:

- **Plug in to unlock:** the Turn off button only works while the phone is
  plugged in.
- **Cooldown:** tap Stop and wait out a timer you chose (minutes, hours or
  days). Then Turn off works, but strict mode stays on until you tap it. Change
  your mind any time: Keep it on locks it again.

While it's on:

- AppBlock is a device admin (with no admin powers), so Android won't uninstall
  it: not from the launcher, the Play Store or adb.
- The pages that could switch it off get the wall: AppBlock's App info (Force
  stop, Clear storage), its Accessibility switch, the uninstall prompt, the
  device admin page, Safety Center's "Remove access". It finds them by looking
  for its own name on the screens of Settings, the package installer and the
  permission controller. The rest of Settings (Wi-Fi, Bluetooth...) stays usable.
- Limits only get stricter: lowering an allowance or adding an app works; raising
  one, removing an app or unblocking Shorts waits until strict mode is off.
- Optional **emergency passes** ("5 minutes in any day", say) open your blocked apps
  without ending strict mode. A pass only counts down while you're in an app it
  opened (a spent app, or Shorts or Reels), so time spent anywhere else isn't
  wasted. Stop it to save what's left.

Setting the phone's clock to skip a cooldown starts it over. Strict mode isn't
restored from a backup: on a new phone it starts off.

It's never a trap. Besides your way out:

- **Safe mode** (hold power, long-press Power off, Reboot to safe mode) runs
  without AppBlock. Switch off its device admin in Settings there; back in normal
  mode, strict mode ends and a notification says so.
- **adb**, for developers:
  `adb shell am broadcast -a xyz.robinroy.appblock.END_STRICT -n xyz.robinroy.appblock/.EndStrictReceiver`.
  Only the shell can send it.

## Tie yourself to the mast

![Ulysses and the Sirens](<public/John_William_Waterhouse_-_Ulysses_and_the_Sirens_(1891).jpg>)

_Ulysses and the Sirens, John William Waterhouse, 1891. That's him on the mast._

Odysseus had to sail past the Sirens, whose song lured every sailor to
shipwreck. Every captain before him knew the danger. Knowing was never enough.

Odysseus wasn't stronger than the others. He was wiser: he knew that in the
moment, with the song in his ears, he'd be as weak as anyone. So he had his
crew tie him to the mast before the singing started.

Your phone sings too. And you already know knowing isn't enough. You've
promised yourself "just five minutes" before.

AppBlock is your mast. You decide the rules now, while you're clearheaded: a
few minutes per app, every few hours. Later, when the pull comes, the decision
is already made. Not zero access, no daily quota to blow through by 5am. Just
enough to check in, never enough to drown.

## What exactly gets blocked

**Blocked during a block window:**

- **Opening the app:** from the launcher, recents, widgets, share sheet, links,
  anything. The moment the app's window shows up, the block wall covers it, with
  a button to go home (over a pop-up, it says to swipe it away in Recents).
- **Split screen and pop-up windows:** the wall covers just that app's window,
  so the other half of the screen, or the home screen around a pop-up, stays
  usable. Each limited app on screen uses up its own budget.
- **Tapping one of its notifications:** the notification arrives and is readable,
  but tapping it opens the app → blocked like any other open.
- **Overstaying:** if you're mid-scroll when the budget runs out (checked every
  5 seconds), the wall drops over the app.

**NOT blocked:**

- **Notifications:** they arrive, buzz, and show on the lock screen exactly as
  always. Inline actions (e.g. replying to a message from the notification) work,
  since they never open the app's window.
- **Background activity:** messages keep syncing, downloads keep running, audio
  keeps playing. A blocked Spotify keeps playing what's queued; you just can't
  open its UI.
- The budget clock only ticks while the app is **on screen**. Background use
  costs nothing.
- **Incoming calls in blocked apps** (WhatsApp/Telegram): the ring notification
  appears, but the full-screen incoming-call UI is the app's window. Answering
  while the app is over budget will likely get walled. Think twice before
  blocking an app you take calls on.
- **The notification shade and power menu:** pulling either down over a blocked
  app lifts the wall while it's open, so the shade stays usable. The wall is back
  the moment it closes, and a limited app keeps counting underneath.
- A blocked app's content shown _inside another app_ (e.g. a YouTube video
  embedded in a browser) is not blocked. Only the app's own windows are.
