# One-tap phone transfer

BizCard Scanner v0.1.1-beta adds a one-tap **轉移到新手機** action on the backup screen.

Flow:

1. Generate the existing full ZIP backup (JSON + CSV + front/back card images) into app cache.
2. Expose only that temporary ZIP through AndroidX FileProvider with temporary read permission.
3. Open Android's standard share sheet so the user can choose Quick Share, Google Drive, LINE, Mail, or another ZIP-capable target.
4. On the destination phone, install BizCard Scanner and use **備份與匯出 → 還原備份** to import the ZIP.

The app still does not request INTERNET or broad storage permissions. The temporary transfer ZIP is replaced the next time a transfer package is generated and can also be reclaimed by Android as cache.
