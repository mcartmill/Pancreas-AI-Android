@echo off
setlocal enabledelayedexpansion
title Pancreas AI v15 - Project Setup

echo.
echo  ============================================================
echo   PANCREAS AI v15 - Android Project Setup
echo   Target: D:\Projects\Pancreas-AI
echo  ============================================================
echo.

set "ROOT=D:\Projects\Pancreas-AI"
set "PKG=%ROOT%\app\src\main\java\com\pancreas\ai"
set "RES=%ROOT%\app\src\main\res"
set "IDEA=%ROOT%\.idea"
set "BAT_DIR=%~dp0"

:: ═════════════════════════════════════════════════════════════════
:: REQUIRE ANDROID STUDIO TO BE CLOSED
:: ═════════════════════════════════════════════════════════════════
echo Checking whether Android Studio is running...
tasklist /fi "imagename eq studio64.exe" 2>nul | find /i "studio64.exe" >nul
if not errorlevel 1 (
    echo.
    echo  *** Android Studio is still OPEN. Close it and run setup.bat again. ***
    echo.
    pause
    exit /b 1
)
echo    [OK] Android Studio is not running.

:: ═════════════════════════════════════════════════════════════════
:: NUKE .idea SO ANDROID STUDIO STARTS FRESH
:: ═════════════════════════════════════════════════════════════════
if exist "%IDEA%" (
    rd /s /q "%IDEA%" 2>nul
    if exist "%IDEA%" (
        echo  [ERROR] Could not delete %IDEA% - close Android Studio and retry.
        pause & exit /b 1
    )
    echo    [OK] Deleted stale .idea
)

:: ═════════════════════════════════════════════════════════════════
:: DETECT JBR PATH
:: ═════════════════════════════════════════════════════════════════
set "AS_JBR="
for %%P in (
    "%PROGRAMFILES%\Android\Android Studio\jbr"
    "%PROGRAMFILES%\Android\Android Studio Preview\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio Preview\jbr"
    "C:\Android\Android Studio\jbr"
) do ( if "!AS_JBR!"=="" if exist "%%~P\bin\java.exe" set "AS_JBR=%%~P" )

if "!AS_JBR!"=="" (
    for /f "tokens=2*" %%A in (
        'reg query "HKLM\SOFTWARE\Android Studio" /v "Path" 2^>nul'
    ) do ( if exist "%%B\jbr\bin\java.exe" set "AS_JBR=%%B\jbr" )
)
if "!AS_JBR!"=="" if defined JAVA_HOME if exist "!JAVA_HOME!\bin\java.exe" set "AS_JBR=!JAVA_HOME!"

if "!AS_JBR!"=="" (
    echo    [WARN] JBR not found.
) else (
    echo    [OK]  JBR path: !AS_JBR!
)

:: ═════════════════════════════════════════════════════════════════
:: FIND THE REGISTERED JDK NAME FROM ANDROID STUDIO'S jdk.table.xml
::
:: Root cause of every JDK error so far:
::   gradleJvm in .idea\gradle.xml must match an entry NAME in
::   %APPDATA%\Google\AndroidStudio*\options\jdk.table.xml exactly.
::   Paths, tokens like #USE_PROJECT_JDK, #USE_GRADLE_LOCAL_JAVA_HOME —
::   all fail if they are not present in that file under that exact name.
::
::   The embedded JDK IS in jdk.table.xml (Android Studio registers it
::   at install time), but its name looks like:
::       "JBR-21.0.9-1087.21-jcef 21.0.9"   (not the path)
::
::   We use PowerShell to find that name by matching the homePath to
::   our detected JBR folder, then write gradle.xml with that exact name.
::   This is the only approach that is guaranteed to work.
:: ═════════════════════════════════════════════════════════════════
echo.
echo Locating registered JDK name in Android Studio jdk.table.xml...
set "GRADLE_JVM="

