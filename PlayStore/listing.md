# Google Play text for RigHelm for Android

Copy each block into the matching field in Play Console → RigHelm. Character limits are in
brackets; every block here is under its limit.

## Main store listing (Grow users → Store presence → Main store listing)

### App name [30]

```
RigHelm Remote
```

The same name as the iPhone app. `RigHelm` alone is fine too.

### Short description [80]

```
Tune, listen, talk and send CW on your radio over Wi-Fi with RigHelm.
```

### Full description [4000]

```
RigHelm Remote turns your Android phone into a control head for a radio run by RigHelm, the free rig-control program for Linux, macOS and Windows. Walk away from the shack and keep operating from anywhere on your home Wi-Fi.

You need RigHelm running on the computer your radio is plugged into, and a radio that RigHelm supports. This app does not talk to the radio by itself, and it does not work over the internet. It controls the RigHelm station on your local network.

FINDS YOUR STATION
• Stations on your Wi-Fi show up by themselves. You can also add one by address.
• Station passwords are encrypted with a key in the Android Keystore.

CONTROL THE RADIO
• Frequency by helm-wheel knob, drag on the display, step buttons, or type it in.
• Bands, modes, VFO A/B, split, lock, ATU and antenna.
• Receiver, filter, DSP, transmitter, clarifier and CW settings.
• S-meter, and PO, SWR and ALC while transmitting.

HEAR THE RADIO
• Live receive audio with a jitter buffer for Wi-Fi.
• Keeps playing with the screen off or in another app. A notification lets you disconnect from anywhere.

TALK AND SEND CW
• Push-to-talk with the phone's microphone in SSB, AM and FM.
• CW paddles on the screen: straight key, iambic A or B, plus typed text, keyer speed and an optional sidetone.

SAFE BY DEFAULT
• Transmit is locked until you turn it on in Settings.
• The phone lets go of the transmitter when the app goes to the background, when a call comes in, after a time-out, or if Wi-Fi drops. The station un-keys a phone that disappears within seconds.
• A station without a password is receive-only.

PRIVATE
No accounts, no ads, no analytics. Everything stays between your phone and your station.

Transmitting requires an amateur radio license. You are responsible for operating within the rules where you are.

RigHelm and this app are open source under the GNU GPL v3: github.com/NR5P/righelm-android
```

### Graphics

| Asset | Size | Where it comes from |
|---|---|---|
| App icon | 512 × 512 PNG | [icon-512.png](icon-512.png), made from the launcher icon |
| Feature graphic | 1024 × 500 PNG or JPEG | Needs making. The logo on the dark background from nr5p.com works. |
| Phone screenshots | 2–8, each side 320–3840 px, long side at most 2× the short side | See below |

Most phones today are taller than 2:1 (1080 × 2400 is 2.22:1), so Play rejects raw
screenshots from them. Crop the status bar and navigation bar off, or crop to 1080 × 2160.
The four 600 × 1334 shots on nr5p.com (Operate, Bands, Controls, Transmit) are 2.22:1 too, so
they need the same crop. Good set: Operate, Bands, Controls, transmitting with meters, CW key,
Settings.

The promo video can go in as a **YouTube URL** (Play takes only a link, no upload).

### Contact details (Store settings)

| Field | Value |
|---|---|
| Email | `nathan@nr5p.com` |
| Website | `https://nr5p.com/righelm-android.html` |
| Category | App → **Tools** |
| Tags | Pick up to 5 from Play's list; the closest are usually *Remote control* and *Tools* |

## Privacy policy URL (Policy → App content → Privacy policy)

```
https://nr5p.com/righelm-android-privacy.html
```

Publish [righelm-android-privacy.html](righelm-android-privacy.html) there first.

## App access (Policy → App content → App access)

Now called **Sign in details**. Answered **No** (nothing in the app is behind a sign-in). The
"Yes" path makes you tick a box saying the sign-in details give full access to every feature,
which isn't true when the missing piece is a radio. If Google's review says it couldn't test
the app, switch to **Yes** and paste this (500 characters max there, so trim it):

```
RigHelm Remote is a remote control for RigHelm (https://github.com/NR5P/righelm), a free desktop program that runs on the computer connected to an amateur radio transceiver. The app only talks to a RigHelm station on the same local network; it has no server, no accounts and no internet features, so it cannot be fully used without that equipment.

Video of the app running against a real station: <VIDEO LINK>

The video shows: discovering the station on the local network, connecting with the station password, tuning and changing band and mode, receive audio playing in the background with the notification, turning on "Allow transmit from this phone" in Settings, push-to-talk with the phone microphone, and the on-screen CW key.

Without a station, you can still open the app, see the empty station list and the "Add by address" form, and open Settings.
```

## Foreground service declaration (Policy → App content → Foreground service permissions)

Tick **Media playback**, then:

**Description**

```
While the user is connected to their RigHelm radio station, the app plays the radio's receive audio, streamed live from the station over the local network, so the user can keep listening with the screen off or while using other apps, like a radio app. The service starts only when the user connects, shows an ongoing notification with a Disconnect button, and stops when the user disconnects, the station goes away, or the app is swiped away.
```

**Impact if deferred or interrupted:** the user would stop hearing the radio and lose the
connection to the station mid-contact.

**Video link:** a short screen recording: connect, hear audio, press Home or lock the
screen, audio keeps playing, pull down the notification, tap Disconnect. This can be a
segment of the same video as App access.

## Data safety (Policy → App content → Data safety)

Google counts any user data that leaves the phone as "collected", even when it goes to the
user's own computer and never to you. The phone's microphone audio does leave the phone (to the
station) while transmitting, so the safe answer is to declare it rather than "no data".
A form that says "no data" next to a microphone permission is a common reason for a policy
warning.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **No** (CAT and audio go over the LAN unencrypted; only the password is proved with HMAC) |
| Which ways can users request that their data is deleted? | None needed: nothing is stored. Choose the option saying data isn't retained, if offered; otherwise leave the deletion URL blank |
| Data types | **Audio → Voice or sound recordings** only |
| Collected / shared | **Collected** yes, **shared** no |
| Processed ephemerally? | **Yes** |
| Required or optional? | **Optional** (only when the user turns on PHONE MIC) |
| Purpose | **App functionality** |

Leave everything else (location, personal info, device IDs, app activity, crash logs)
unticked. The app has no analytics or crash reporting libraries.

## Content rating (Policy → App content → Content ratings)

- Category: **All other app types** (it's a utility, not a game or social app).
- Violence, sexuality, language, controlled substances, gambling: **No** to all.
- *Does the app allow users to interact or exchange content?* **Yes.** The phone's
  microphone goes out over the air to anyone listening. This doesn't raise the age rating; it
  adds a "Users interact" note, which is accurate for a transmitter.
- *Shares the user's current location?* **No.** *Digital purchases?* **No** (the app price
  isn't an in-app purchase).

## Target audience and content

- Target age: **18 and over** only. Licensed operators, and it keeps the app out of the
  Families policy.
- *Could the app unintentionally appeal to children?* **No.**

## Other App content questions

| Section | Answer |
|---|---|
| Ads | **No, my app does not contain ads** |
| Government apps | **No** |
| Financial features | **None** |
| Health | **None** |
| News app | **No** |
| Advertising ID | **No** (no ad or analytics SDKs, and `AD_ID` isn't in the manifest) |

## Release notes for 1.1.1 [500]

```
First release on Google Play. Tune, change bands and modes, hear the radio, talk with the phone's microphone and send CW on your radio through RigHelm. Swiping the app away now disconnects cleanly and reconnects to the same station next time.
```
