# Eric's To-Do

One place for all your tasks: two lists (Business and Personal), big clear text, drag to reorder, and a home-screen widget you can stretch to fill the whole screen.

Feed it old lists by **taking a picture**, **choosing photos/screenshots**, **pasting**, or **sharing from Voice Log**. It finds the tasks, files them under Business or Personal, and catches repeats: a repeat gets merged into the task you already have and shows a count (**×2**, **×3**…). It always asks before merging.

No account, no subscription. Tasks stay on the phone, and can sync between your phone and tablet through your own Google Drive. Android 10 or newer.

## Install / update

On your phone open **https://github.com/eet1125-AI/TodoManager/releases/latest** and tap **ToDo.apk**. Allow "install unknown apps" the first time. New versions install over the old one and keep your tasks. The version number is at the bottom of Settings.

## Daily use

| Do this | What happens |
|---|---|
| Tap **BUSINESS** / **PERSONAL** at the top | Switches lists. The number is how many open tasks are on that list. |
| Type in the box at the bottom, tap **+** | Adds the task to the top of the list you're on. |
| Tap the circle | Checks the task off. **UNDO** appears for a few seconds. |
| Drag the **≡** handle (or press and hold a task) | Moves it up or down. Top = most important. |
| Tap a task | Edit the wording, mark urgent, reset the ×count, delete. |
| **⋮** on a task | Move to top/bottom, mark urgent, move to the other list, delete. |
| **Done (n)** at the bottom | Shows finished tasks. Tap a circle to bring one back. **Clear done** removes them. |
| Top **⋮** menu | Find duplicates, Share this list, Urgent to the top, Settings. |

## Adding old lists (Add notes)

1. Tap **Add notes**. Pick which list tasks go to when the notes don't say Business or Personal.
2. Choose **Take a picture**, **Choose photos**, **Paste what I copied**, or type/paste in the box and tap **Find the tasks**.
3. **Check before adding** screen: each task shows its list (tap to switch), URGENT (tap to toggle), and editable wording.
   - **You already have this task** → **Yes, merge** combines them and raises the count, **Keep both** adds it separately, **Skip** ignores it.
   - **You already finished this** → defaults to Skip; **Reopen** puts it back on your list.
   - **Merge all repeats** / **Keep all as new** set every repeat at once.
4. Tap **Add N to my lists**.

**From Voice Log:** open a project's to-do list → **Send to To-Do**. It goes straight to the check screen.

The reader understands checkboxes (☐ ☑ [ ] [x]), bullets, numbering, "– URGENT", and headings like BUSINESS TASKS / PERSONAL TASKS. Checked-off items are marked and skipped by default.

## Sync phone and tablet (Firebase)

1. **First device:** Settings → **Sync phone & tablet** → enter your email and a password → **Create account**.
2. **Every other device:** same email and password → **Sign in**. The first time, both lists are combined (use **Find duplicates** to tidy doubles).
3. That's it. Every change is sent within a second and shows up on the other device by itself. The sync icon (two arrows) forces a sync; Settings shows "Up to date" and the time.

Each device keeps its own copy, so the app works with no signal and catches up when it's back online. Rules: newest change wins, deletions apply everywhere, the most recent drag sets the order.

Setup (done once): Firebase project with Email/Password sign-in and a Firestore database; `app/google-services.json` from that project is in this repo. Without that file the app builds but sync stays off.

## AI helper (optional, Google Gemini)

Settings → paste a free key from https://aistudio.google.com/apikey → **Save & test** (the same key as Voice Log works).

With AI on, it reads **handwriting**, cleans up wording, sorts Business vs Personal using ARMSCO context, and matches repeats even when worded differently. Without it, pasted text and printed/typed photos are read on the phone, and repeats are matched by wording. If the AI fails (no signal, limit reached), the app falls back to reading on the phone and tells you.

## Widget (full screen)

Long-press an empty spot on the home screen → **Widgets** → **Eric's To-Do** → drop it, then drag its edges to fill the screen. Tap **BUSINESS / PERSONAL** in the widget to switch (each widget remembers its own list, so you can put one of each on different screens). Tap a task to check it off; the **↶** button undoes the last one. **+** opens the app. Widget text follows the app's text size.

## Settings

Text size (Small → Giant; also "Smaller text / Bigger text" in the main ⋮ menu), light or dark background, AI key and model, **Sync phone & tablet**, **Save backup / Restore** (without sync, tasks live only on the phone; uninstalling deletes them).

## Project layout

```
app/src/main/java/com/eric/todo/
  MainActivity.kt          screens, share-in from other apps
  AppViewModel.kt          import: read text/photos, AI, build the check list
  data/TaskStore.kt        tasks.json storage, merge, reorder, undo
  data/SyncMerge.kt        how phone + tablet changes are combined (tested in app/src/test)
  sync/CloudSync.kt        live sync through Firebase (sign-in + online database)
  data/Settings.kt         settings
  importer/TextParser.kt   turns pasted/OCR text into tasks
  importer/Dupes.kt        "same task?" matching
  importer/Ocr.kt          on-phone photo text reading (ML Kit)
  ai/Gemini.kt             AI reading and sorting
  ui/                      Main, Add notes, Duplicates, Settings screens
  widget/TaskWidget.kt     home-screen widget
```

`app/todo-release.jks` signs every build with the same key so updates install over the old version. Keep this repo private.
