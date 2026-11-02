# Getting RigHelm for Android onto Google Play at $4.99

The app is already in Play Console, in internal testing (the test link on nr5p.com), so the
app record, package name (`com.righelm.android`) and upload key are set up. What's left: the
price, the App content forms, the store page, a closed test if your account needs one, and a
production release.

## What's in this folder

| File | What |
|---|---|
| [listing.md](listing.md) | All the text to paste: name, descriptions, App access instructions, foreground service declaration, Data safety, content rating and other App content answers, release notes. |
| [righelm-android-privacy.html](righelm-android-privacy.html) | Privacy policy page, matching the iPhone one. It goes up at `https://nr5p.com/righelm-android-privacy.html`. |
| [icon-512.png](icon-512.png) | Store icon, 512 × 512. Scaled up from the 432 px launcher art, so make it from the original artwork if you have it at a larger size. |

The release bundle is `app/build/outputs/bundle/release/app-release.aab` (1.1.1, version code 3,
signed with the upload key) from `./gradlew :app:bundleRelease`.

## Steps, in order

### 1. Check that the app can still be made paid (do this first)

Google lets you change an app from paid to free, never from free to paid. Releases to the
internal test track may already count as "published free".

Play Console → RigHelm → **Monetize with Play → Products → App pricing**. If it lets you
set a price, you're fine. If it says the app is free and can't be changed, the choices are:

- **A new app listing** with a new package name (for example `com.righelm.android.remote`),
  set to paid before its first release. Existing testers would install it as a new app.
- **Free to download with a one-time in-app purchase** to unlock (for example unlocking
  transmit). This needs Play Billing code in the app.

### 2. Payments profile (one time, account-wide)

Setup → **Payments profile** → create or link a merchant account, with bank account and tax
info (W-9 for a US individual). You can't set a price until it's done.

Selling a paid app makes you a **trader** under EU rules (declared in the developer account's
settings, under *Trader status*). Google shows the trader's address, phone and
email on the store page, so use details you're happy to make public.

### 3. Set the price

App pricing → **$4.99 USD**. Play fills in other countries' prices. Google keeps 15% on the
first $1M a year, so you get about $4.24 a sale. Under **Countries / regions**, pick where it's sold.

### 4. Put the privacy policy online

Copy `PlayStore/righelm-android-privacy.html` into `nr5p-website/`, add
`--include='righelm-android-privacy.html'` to the rsync line in `nr5p-website/deploy.sh`, and
run `./deploy.sh`. Open the URL and check it loads.

While you're there, `righelm-android.html` says "free & open source", "In testing on Google
Play" and "Not in the Play Store yet". Update those once it's live. Selling it is fine: you
wrote all of it, so the GPL doesn't stop you charging. The source stays free on GitHub.

### 5. App content forms

Policy → **App content**. Fill in every section from [listing.md](listing.md): privacy policy,
app access, ads, content rating, target audience, data safety, government apps, financial
features, health, foreground service permissions, advertising ID.

The **App access** and **foreground service** sections both want a video. One unlisted
YouTube video covers both: connect → tune, band, mode → audio playing → Home / lock screen,
audio keeps going → the notification with Disconnect → Allow transmit → push-to-talk → CW
key. Put its link into the `<VIDEO LINK>` spot.

### 6. Store listing

Grow users → Store presence → **Main store listing**. Paste the name and descriptions,
upload the icon, a 1024 × 500 feature graphic, and 2–8 phone screenshots (crop to 2:1 or
less; see listing.md).

### 7. Closed test (only if Play asks for it)

Personal developer accounts created after 13 November 2023 must run a **closed** test (not
internal) with at least 12 testers opted in for 14 days in a row before **Production** unlocks.
Dashboard shows whether this applies to you. If it does:

1. Testing → **Closed testing** → create a track, add the testers (an email list or Google
   Group), and release the same `.aab` to it.
2. Wait the 14 days with 12+ testers opted in, then **Apply for production access** from the
   Dashboard (a short questionnaire about the test).

If the app is already paid, closed testers have to buy it too. Set the price after the
closed test, or refund them, whichever is easier.

### 8. Production release

Production → **Create new release** → upload `app-release.aab` (or promote it from the test
track), paste the release notes, **Review release → Start rollout**. Google's review usually
takes a few days the first time.

## After release

- Each update: raise `versionCode` (and `versionName` for user-visible changes) in
  `app/build.gradle.kts`, run `./gradlew :app:bundleRelease`, upload a new release.
- Keep `~/righelm-upload.jks` and `keystore.properties` backed up somewhere safe. If they're
  lost, Google can reset the upload key, but it takes a support request and a few days.
