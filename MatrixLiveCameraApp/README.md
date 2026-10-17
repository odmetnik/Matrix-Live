# Phoenix Matrix Live Camera — Android

New Android app in the existing odmetnik/Matrix-Live repository. **The old Phoenix Matrix Wallpaper ZIP and its workflow on the main branch are left unchanged.**

## Features
- CameraX, front selfie camera by default, switchable to back camera.
- Camera grayscale is rotated/mirrored, downsampled, contrast-adjusted and represented **only by green glyphs**.
- Digital rain falling through the camera-derived glyphs; optional neon highlights and contour enhancement.
- Defaults: density **75%**, rain speed **55%**, contrast **70%**, glow **ON**, contours **ON**.
- Persistent settings, adjustable Battery (20 FPS), Balanced (30 FPS), Ultra (60 FPS) profiles.
- Live Wallpaper animates green glyphs using the **last explicitly saved camera frame**. Without saved frame, it animates pure Matrix code rain.
- No internet permission, no accounts, no advertising or tracking.

## APK build through GitHub Actions
1. Open GitHub repository -> **Actions** -> **Build Matrix Live Camera APK**.
2. Open the run for branch feature/matrix-live-camera.
3. If successful, download artifact **MatrixLiveCamera-debug-APK**, or download the test build from **Releases**.
4. Install APK on Samsung/Android (Android 8+); you may need to allow installation from your browser/Files app.

The CI uses JDK 17, Gradle 8.9 and Android SDK 35. No Android Studio needed for GitHub build.

## Operation
- Start app, grant CAMERA runtime permission, point the chosen camera at your surroundings.
- Adjust parameters; they are saved locally. Select the FPS performance mode.
- Tap **Spremi kadar** to store one grayscale frame **in private app files**.
- Tap **Postavi Matrix Live Wallpaper**, then select it in the Android system picker.
- After saving a new frame, wallpaper refreshes it the next time it becomes visible (e.g. return to home screen).

## Android limitation (intentional)
Android limits background camera use. **Live camera is only available inside the foreground Activity**, never inside the wallpaper engine. The background wallpaper is an animation over the last saved still frame. This is not a constant live camera wallpaper and does not attempt to bypass platform camera or privacy restrictions.

## Technical details
- Kotlin, native Canvas rendering, CameraX ImageAnalysis Y-plane input, Android WallpaperService.
- Different package ID from the earlier app: com.phoenixart.matrixcamera.
- Source and build settings inside MatrixLiveCameraApp/; independent of older ZIP project.
- This code must be verified by GitHub Actions and then tested on real hardware for camera rotation, performance and wallpaper picker behavior.

## Privacy
Camera permission only. Snapshot saved to app-private storage. No network permission. Remove the app to remove stored frames; saved frames are not exported.

## License
MIT.
