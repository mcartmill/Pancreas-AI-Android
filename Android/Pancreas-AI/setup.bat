@echo off
setlocal enabledelayedexpansion
title Pancreas AI - Project Setup

echo.
echo  ============================================================
echo   PANCREAS AI - Android Project Setup
echo   Target: D:\Projects\Pancreas-AI
echo  ============================================================
echo.

:: ─── Detect Android SDK ───────────────────────────────────────────────────────
set "SDK_PATH="
if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_PATH=%LOCALAPPDATA%\Android\Sdk"
if "!SDK_PATH!"=="" if exist "C:\Android\Sdk" set "SDK_PATH=C:\Android\Sdk"
if "!SDK_PATH!"=="" if defined ANDROID_HOME set "SDK_PATH=%ANDROID_HOME%"

set "ROOT=D:\Projects\Pancreas-AI"
set "PKG=%ROOT%\app\src\main\java\com\pancreas\ai"
set "RES=%ROOT%\app\src\main\res"
set "BAT_DIR=%~dp0"

:: ─── [1] Directories ─────────────────────────────────────────────────────────
echo [1/9] Creating directory structure...
for %%D in (
    "%ROOT%"
    "%ROOT%\gradle\wrapper"
    "%PKG%"
    "%RES%\layout"
    "%RES%\values"
    "%RES%\drawable"
    "%RES%\menu"
    "%ROOT%\app\src\test\java\com\pancreas\ai"
    "%ROOT%\app\src\androidTest\java\com\pancreas\ai"
) do mkdir %%D 2>nul
echo    [OK] Directories created.

:: ─── [2] Gradle wrapper ───────────────────────────────────────────────────────
echo [2/9] Writing Gradle wrapper...
(
echo distributionBase=GRADLE_USER_HOME
echo distributionPath=wrapper/dists
echo distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
echo networkTimeout=10000
echo zipStoreBase=GRADLE_USER_HOME
echo zipStorePath=wrapper/dists
) > "%ROOT%\gradle\wrapper\gradle-wrapper.properties"
echo    [OK] gradle-wrapper.properties

:: ─── [3] Gradle build files ───────────────────────────────────────────────────
echo [3/9] Writing build files...
(
echo plugins {
echo     id 'com.android.application' version '8.2.2' apply false
echo     id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
echo }
) > "%ROOT%\build.gradle"

(
echo pluginManagement {
echo     repositories { google^(^); mavenCentral^(^); gradlePluginPortal^(^) }
echo }
echo dependencyResolutionManagement {
echo     repositoriesMode.set^(RepositoriesMode.FAIL_ON_PROJECT_REPOS^)
echo     repositories { google^(^); mavenCentral^(^); maven { url 'https://jitpack.io' } }
echo }
echo rootProject.name = "PancreasAI"
echo include ':app'
) > "%ROOT%\settings.gradle"

(
echo plugins {
echo     id 'com.android.application'
echo     id 'org.jetbrains.kotlin.android'
echo }
echo android {
echo     namespace 'com.pancreas.ai'
echo     compileSdk 33
echo     defaultConfig {
echo         applicationId "com.pancreas.ai"
echo         minSdk 26; targetSdk 33; versionCode 1; versionName "1.0.0"
echo     }
echo     buildFeatures { viewBinding true }
echo     buildTypes {
echo         release {
echo             minifyEnabled false
echo             proguardFiles getDefaultProguardFile^('proguard-android-optimize.txt'^), 'proguard-rules.pro'
echo         }
echo     }
echo     compileOptions {
echo         sourceCompatibility JavaVersion.VERSION_11
echo         targetCompatibility JavaVersion.VERSION_11
echo     }
echo     kotlinOptions { jvmTarget = '11' }
echo }
echo dependencies {
echo     implementation 'androidx.core:core-ktx:1.10.1'
echo     implementation 'androidx.appcompat:appcompat:1.6.1'
echo     implementation 'com.google.android.material:material:1.9.0'
echo     implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
echo     implementation 'androidx.activity:activity-ktx:1.7.2'
echo     implementation 'androidx.fragment:fragment-ktx:1.6.1'
echo     implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2'
echo     implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.6.2'
echo     implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'
echo     implementation 'androidx.security:security-crypto:1.1.0-alpha06'
echo     implementation 'com.squareup.retrofit2:retrofit:2.9.0'
echo     implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
echo     implementation 'com.squareup.okhttp3:okhttp:4.12.0'
echo     implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
echo     implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
echo     implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
echo     testImplementation 'junit:junit:4.13.2'
echo }
) > "%ROOT%\app\build.gradle"
echo    [OK] build.gradle + settings.gradle

