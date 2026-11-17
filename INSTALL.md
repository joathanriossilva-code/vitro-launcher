# How to install Vitro

*Português: [INSTALAR.md](INSTALAR.md).* After installing, see the [first-run setup](README.en.md#first-run-setup) and
the [tutorial](TUTORIAL.en.md).

Vitro is not on the Amazon or Google stores: you install it from the `vitro.apk` file published on this repository's
**Releases** page. It takes about 5 minutes. The options go from easiest to most involved: pick one.

| Option | You need | Good for |
|---|---|---|
| [1. Downloader app](#1-downloader-app-the-easiest) | Just the TV and the remote | Almost everyone |
| [2. From your phone, with Send Files to TV](#2-from-your-phone-with-send-files-to-tv) | An Android phone | People who don't want to type a link with the remote |
| [3. USB drive](#3-usb-drive) | A USB drive and a TV or box with USB | Android TV sets and boxes (the Fire TV Stick has no USB) |
| [4. Computer, with ADB](#4-computer-with-adb) | A computer on the same network | People who already use ADB |

## Before you start

1. **Check that your TV is supported** in the [Where it works](README.en.md#where-it-works) table. Samsung (Tizen),
   LG (webOS), Roku TVs and Fire TV devices with Vega OS cannot install Android apps.
2. **Turn on Developer options.** Vitro needs them during setup, and on some TVs they are what unlocks installing
   apps from outside the store.

   | System | Path |
   |---|---|
   | Fire TV | Settings › **My Fire TV** › About › press OK **7 times** on the device name |
   | Google TV | Settings › **System** › About › press OK **7 times** on "Android TV OS build" |
   | Android TV | Settings › **Device Preferences** › About › press OK **7 times** on "Build" |

   A message says you are now a developer. Names vary a little between manufacturers.

## 1. Downloader app (the easiest)

**Downloader** is a free app that downloads files straight to the TV, no other device needed.

1. **Install Downloader.** On Fire TV, search for "Downloader" in the store (the magnifier) and install the one with the
   orange icon. On Google TV and Android TV, it is on Google Play.
2. **Let Downloader install apps.**

   | System | Path |
   |---|---|
   | Fire TV | Settings › My Fire TV › Developer options › **Install unknown apps** › Downloader: **On** |
   | Google TV | Settings › Apps › Security & restrictions › **Unknown sources** › Downloader: **On** |
   | Android TV | Settings › Device Preferences › Security & restrictions › **Unknown sources** › Downloader: **On** |

3. **Open Downloader** and type just the code in the address field:

   ```
   9849078
   ```

   Press **Go**. The download starts by itself. The code always leads to the latest Vitro release.

   <details>
   <summary>If you'd rather type the full address</summary>

   ```
   github.com/joathanriossilva-code/vitro-launcher/releases/latest/download/vitro.apk
   ```

   That is where the code leads (check it at `aftv.news/9849078+` in any browser).
   </details>
4. When it finishes, press **Install** and then **Done**. Downloader offers to delete the downloaded file: you can.

> Want to pick a version? Type `github.com/joathanriossilva-code/vitro-launcher/releases` in Downloader, which has a
> browser, and choose the `vitro.apk` of the version you want.

## 2. From your phone, with Send Files to TV

Good for people who don't want to type a link with the remote.

1. Install **Send Files to TV** on the TV (Amazon store or Google Play) and on your Android phone (Google Play).
2. Let Send Files to TV install apps on the TV, using the same path as Downloader's step 2 but turning on
   **Send Files to TV**.
3. On the phone, download `vitro.apk` from this repository's **Releases** page.
4. Open the app on both: on the TV choose **Receive**; on the phone choose **Send**, then `vitro.apk` and your TV.
5. On the TV, open the received file and press **Install**.

## 3. USB drive

For TVs and boxes with a USB port.

1. On a computer, download `vitro.apk` from the **Releases** page and copy it to a USB drive.
2. On the TV, install a file manager (for example **X-plore File Manager**, on Google Play) and let it install apps,
   using the path from Downloader's step 2.
3. Plug the drive into the TV, open the file manager, go to the drive, pick `vitro.apk` and press **Install**.

## 4. Computer, with ADB

For people who already have [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) on a
computer.

1. Turn on **ADB debugging** (Fire TV) or **USB debugging** (Android TV and Google TV) in Developer options.
2. Find the TV's IP address: Settings › Network (on Fire TV, My Fire TV › About › Network).
3. On the computer, with `vitro.apk` downloaded:

   ```
   adb connect TV-IP:5555
   adb install vitro.apk
   ```

   The first time, the TV asks whether to allow debugging from that computer: tick **Always allow** and press **OK**.

**Android 13 and 14 TVs without port 5555** (`adb connect` fails): turn on **Wireless debugging** in Developer
options, open **Pair device with pairing code** and use the IP, ports and code the TV shows:

```
adb pair TV-IP:PAIRING-PORT
adb connect TV-IP:WIRELESS-DEBUGGING-PORT
adb install vitro.apk
```

## After installing

1. **Open Vitro**: on Fire TV, in Apps (or hold Home › Apps); on Google TV, in the Apps tab; on Android TV, in the
   apps row.
2. **First-run setup** opens by itself and guides the rest, including the Home button. See
   [First-run setup](README.en.md#first-run-setup).
3. To learn every button and menu, see the [tutorial](TUTORIAL.en.md).

## Update and uninstall

- **Update:** install the new `vitro.apk` on top, with any of the options above. Your settings stay.
- **Uninstall:** Settings › Apps › Vitro › Uninstall. Apps it disabled stay disabled until you enable them; see
  [Undo](README.en.md#undo).

## If something goes wrong

| What you see | What to do |
|---|---|
| "Install blocked", or no Install button | The app that downloaded the file is not allowed to install apps yet (Downloader's step 2). |
| "App not installed" | Another Vitro build signed with a different key is installed (for example one you compiled). Uninstall it and install again. |
| Downloader can't find the code or the address | Check the digits (9849078) or the address letter by letter, or open the Releases page in Downloader's browser. |
| The app is not in the list | On Fire TV, look in Apps › See all; on some TVs, new apps go to the end of the list. |
