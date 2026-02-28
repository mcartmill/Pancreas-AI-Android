PancreasAI — Recent Changes

V1.0.1: Chart Fixes \& Insulin Decimals

Insulin Tracking

Units input now supports 2 decimal places (e.g. 12.95) — hint updated to reflect this

Dose display in the log list changed from 1 to 2 decimal places (e.g. 12.95 u • Rapid)

24-hour total also shows 2 decimal places

Save confirmation toast updated to match

Post-Meal Response Chart

Fixed critical bug: the green target zone was being drawn last, covering all the curve lines — it now renders first so lines appear on top

Target zone is now semi-transparent (50% alpha) instead of a solid block

Chart height increased from 120dp to 160dp for more breathing room

Y-axis labels added on the left (+0, +40, +80 mg/dL)

Horizontal grid lines added at each Y-axis tick

Faint individual meal lines made more visible (thicker, higher opacity)

Average line bumped to 3dp width

"— avg rise" legend label added

Post-Insulin Response Chart

Same zone-ordering fix applied (zone drawn behind lines)

Zone color changed to semi-transparent cyan to match the insulin theme

Chart height increased from 120dp to 160dp

Y-axis labels added (-0, -30, -60 mg/dL showing the drop)

Horizontal grid lines added

Faint individual dose lines made more visible

Average line bumped to 3dp width

"— avg drop" legend label added



V1.0.2: Personal Info, Thresholds \& Notifications

Personal Info (new Settings card)

Height (inches), weight (lbs), age, and sex fields

Sex uses a dropdown (Female, Male, Non-binary, Other, Prefer not to say)

All fields saved with a single "Save Info" button and passed to the AI Insights prompt for more personalized suggestions

Glucose Thresholds (new Settings card)

High threshold slider: 120–300 mg/dL, default 180

Low threshold slider: 50–100 mg/dL, default 70

Changes apply live across the entire app — chart dashed reference lines, HIGH/IN RANGE/LOW label, time-in-range stats, and notification triggers all use the configured values instead of hardcoded numbers

Notifications (new Settings card)

Master toggle requests POST\_NOTIFICATIONS permission on Android 13+ before enabling

Sub-toggle for projecting high glucose events (orange)

Sub-toggle for projecting low glucose events (red)

Projection window slider: 10–40 minutes, default 20 min

Alert engine runs after every data refresh — uses a weighted rate-of-change calculation across the last 3–4 readings (most recent interval weighted 2:1)

30-minute cooldown per alert type to prevent repeated notifications

Tapping a notification opens the app directly

New GlucoseAlertManager.kt file added

POST\_NOTIFICATIONS permission added to AndroidManifest.xml

PancreasAI — Update Release Notes

Security \& Encryption Update

February 27, 2026



Overview of V1.0.3

This update brings all locally stored health data into compliance with HIPAA's Technical Safeguard requirements for encryption at rest. Every file containing Protected Health Information (PHI) is now encrypted using AES-256-GCM via the Android Keystore — the same standard used by banking and healthcare apps.



What Changed

New: SecureFileStore (SecureFileStore.kt)

A new encryption layer was introduced to handle all PHI file I/O. It wraps AndroidX EncryptedFile and provides a clean read/write/delete API used across all three health data stores. Key details:

Algorithm: AES-256-GCM-HKDF-4KB (Authenticated Encryption with Associated Data — provides both confidentiality and integrity protection against tampering)

Key storage: Android Keystore hardware-backed secure enclave (the encryption key never touches the filesystem and cannot be extracted)

Secure deletion: When migrating or overwriting, old files are zeroed out before deletion to reduce residual data risk

Glucose Log — now encrypted

glucose\_log.json stores up to 13 months of CGM readings including timestamps, glucose values, and trend arrows. Previously written as plain JSON. Now encrypted at rest via SecureFileStore.

Insulin Log — now encrypted

