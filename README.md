# AB's Phone Solutions — Phase 1

Two programs that work together:

| Program | Runs on | What it does |
|---|---|---|
| **Phone app** (`android/`) | Android 8 or newer | Back up to PC, organize WhatsApp & Telegram documents, clear old app backups, phone status |
| **PC program** (`pc/abps_pc.py`) | Windows (also macOS/Linux) | Receives backups over the USB-C cable or Wi-Fi and checks every file |

iPhones are not supported: iOS does not let apps read other apps' files or clear their backups.

---

## How your data is protected

* **Nothing happens without your approval.** Every move or delete shows the list first and waits for you to tap to approve.
* **Backup → Move** never removes a file from the phone until the PC has saved it and its SHA-256 fingerprint matched the phone's copy exactly. You see the list of verified files and approve before anything is removed. Just before removing, the app checks again that the file hasn't changed since.
* **Files are never half-saved on the PC.** They are written to a temporary file first and renamed only after the size and fingerprint check.
* **Organize** never deletes anything. Every move or copy is logged, and you can **undo** it for 30 days.
* **App backup cleanup** never touches your documents, photos, videos or audio. It always keeps each app's latest backup. "Your call" items start unticked. Cleared items are set aside for 7 days and can be restored. Space is freed when they're deleted after 7 days, or when you choose "Delete now".
* **Pairing:** only a phone that entered the 6-digit code shown on the PC can send files. The code changes after each pairing, and pairing pauses for a minute after 5 wrong tries. The control page on the PC only opens from that PC.

### What Android does and doesn't allow (and what the app does about it)

| Activity | Android's rule | What the app does |
|---|---|---|
| Reading your files | Needs "All files access", granted by you in Settings | Explains why, asks once. Without it, the app does nothing. |
| WhatsApp documents and chat backups | Readable with All files access | Organizes documents; clears only old dated chat backups and keeps the newest |
| Telegram files and cache | Newer Telegram keeps them in a protected folder | Organizes files you saved to Downloads; opens Telegram's own storage screen for the cache |
| Other apps' private data and caches | Protected | Opens Android's storage settings so you can clear them per app |
| Saved installers (.apk) | Readable | "Safe" only if the same or newer version is installed; otherwise "Your call" |
| USB transfer | Needs USB debugging turned on once | Step-by-step instructions in the app; the PC program links itself |
| Google Play | Play restricts "All files access" | Phase 1 is installed directly from the .apk file, not from Play |

---

## Step 1 — Get the installable files

You need two files: `ABPhoneSolutions.apk` (phone) and `ABPhoneSolutions-PC.exe` (Windows).
GitHub builds them for free:

1. Create a free account at github.com and make a **new private repository**.
2. Upload all the files from this folder, keeping the folders as they are. The hidden `.github` folder must be included. Drag-and-drop on the GitHub website works.
3. Open the **Actions** tab. The "Build AB's Phone Solutions" run starts by itself and takes about 10 minutes.
4. When it shows a green tick, open the run and download **ABPhoneSolutions-Android** and **ABPhoneSolutions-Windows** from the bottom of the page (they arrive as .zip files).

If the run shows a red cross, open it, copy the error text and send it to Claude to fix.

*Without GitHub:* open the `android` folder in Android Studio and choose Build › Build APK. On the PC, install Python 3 and run `python abps_pc.py` instead of the .exe.

## Step 2 — Set up the PC

1. Run `ABPhoneSolutions-PC.exe`. A console window and a control page in your browser open. Keep the window open while backing up.
2. When Windows Firewall asks, allow it on **Private networks** (needed for Wi-Fi).
3. Pick the backup folder on the control page (by default `D:\Phone Backup` if you have a D: drive).
4. **For the USB-C cable:** download Google's free *SDK Platform-Tools for Windows* (search "Android platform tools download"). From the zip, put `adb.exe`, `AdbWinApi.dll` and `AdbWinUsbApi.dll` in the same folder as `ABPhoneSolutions-PC.exe`. The control page shows when the cable link is ready.

## Step 3 — Set up the phone

1. Copy `ABPhoneSolutions.apk` to the phone and open it. Allow "install unknown apps" for your file manager when asked.
2. Open the app and tap **Allow file access**. Turn on "Allow access to manage all files", then come back.
3. **For the USB-C cable**, once only: Settings › About phone › tap *Build number* 7 times. Then Settings › System › Developer options › turn on **USB debugging**. Plug into the PC and tap **Allow**.
4. In the app: **Backup › USB-C cable** (or **Same Wi-Fi**) › enter the 6-digit code shown on the PC.

## Speed

The PC program saves at over 300 MB/s on its own, so the phone and the link set the speed:

* **USB-C cable:** usually 30–40 MB/s on a USB 2 cable or port, and often 80–150 MB/s with a USB 3 cable in a USB 3 port, if the phone supports USB 3.
* **Same Wi-Fi:** usually 15–60 MB/s, depending on the router and distance.

The app sends 4 files at a time, skips files that are already on the PC, and retries a file up to 3 times if the link drops.

## Test checklist (please try in this order)

1. **Pairing:** a wrong code is refused; the right code links the phone. The PC control page lists the phone.
2. **Copy backup of Documents:** check that the files appear under `Phone Backup\<phone name>\Documents\...`. Run it again; it should say they were already on the PC.
3. **Move backup of a few photos:** use a test folder first. Check that the approval screen lists them and that they're on the PC before you approve. Then tap "Keep them on the phone" once to confirm nothing is removed.
4. **Pull the cable mid-transfer:** the app should report the failed files. Nothing should be removed. Start again and only the missing files are sent.
5. **Organize:** read the documents and check the suggested folders. Try "Copy instead" first, then **Undo**.
6. **Clean:** check that "Latest WhatsApp chat backup" is marked *Kept*. Set some old backups aside, then **Restore** them.

## What's next (phase 2)

* System restore for a phone that won't start, from the PC program over USB, using the phone maker's official software. Phones whose bootloader is locked will be limited to the maker's own repair tools.
* An encrypted link for Wi-Fi and for backing up from a different network.
* Running in the background so the screen can turn off during long transfers.
* Diagnostics screens from the prototype.

## Project layout

```
android/   Android Studio project (Kotlin, Jetpack Compose)
  app/src/main/java/com/abs/phonesolutions/
    core/PcLink.kt      link to the PC: discovery, pairing, verified upload
    core/PhoneFiles.kt  finding user files, phone status, permission
    core/Organizer.kt   reading text (PDF, Office, photos via on-device OCR), sorting, undo
    core/Cleaner.kt     finding outdated app backups, 7-day hold, restore
    AppViewModel.kt     the steps and approvals for each activity
    ui/                 screens
pc/abps_pc.py           PC program (no extra packages needed)
pc/test_pc.py           automatic test that simulates a phone
.github/workflows/      free cloud build of the .apk and .exe
```