:: ─── [3b] gradle.properties ──────────────────────────────────────────────────
(
echo org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
echo org.gradle.daemon=true
echo org.gradle.parallel=true
echo org.gradle.caching=true
echo.
echo # AndroidX + Jetifier -- required for security-crypto and material components
echo android.useAndroidX=true
echo android.enableJetifier=true
echo.
echo # Kotlin code style
echo kotlin.code.style=official
) > "%ROOT%\gradle.properties"
echo    [OK] gradle.properties ^(AndroidX + Jetifier enabled^)

:: ─── [4] local.properties ─────────────────────────────────────────────────────
echo [4/9] Writing local.properties...
if not "!SDK_PATH!"=="" (
    set "SDK_ESC=!SDK_PATH:\=\\!"
    echo sdk.dir=!SDK_ESC!> "%ROOT%\local.properties"
    echo    [OK] SDK: !SDK_PATH!
) else (
    echo sdk.dir=C\:\\Users\\%USERNAME%\\AppData\\Local\\Android\\Sdk> "%ROOT%\local.properties"
    echo    [WARN] SDK not detected. Edit local.properties manually.
)

:: ─── [5] AndroidManifest.xml ──────────────────────────────────────────────────
echo [5/9] Writing AndroidManifest.xml...
(
echo ^<?xml version="1.0" encoding="utf-8"?^>
echo ^<manifest xmlns:android="http://schemas.android.com/apk/res/android"^>
echo     ^<uses-permission android:name="android.permission.INTERNET" /^>
echo     ^<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /^>
echo     ^<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /^>
echo     ^<application
echo         android:allowBackup="true"
echo         android:icon="@drawable/ic_launcher"
echo         android:label="Pancreas AI"
echo         android:roundIcon="@drawable/ic_launcher"
echo         android:supportsRtl="true"
echo         android:theme="@style/Theme.PancreasAI"
echo         android:usesCleartextTraffic="false"^>
echo         ^<activity android:name=".MainActivity" android:exported="true"
echo             android:launchMode="singleTop"
echo             android:windowSoftInputMode="adjustResize"^>
echo             ^<intent-filter^>
echo                 ^<action android:name="android.intent.action.MAIN" /^>
echo                 ^<category android:name="android.intent.category.LAUNCHER" /^>
echo             ^</intent-filter^>
echo         ^</activity^>
echo         ^<activity android:name=".SettingsActivity" android:exported="false"
echo             android:label="Settings"
echo             android:parentActivityName=".MainActivity" /^>
echo         ^<activity android:name=".OAuthWebViewActivity" android:exported="false"
echo             android:label="Connect to Dexcom" /^>
echo     ^</application^>
echo ^</manifest^>
) > "%ROOT%\app\src\main\AndroidManifest.xml"
echo    [OK] AndroidManifest.xml

:: ─── [6] Copy Kotlin sources ──────────────────────────────────────────────────
echo [6/9] Copying Kotlin source files...
set "KT_OK=0"
for %%F in (DexcomApiService.kt CredentialsManager.kt GlucoseRepository.kt GlucoseViewModel.kt MainActivity.kt SettingsActivity.kt OAuthWebViewActivity.kt BootReceiver.kt) do (
    if exist "%BAT_DIR%%%F" (
        copy /Y "%BAT_DIR%%%F" "%PKG%\%%F" >nul
        echo    [OK] %%F
        set "KT_OK=1"
    ) else (
        echo    [MISSING] %%F
    )
)
if "!KT_OK!"=="0" (
    echo.
    echo    ERROR: No .kt files found. Make sure ALL files from the zip
    echo    are extracted into the SAME folder as setup.bat before running.
    echo.
)