insulin\_log.json stores all logged insulin doses including units, type (rapid/long/other), timestamps, and notes. Previously written as plain JSON. Now encrypted at rest via SecureFileStore.

Food Log — now encrypted

food\_log.json stores all logged meals including food names, carbohydrate and calorie counts, meal type, timestamps, and notes. Previously written as plain JSON. Now encrypted at rest via SecureFileStore.

Credentials \& Settings — already encrypted (no change needed)

All credentials, tokens, API keys, personal info, and settings stored via CredentialsManager were already protected with EncryptedSharedPreferences (AES-256-GCM) from a prior version. No changes were required here.



Automatic Migration for Existing Users

No manual steps are required. On first launch after the update, the app automatically detects any plain-text data files left over from prior versions, re-encrypts them in place, and securely wipes the originals. The process is silent and no data is lost.



Known Limitation — Exported HTML Reports

When you export a report and share it via email, Google Drive, or another app, a temporary HTML file is written to device storage in an unencrypted state. This is unavoidable — the receiving app must be able to read the file. Users should treat exported reports as sensitive documents and delete them from their Downloads folder or sharing destination once they have been used.



Full Encryption Summary

Data

Storage Method

Encryption

Dexcom credentials

EncryptedSharedPreferences

AES-256-GCM ✅

OAuth tokens

EncryptedSharedPreferences

AES-256-GCM ✅

API keys (Claude, etc.)

EncryptedSharedPreferences

AES-256-GCM ✅

Personal info (height, weight, age)

EncryptedSharedPreferences

AES-256-GCM ✅

App settings \& thresholds

EncryptedSharedPreferences

AES-256-GCM ✅

Glucose readings log

EncryptedFile (SecureFileStore)

AES-256-GCM ✅

Insulin dose log

EncryptedFile (SecureFileStore)

AES-256-GCM ✅

Food / meal log

EncryptedFile (SecureFileStore)

AES-256-GCM ✅

Exported HTML reports

Temporary plain file (shared)

⚠️ Unencrypted





PancreasAI is a personal project and is not a certified HIPAA Business Associate. This update implements encryption at rest as a best-practice technical safeguard. It does not constitute a formal compliance certification.

PancreasAI — Release Notes

Version 1.0.4

February 28, 2026



🐛 Bug Fixes

Fixed intermittent "AccountPasswordInvalid" error on refresh The app was performing a full Dexcom re-authentication on every single data refresh. Dexcom occasionally returns a transient server error during authentication which the app was misreading as a genuine password failure — showing a scary red error card even when your credentials were perfectly fine. The app now caches your session and reuses it across refreshes, only re-authenticating when the session actually expires. The error should no longer appear for users whose credentials are correct.

Improved Google SSO account detection and error messaging When Dexcom explicitly rejects a password (rather than a transient server error), the app now detects this immediately and stops retrying. Gmail and Google-mail accounts receive a specific explanation explaining that Sign in with Google accounts require a native Dexcom password to be set via the Forgot Password flow, along with step-by-step instructions to fix it. The Diagnostics tool also now flags this inline with a ⚠️ warning so it's immediately clear what the issue is.



✨ New Features

Editable insulin log entries Insulin doses can now be edited after saving. Each entry in the Insulin Log shows a new cyan ✎ button alongside the existing delete button. Tapping it opens the log dialog pre-filled with all the original values — units, type, time, and note — ready to correct. Saving updates the entry in place, preserving its position on the glucose chart.

Editable food / meal log entries Meals can now be edited after saving. Same as insulin — tap ✎ on any food entry to open the edit dialog with all fields pre-filled: food name, carbs, calories, meal type, time, and note. Updates are reflected immediately on the chart and in the 24-hour totals.



What's Unchanged

All glucose data, insulin logs, food logs, and settings carry over automatically — no action needed on update. The session fix is transparent; the app will use a fresh login on first launch after updating, then cache it going forward.



PancreasAI is a personal project and is not a medical device. Always consult your healthcare team before making changes to your insulin regimen.



