# Daybell: reminders and calorie tracker

A personal Android app for planning your day and tracking what you eat.

- **Tasks:** add tasks with a date and time, set a reminder (at the time, or 5 to 60 minutes before), and repeat daily, on weekdays or weekly. A ring shows what percentage of the day's tasks are done, with a bar for each day of the week.
- **Alarms:** ring through Android's own alarm system, so they work when the app is closed, the screen is locked or the phone has restarted. A full-screen alarm offers **Mark as done**, **Snooze 5 min** and **Dismiss**. Sound, a spoken reminder ("Reminder. Call the bank. In 5 minutes.") and vibration can each be turned on or off in Settings.
- **Appearance:** pick System, Light or Dark in Settings. The alarm screen and system bars follow it too.
- **Calories:** set a daily goal and log food by meal. The page shows how much of the goal you've used, how much is left, and a week view. Foods you've logged before can be added again with one tap.

## Install

1. Download [`apk/Daybell.apk`](apk/Daybell.apk) on your Android phone (Android 8.0 or newer) and open it. Allow "install unknown apps" when asked.
2. Open Daybell and allow notifications.
3. Go to **Settings → Phone permissions**. If anything is missing, tap **Fix**. On Xiaomi, Oppo, Vivo and Samsung phones, set Daybell's battery use to **Unrestricted** so alarms aren't blocked.
4. Tap **Test**, then lock the phone. The test alarm rings within 10 seconds.

Tasks and the food log are stored on the phone only.

## Project layout

| Path | What it is |
| --- | --- |
| `web/index.html` | The whole app interface (HTML, CSS and JavaScript). It also runs on its own in a browser, where alarms ring only while the page is open. |
| `android/` | The Android app (Java, no extra libraries). It shows `web/index.html` and handles alarms natively. |
| `android/app/src/main/assets/index.html` | The copy of `web/index.html` that goes into the app. Copy the file over after changing it. |
| `apk/Daybell.apk` | A signed build, ready to install. |

How the Android side works:

- `MainActivity` shows the interface and gives the page a `DaybellNative` bridge. Every time tasks change, the page sends them over.
- `Scheduler` works out each task's next reminder time, including repeats, and books it with `AlarmManager.setAlarmClock`.
- `AlarmService` plays the alarm tone, reads the reminder aloud and vibrates. It also shows the alarm notification.
- `AlarmActivity` is the full-screen alarm page shown over the lock screen.
- `BootReceiver` books the alarms again after a restart, an app update or a time-zone change.

## Build

You need JDK 17 or newer, Gradle 8.9 or newer, and the Android SDK with platform 35.

```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease
```

The APK is written to `android/app/build/outputs/apk/release/`.

### Signing

The signing key is not in this repository. To sign builds, create `android/keystore.properties` (git ignores it):

```properties
storeFile=/path/to/daybell.keystore
storePassword=...
keyAlias=daybell
keyPassword=...
```

Without that file, `assembleRelease` produces an unsigned APK, which Android won't install. Use `gradle assembleDebug` for a test build instead. An update only installs over the existing app when it is signed with the same key as `apk/Daybell.apk`. Keep that key safe: without it you'd have to uninstall the app, which deletes its data.
