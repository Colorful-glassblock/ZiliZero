# ZiliZero (v2026) Project Context

## Project Overview
**ZiliZero** is a next-generation, third-party Bilibili client designed specifically for Android TV. It aims to provide a high-performance, immersive "10-foot UI" experience by leveraging modern Android development standards.

The project is a direct response to the limitations of existing legacy clients (like BBLL) and official applications, focusing on:
- **Performance:** Native architecture to run smoothly on low-end TV SoCs (1GB-2GB RAM).
- **Quality:** Support for 4K, HDR, and Dolby Vision via gRPC/DASH.
- **Experience:** A fluid, focus-driven UI built with Jetpack Compose for TV.

## Architecture

### Tech Stack
- **Language:** Kotlin (100%)
- **UI Framework:** **Jetpack Compose for TV** (Material 3 TV)
    - *Reasoning:* Replaces legacy `Leanback` for better state management and customization.
- **Architecture Pattern:** **MVI (Model-View-Intent)** with Unidirectional Data Flow.
- **Player Core:** **AndroidX Media3 (ExoPlayer)**
    - *Implementation:* Custom `MergingMediaSource` to handle Bilibili's separated Video/Audio DASH streams.
- **Networking:** **Retrofit** + **gRPC (Protobuf)**
    - *HTTP:* For Wbi signing and basic metadata.
    - *gRPC:* For high-quality stream URLs (`PlayView` service).
- **Image Loading:** **Glide** (Heavily Optimized)
    - *Config:* Forced `RGB_565`, strict BitmapPool limits, and server-side scaling parameters.
- **Danmaku Engine:** **DanmakuFlameMaster (DFM)**
    - *Optimization:* Rendered on `SurfaceView` (Z-Order Top) with `SimpleTextCacheStuffer`.

### Key Design Decisions (Based on Research)
1.  **Network Defense ("Gaia"):**
    -   **Wbi Signing:** Implements dynamic key retrieval and Mixin algorithms.
    -   **Header Spoofing:** Injects PC Chrome User-Agents and specific `Referer` headers to bypass CDN 403 checks.
    -   **Fingerprinting:** Simulates WebGL/Canvas signatures for specific API endpoints.
2.  **UI/UX Standards:**
    -   **Safe Area:** Respects 5% overscan margins (48dp horizontal, 27dp vertical).
    -   **Typography:** Minimum body text size of **20sp** for 3m viewing distance.
    -   **Focus Management:** Uses `FocusRequester` and `rememberSaveable` to persist focus state across navigation (e.g., returning from Player to Grid).
3.  **Low-End Device Optimization:**
    -   **SurfaceView:** Used for both Video and Danmaku layers to bypass Main Thread blocking.
    -   **Baseline Profiles:** Included to optimize JIT compilation for faster startup and reduced jank on A53 cores.

## Directory Structure
- `app/`: Main application module.
    - `src/main/java/com/zilizero/app/`: Kotlin source code.
    - `src/main/res/`: Resources (layouts, drawables, values).
- `docx/`: Research reports and technical documentation (Source of Truth).
- `gradle/`: Gradle wrapper and catalog (`libs.versions.toml`).

## Building and Running
*Prerequisite: Android Studio Iguana+ or CLI with JDK 17+*

1.  **Sync Gradle:** Ensure `libs.versions.toml` dependencies are downloaded.
2.  **Proto Generation:** Run the Protobuf compilation task (TODO: Setup Protobuf Gradle Plugin).
3.  **Build APK:** `./gradlew assembleDebug`
4.  **Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Development Roadmap
1.  **Infrastructure (Current):** Project scaffolding, dependency management.
2.  **Core Network:** Implement Wbi signer, Proto generation, and standard Interceptors.
3.  **UI Foundation:** Login flow (QR Code), Home Grid, Detail Page.
4.  **Playback Engine:** ExoPlayer integration with DASH merging and DFM.