:: Use PowerShell to parse jdk.table.xml and extract the name
:: whose homePath matches our AS_JBR folder.
:: If AS_JBR is empty we still search for any JBR entry.
for /f "delims=" %%N in ('powershell -NoProfile -Command ^
    "$jbrPath = '%AS_JBR:\=\\%';" ^
    "$pattern = [System.IO.Path]::GetFullPath($jbrPath).ToLower();" ^
    "$found = $null;" ^
    "$configs = Get-ChildItem -Path ([System.IO.Path]::Combine($env:APPDATA,'Google')) -Filter 'jdk.table.xml' -Recurse -ErrorAction SilentlyContinue;" ^
    "foreach ($f in $configs) {" ^
    "  [xml]$x = Get-Content $f.FullName -Encoding UTF8 -ErrorAction SilentlyContinue;" ^
    "  if (-not $x) { continue };" ^
    "  foreach ($jdk in $x.SelectNodes('//jdk')) {" ^
    "    $home = $jdk.SelectSingleNode('homePath');" ^
    "    $name = $jdk.SelectSingleNode('name');" ^
    "    if (-not $home -or -not $name) { continue };" ^
    "    $h = [System.IO.Path]::GetFullPath($home.value).ToLower();" ^
    "    if ($h -eq $pattern -or ($jbrPath -eq '' -and $name.value -match 'JBR')) {" ^
    "      $found = $name.value; break" ^
    "    }" ^
    "  }; if ($found) { break }" ^
    "};" ^
    "if ($found) { Write-Output $found } else { Write-Output '' }" ^
    2^>nul') do set "GRADLE_JVM=%%N"

if not "!GRADLE_JVM!"=="" (
    echo    [OK]  Found registered JDK name: !GRADLE_JVM!
) else (
    echo    [INFO] Could not find a matching entry in jdk.table.xml.
    echo           Will write gradle.xml with the embedded JDK path directly.
    echo           Android Studio will prompt once to confirm - click OK.
    if not "!AS_JBR!"=="" (
        set "GRADLE_JVM=!AS_JBR!"
    )
)

:: ═════════════════════════════════════════════════════════════════
:: DETECT SDK
:: ═════════════════════════════════════════════════════════════════
set "SDK_PATH="
if exist "%LOCALAPPDATA%\Android\Sdk"  set "SDK_PATH=%LOCALAPPDATA%\Android\Sdk"
if "!SDK_PATH!"=="" if exist "C:\Android\Sdk" set "SDK_PATH=C:\Android\Sdk"
if "!SDK_PATH!"=="" if defined ANDROID_HOME   set "SDK_PATH=%ANDROID_HOME%"

:: ═════════════════════════════════════════════════════════════════
:: [1]  DIRECTORIES
:: ═════════════════════════════════════════════════════════════════
echo.
echo [1/9] Creating directory structure...
for %%D in (
    "%ROOT%" "%ROOT%\gradle\wrapper" "%IDEA%"
    "%PKG%" "%RES%\layout" "%RES%\values" "%RES%\drawable" "%RES%\menu"
    "%ROOT%\app\src\test\java\com\pancreas\ai"
    "%ROOT%\app\src\androidTest\java\com\pancreas\ai"
) do mkdir %%D 2>nul
echo    [OK] Done.

:: ═════════════════════════════════════════════════════════════════
:: [2]  GRADLE WRAPPER
:: ═════════════════════════════════════════════════════════════════
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

:: ═════════════════════════════════════════════════════════════════
:: [3]  GRADLE BUILD FILES
:: ═════════════════════════════════════════════════════════════════
echo [3/9] Writing Gradle build files...
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
echo     compileSdk 34
echo     defaultConfig {
echo         applicationId "com.pancreas.ai"
echo         minSdk 26
echo         targetSdk 34
echo         versionCode 15
echo         versionName "1.5.0"
echo     }
echo     buildFeatures { viewBinding true }
echo     buildTypes {
echo         release {
echo             minifyEnabled false
echo             proguardFiles getDefaultProguardFile^('proguard-android-optimize.txt'^), 'proguard-rules.pro'
echo         }
echo     }
echo     compileOptions {
echo         sourceCompatibility JavaVersion.VERSION_17
echo         targetCompatibility JavaVersion.VERSION_17
echo     }
echo     kotlinOptions { jvmTarget = '17' }
echo }
echo dependencies {
echo     implementation 'androidx.core:core-ktx:1.12.0'
echo     implementation 'androidx.appcompat:appcompat:1.6.1'
echo     implementation 'com.google.android.material:material:1.11.0'
echo     implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
echo     implementation 'androidx.cardview:cardview:1.0.0'
echo     implementation 'androidx.activity:activity-ktx:1.8.2'
echo     implementation 'androidx.fragment:fragment-ktx:1.6.2'
echo     implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
echo     implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.7.0'
echo     implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
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
echo    [OK] build.gradle, settings.gradle, app/build.gradle

:: gradle.properties — forward slashes are required in .properties files.
:: Backslashes are treated as escape chars and get silently eaten.
if not "!AS_JBR!"=="" (
    set "AS_JBR_FWD=!AS_JBR:\=/!"
(
echo org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
echo org.gradle.daemon=true
echo org.gradle.parallel=true
echo org.gradle.caching=true
echo org.gradle.java.home=!AS_JBR_FWD!
echo.
echo android.useAndroidX=true
echo android.enableJetifier=true
echo kotlin.code.style=official
) > "%ROOT%\gradle.properties"
    echo    [OK] gradle.properties ^(java.home=!AS_JBR_FWD!^)
) else (
(
echo org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
echo org.gradle.daemon=true
echo org.gradle.parallel=true
echo org.gradle.caching=true
echo.
echo android.useAndroidX=true
echo android.enableJetifier=true
echo kotlin.code.style=official
) > "%ROOT%\gradle.properties"
    echo    [OK] gradle.properties
)

:: ═════════════════════════════════════════════════════════════════
:: [4]  .idea FILES
::
:: We write gradle.xml using the EXACT JDK name read from Android
:: Studio's own jdk.table.xml above.  That name is already in the
:: registry so no "Undefined jdk.table.xml entry" error fires.
:: ═════════════════════════════════════════════════════════════════
echo [4/9] Writing .idea files...

if not "!GRADLE_JVM!"=="" (
(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<project version="4"^>
echo   ^<component name="GradleSettings"^>
echo     ^<option name="linkedExternalProjectsSettings"^>
echo       ^<GradleProjectSettings^>
echo         ^<option name="distributionType" value="DEFAULT_WRAPPED" /^>
echo         ^<option name="externalProjectPath" value="$PROJECT_DIR$" /^>
echo         ^<option name="gradleJvm" value="!GRADLE_JVM!" /^>
echo         ^<option name="modules"^>
echo           ^<set^>
echo             ^<option value="$PROJECT_DIR$" /^>
echo             ^<option value="$PROJECT_DIR$/app" /^>
echo           ^</set^>
echo         ^</option^>
echo       ^</GradleProjectSettings^>
echo     ^</option^>
echo   ^</component^>
echo ^</project^>
) > "%IDEA%\gradle.xml"
    echo    [OK] .idea\gradle.xml ^(gradleJvm="!GRADLE_JVM!"^)
) else (
    echo    [--] Skipping gradle.xml - no valid JDK name found. AS will create it.
)

:: misc.xml — language level only, NO projectJdkName attribute.
:: Setting projectJdkName to a value AS can't resolve causes it to
:: fall back to #USE_PROJECT_JDK in gradle.xml, which then also fails.
(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<project version="4"^>
echo   ^<component name="ProjectRootManager" version="2" languageLevel="JDK_17"^>
echo     ^<output url="file://$PROJECT_DIR$/build" /^>
echo   ^</component^>
echo ^</project^>
) > "%IDEA%\misc.xml"
echo    [OK] .idea\misc.xml

(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<project version="4"^>
echo   ^<component name="VcsDirectoryMappings"^>
echo     ^<mapping directory="$PROJECT_DIR$" vcs="Git" /^>
echo   ^</component^>
echo ^</project^>
) > "%IDEA%\vcs.xml"
echo    [OK] .idea\vcs.xml

:: ═════════════════════════════════════════════════════════════════
:: [5]  local.properties
:: ═════════════════════════════════════════════════════════════════
echo [5/9] Writing local.properties...
if not "!SDK_PATH!"=="" (
    set "SDK_FWD=!SDK_PATH:\=/!"
    echo sdk.dir=!SDK_FWD!> "%ROOT%\local.properties"
    echo    [OK] sdk.dir=!SDK_FWD!
) else (
    echo sdk.dir=C:/Users/%USERNAME%/AppData/Local/Android/Sdk> "%ROOT%\local.properties"
    echo    [WARN] SDK not detected - edit local.properties if needed.
)

:: ═════════════════════════════════════════════════════════════════
:: [6]  AndroidManifest.xml
:: ═════════════════════════════════════════════════════════════════
echo [6/9] Writing AndroidManifest.xml...
if exist "%BAT_DIR%AndroidManifest.xml" (
    copy /Y "%BAT_DIR%AndroidManifest.xml" "%ROOT%\app\src\main\AndroidManifest.xml" >nul
    echo    [OK] Copied from source folder.
) else (
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
echo    [OK] AndroidManifest.xml written.
)

:: ═════════════════════════════════════════════════════════════════
:: [7]  KOTLIN SOURCE FILES
:: ═════════════════════════════════════════════════════════════════
echo [7/9] Copying Kotlin source files...
set "KT_OK=0"
for %%F in (
    DexcomApiService.kt DexcomShareService.kt CredentialsManager.kt
    GlucoseRepository.kt GlucoseViewModel.kt MainActivity.kt
    SettingsActivity.kt OAuthWebViewActivity.kt BootReceiver.kt
    InsulinEntry.kt
    FoodEntry.kt
    ReportExporter.kt
    InsightsAnalyzer.kt
    InsightsActivity.kt
) do (
    if exist "%BAT_DIR%%%F" (
        copy /Y "%BAT_DIR%%%F" "%PKG%\%%F" >nul
        echo    [OK] %%F
        set "KT_OK=1"
    ) else echo    [MISSING] %%F
)
if "!KT_OK!"=="0" echo    ERROR: No .kt files found - extract the full zip into the same folder as setup.bat.

:: ═════════════════════════════════════════════════════════════════
:: [8]  RESOURCE FILES
:: ═════════════════════════════════════════════════════════════════
echo [8/9] Copying resource files...
for %%F in (activity_main.xml activity_settings.xml activity_oauth_webview.xml) do (
    if exist "%BAT_DIR%res\layout\%%F" ( copy /Y "%BAT_DIR%res\layout\%%F" "%RES%\layout\%%F" >nul & echo    [OK] layout\%%F ) else echo    [MISSING] res\layout\%%F
)
for %%F in (colors.xml themes.xml strings.xml) do (
    if exist "%BAT_DIR%res\values\%%F" ( copy /Y "%BAT_DIR%res\values\%%F" "%RES%\values\%%F" >nul & echo    [OK] values\%%F ) else echo    [MISSING] res\values\%%F
)
if exist "%BAT_DIR%res\menu\main_menu.xml" ( copy /Y "%BAT_DIR%res\menu\main_menu.xml" "%RES%\menu\main_menu.xml" >nul & echo    [OK] menu\main_menu.xml ) else echo    [MISSING] res\menu\main_menu.xml
if not exist "%RES%\xml" mkdir "%RES%\xml"
if exist "%BAT_DIR%res\xml\file_provider_paths.xml" ( copy /Y "%BAT_DIR%res\xml\file_provider_paths.xml" "%RES%\xml\file_provider_paths.xml" >nul & echo    [OK] xml\file_provider_paths.xml ) else echo    [MISSING] res\xml\file_provider_paths.xml
for %%F in (bg_result.xml ic_launcher.xml) do (
    if exist "%BAT_DIR%res\drawable\%%F" ( copy /Y "%BAT_DIR%res\drawable\%%F" "%RES%\drawable\%%F" >nul & echo    [OK] drawable\%%F ) else echo    [MISSING] res\drawable\%%F
)

:: ═════════════════════════════════════════════════════════════════
:: [9]  PROGUARD + .gitignore + README
:: ═════════════════════════════════════════════════════════════════
echo [9/9] Finalising...
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
echo .DS_Store
echo /build
echo /captures
echo .externalNativeBuild
echo .cxx
echo *.keystore
echo .idea/workspace.xml
echo .idea/navEditor.xml
echo .idea/assetWizardSettings.xml
) > "%ROOT%\.gitignore"
echo    [OK] Done.

