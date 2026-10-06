# nonfungible.cash for Android: Developer & Agent Guidelines

This repository contains the source code for the nonfungible.cash Android app, both sides of the door at events whose tickets are image NFTs. [How the door decides](docs/door.md) holds the principles the code follows. These guidelines serve as the definitive guide for both human developers and autonomous AI coding agents to ensure consistency, quality, and maintainability across the codebase.

## 1. Project Overview & Tech Stack
- **Platform:** Android (Min SDK 26, Target SDK 36, Java 17).
- **Language:** Kotlin (primary), Java (legacy/interop).
- **Architecture:** Activities with ViewBinding and bottom sheets: the door, a decision or one NFT, a collection's list, minting, prices and settings for an organiser; a guest's NFTs, one NFT's page and a collection's sales for a guest. The protocol, the door's decision, the rotating codes and what is kept for each collection live in `entry/` and have no UI. No Jetpack Compose.
- **Networking & Data:** OkHttp3, Gson, upokecenter CBOR (the request).
- **Domain Specifics:** BouncyCastle for nostr crypto. The mint verifies proofs; the app carries no Cashu SDK and no pairing code.
- **UI Features:** ZXing (QR generation), WebView (two hidden pages that draw NFTs and mint them, see `docs/making.md`). The look is nonfungible.cash's: its colours, its bordered shapes with a hard shadow (`res/drawable/bg_*.xml`) and its three typefaces (`res/font`, see `docs/fonts.md`). Build screens from the styles in `res/values/themes.xml`, not from Material's defaults.

## 2. Build & Execution Commands

### Building the App
```bash
# Build the standard Debug APK
./gradlew assembleDebug

# Build the Release APK (requires signing configs if set up)
./gradlew assembleRelease
```
*Output APK location: `app/build/outputs/apk/debug/app-debug.apk`*

### Linting
```bash
# Run Android Lint checks
./gradlew lintDebug
```
*Note: There is no strict auto-formatter configured (like ktlint or spotless) in the build script. Follow standard IDE formatting.*

## 3. Testing Commands & Guidelines

The app's rules are unit tested: Robolectric stands in for the Android framework and a `MockWebServer` stands in for the site and its mint.

### Running Tests
**Run all unit tests:**
```bash
./gradlew testDebugUnitTest
```

**Run all tests in a specific class:**
```bash
./gradlew testDebugUnitTest --tests "cash.nonfungible.app.entry.DoorTest"
```

**Run a single, specific test method (CRITICAL FOR AGENTS):**
Use quotes around the class and method name. If the method uses Kotlin backticks for spaces, include the spaces exactly as they appear in the code (without the backticks in the CLI command).
```bash
./gradlew testDebugUnitTest --tests "cash.nonfungible.app.entry.DoorTest.a ticket is admitted once"
```

### Writing Tests
- **Framework:** JUnit 4 (`@Test`, `@Before`).
- **Vectors:** Protocol bytes are checked against vectors made by the reference implementation, in `app/src/test/resources`. Do not hand-edit them.
- **Android APIs:** Annotate test classes with `@RunWith(RobolectricTestRunner::class)` when accessing Android specific classes (`Context`, `Intent`).
- **Network:** Use `MockWebServer`, answering as the site does, rather than mocking classes.
- **Naming:** Use backticks for descriptive, human-readable test names in Kotlin:
  ```kotlin
  @Test
  fun `a mint that cannot be reached refuses`() { ... }
  ```
- **Location:** Unit tests in `app/src/test/java/cash/nonfungible/app/`.

## 4. Code Style & Formatting

- **Indentation:** 4 spaces (never tabs).
- **Line Length:** ~100 characters max, optimize for readability.
- **Imports:**
  - Use explicit imports (avoid `import com.foo.*`).
  - Group Android/Java/Kotlin imports separately from project-specific imports if possible.
  - Remove unused imports before committing.

### Naming Conventions
- **Classes/Interfaces/Objects:** `PascalCase` (e.g., `ResultActivity`).
- **Functions/Properties:** `camelCase` (e.g., `collectionName`, `assetHash`).
- **Constants:** `SCREAMING_SNAKE_CASE` (e.g., `EXTRA_ARRIVAL`).
- **Layouts/Resources:** `snake_case` (e.g., `activity_door.xml`, `ic_back.xml`).
- **IDs:** `snake_case` (e.g., `checking_name`).

## 5. Architecture & Best Practices

### UI & View Binding
- **ViewBinding:** Enabled (`viewBinding = true`).
  - Always use ViewBinding; there is no `findViewById` in the codebase.

### Kotlin Idioms & Coroutines
- **Null Safety:** Avoid the double-bang operator (`!!`) at all costs. Use safe calls (`?.`), Elvis (`?:`), or explicit null checks.
- **Coroutines:** Never use `GlobalScope`.
  - In Activities: `lifecycleScope.launch { ... }`
- **Dispatchers:** Use `Dispatchers.IO` for disk reads, network requests, and heavy crypto. Shift to `Dispatchers.Main` for UI updates.

### Error Handling & Logging
- **Try/Catch:** Wrap risky operations (networking, JSON parsing). At the door a failure refuses; it never admits.
- **Avoid Swallowing Exceptions:** At minimum, log the exception.
- **Logging:** Use `android.util.Log` with a class-specific `TAG` defined in a companion object at the bottom of the class.
  ```kotlin
  companion object {
      private const val TAG = "Door"
  }
  ```
  Use `Log.d(TAG, ...)` for debug info and `Log.e(TAG, "msg", exception)` for errors.
- **User Feedback:** Use Android `Toast` or `Snackbar` for actionable user errors visible on the UI.

## 6. File System & Paths
- **Source Code:** `app/src/main/java/cash/nonfungible/app/`
- **Resources:** `app/src/main/res/`
- **Manifest:** `app/src/main/AndroidManifest.xml`
- **Tests:** `app/src/test/java/cash/nonfungible/app/`

## 7. Workflow & Operational Directives for Agents

1. **Information Gathering First:** NEVER assume a class name, layout ID, or project structure. Use `glob` to find files and `grep` to find specific usages or definitions before writing code.
2. **Absolute Paths:** When using the `read` or `write` tools, always construct full absolute paths using the workspace root (e.g., `<workspace>/app/src/main/java/...`).
3. **Verify Dependencies:** Before adding a new library, verify if an existing one (e.g., Gson for JSON) already solves the problem. Check `app/build.gradle.kts`.
4. **Self-Correction Loop:** If you modify logic, immediately run the corresponding unit test. If a test doesn't exist, create a fast, localized unit test before verifying manually or via UI.
5. **Build Before Finish:** Ensure the app builds (`./gradlew assembleDebug`) before declaring a task complete.
6. **No Hallucinated Tooling:** Do not attempt to run `ktlint`, `detekt`, or `spotless` unless explicitly configured in the project. Rely on `./gradlew lintDebug` and compilation checks.
