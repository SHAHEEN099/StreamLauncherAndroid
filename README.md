# StreamLauncher Android App (`stream.testuk.org`)

Native Android Application built with **Kotlin, AndroidX, and OkHttp** to provide automated, one-tap authenticated access to `stream.testuk.org` (VedStudy).

---

## Features

* **One-Tap Access**: Automatically checks local session tokens. If active, opens the streaming portal in under 1 second.
* **Autonomous 7-Step Solver**: When the 24-hour key expires or on first launch, runs the full 7-step ProLink v38 bypass in the background with live progress updates, countdown timers, and SVG CAPTCHA auto-solving.
* **Native Cookie Injection**: Pre-loads `session` and `session_expiry` directly into Android's native `CookieManager` before launching the stream.
* **Hardware-Accelerated WebView**: Chromium-based playback with support for MPEG-DASH, HLS, HTML5 media, and full-screen landscape video switching.
* **Custom PW Branding**: Configured with the transparent circular Physics Wallah (`pw.webp`) icon for home screen launcher, splash screen, and task switcher.
* **Offline Storage**: Uses Android `SharedPreferences` to persist the 24-hour key so you don't need to re-verify every time you reopen the app.

---

## Project Structure

```text
StreamLauncherAndroid/
├── .github/workflows/
│   └── build-apk.yml               <-- Cloud APK auto-builder (GitHub Actions)
├── app/
│   ├── build.gradle.kts            <-- App dependencies (OkHttp, Coroutines, Material)
│   ├── src/main/
│   │   ├── AndroidManifest.xml     <-- Hardware acceleration, orientation, internet
│   │   ├── java/com/vedstudy/streamlauncher/
│   │   │   ├── MainActivity.kt     <-- Fullscreen WebView, video player, splash UI
│   │   │   ├── KeyGenerator.kt     <-- 7-step ProLink v38 bypass & timer solver
│   │   │   ├── SvgCaptchaSolver.kt <-- Topological SVG glyph digit matcher
│   │   │   └── SessionManager.kt   <-- 24h key/cookie storage in SharedPreferences
│   │   └── res/
│   │       ├── drawable/           <-- App logo (pw.webp), cards, custom buttons
│   │       ├── layout/             <-- activity_main.xml (Dual splash + webview)
│   │       ├── mipmap-*/           <-- Adaptive Android launcher icons (all densities)
│   │       └── values/             <-- Gaming dark theme & color palette
├── build.gradle.kts
└── settings.gradle.kts
```

---

## How to Build the APK

### Method 1: Android Studio (Recommended)
1. Open **Android Studio**.
2. Click **Open** and select the folder:
   ```text
   C:\Users\Hi\.gemini\antigravity-ide\scratch\StreamLauncherAndroid
   ```
3. Wait for Gradle sync to complete.
4. Click **Build $\rightarrow$ Build Bundle(s) / APK(s) $\rightarrow$ Build APK(s)**.
5. Your APK will be generated at:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```
6. Transfer `app-debug.apk` to your phone and install!

### Method 2: Command Line (If JDK 17 is installed)
In this directory, run:
```bash
./gradlew assembleDebug
```

### Method 3: GitHub Actions (Free 1-Click Cloud Build)
1. Push this folder to a GitHub repository (Public or Private).
2. Go to the **Actions** tab on GitHub.
3. The `Build Android APK` workflow will build the APK in ~90 seconds.
4. Download the ready-to-install `app-debug.apk` from the workflow summary.