:: ─── Clear stale Gradle caches ────────────────────────────────────
set "GH=%USERPROFILE%\.gradle"
if defined GRADLE_USER_HOME set "GH=%GRADLE_USER_HOME%"
for %%C in ("%GH%\caches\transforms-3" "%GH%\caches\transforms-4") do (
    if exist "%%~C" ( rd /s /q "%%~C" 2>nul & echo    [OK] Cleared %%~C )
)

:: ═════════════════════════════════════════════════════════════════
echo.
echo  ============================================================
echo   Setup complete!   %ROOT%
echo  ============================================================
echo.
if not "!GRADLE_JVM!"=="" (
    echo   Gradle JVM : !GRADLE_JVM!
    echo   Source     : read from Android Studio jdk.table.xml
    echo   The JDK popup should NOT appear.
) else (
    echo   [INFO] No JDK name found in jdk.table.xml.
    echo   If a JDK prompt appears in Android Studio, click
    echo   "Use Embedded JDK" and it will not appear again.
)
echo.
echo   1. Open Android Studio  (it was closed above)
echo   2. File ^> Open ^> %ROOT%
echo   3. Gradle sync runs automatically
echo   4. Run on device/emulator ^(API 26+^)
echo   5. Settings in app ^> Dexcom Share ^> username + password
echo.
pause
endlocal