:: ─── [7] Copy resource files ──────────────────────────────────────────────────
echo [7/9] Copying resource files...
set "RES_OK=0"
for %%F in (activity_main.xml activity_settings.xml activity_oauth_webview.xml) do (
    if exist "%BAT_DIR%res\layout\%%F" (
        copy /Y "%BAT_DIR%res\layout\%%F" "%RES%\layout\%%F" >nul
        echo    [OK] layout\%%F
        set "RES_OK=1"
    ) else echo    [MISSING] res\layout\%%F
)
for %%F in (colors.xml themes.xml strings.xml) do (
    if exist "%BAT_DIR%res\values\%%F" (
        copy /Y "%BAT_DIR%res\values\%%F" "%RES%\values\%%F" >nul
        echo    [OK] values\%%F
    ) else echo    [MISSING] res\values\%%F
)
for %%F in (main_menu.xml) do (
    if exist "%BAT_DIR%res\menu\%%F" (
        copy /Y "%BAT_DIR%res\menu\%%F" "%RES%\menu\%%F" >nul
        echo    [OK] menu\%%F
    ) else echo    [MISSING] res\menu\%%F
)
for %%F in (bg_result.xml ic_launcher.xml) do (
    if exist "%BAT_DIR%res\drawable\%%F" (
        copy /Y "%BAT_DIR%res\drawable\%%F" "%RES%\drawable\%%F" >nul
        echo    [OK] drawable\%%F
    ) else echo    [MISSING] res\drawable\%%F
)

:: ─── [8] proguard + gitignore ─────────────────────────────────────────────────
echo [8/9] Writing proguard-rules + .gitignore...
(
echo -keep class com.pancreas.ai.** { *; }
echo -keep class com.google.gson.** { *; }
echo -keepattributes Signature
echo -keepattributes *Annotation*
echo -dontwarn okhttp3.**
echo -dontwarn retrofit2.**
) > "%ROOT%\app\proguard-rules.pro"

(
echo *.iml
echo .gradle
echo /local.properties
echo /.idea
echo .DS_Store
echo /build
echo /captures
echo .externalNativeBuild
echo .cxx
echo *.keystore
) > "%ROOT%\.gitignore"
echo    [OK] Done.

:: ─── [9] README ───────────────────────────────────────────────────────────────
echo [9/9] Writing README...
(
echo # Pancreas AI
echo Dexcom CGM live glucose dashboard for Android.
echo.
echo ## Setup
echo 1. Open D:\Projects\Pancreas-AI in Android Studio
echo 2. Let Gradle sync finish
echo 3. Run on a device or emulator ^(API 26+^)
echo 4. Open Settings in the app, enter Dexcom Share credentials
echo 5. Tap Test Connection to verify
echo.
echo ## Dexcom Share
echo Enable Sharing inside the Dexcom app: Menu ^> Share ^> Enable
echo US server:      https://share2.dexcom.com
echo Outside US:     https://shareous1.dexcom.com
) > "%ROOT%\README.md"
echo    [OK] README.md

:: ─── Done ─────────────────────────────────────────────────────────────────────
echo.
echo  ============================================================
echo   Setup Complete!  %ROOT%
echo  ============================================================
echo.
echo   Clearing stale Gradle transform cache to prevent jlink errors...
if exist "D:\GradleCache\caches\transforms-3" (
    rd /s /q "D:\GradleCache\caches\transforms-3" 2>nul
    echo   [OK] Cleared D:\GradleCache\caches\transforms-3
) else (
    echo   [INFO] No stale transform cache found, skipping.
)
echo.
echo   NEXT: Open Android Studio ^> File ^> Open ^> D:\Projects\Pancreas-AI
echo         Wait for Gradle sync, then click Run.
echo         Target device: Android 8+ ^(API 26+^)
echo.
pause
endlocal
