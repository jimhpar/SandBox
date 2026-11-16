# Sandbox (v2.0.2)
**Publisher:** Blackbox THC  
**Target Platforms:** Android & iOS (Cross-Platform Dual Space Architecture)

---

## Overview

**Sandbox** is an isolated dual space and app containerization environment engineered by **Blackbox THC**. It allows users to clone and run multiple independent instances of social media apps, games, and utilities side-by-side with genuine isolation, device hardware spoofing, and full support for Google Play Services and In-App Billing.

---

## Key Features

- **Hybrid Dual Space Engine:**
  - **Primary Clone (Level 1):** Official Work Profile integration with 100% genuine Google Play Store, Google Play Services, and In-App Billing bridge. Features an **Orange "SB"** badge.
  - **Multi-Instance Clones (Level 2+):** Dynamic Multi-Account engine for 3rd, 4th, and 5th accounts (using Supercell ID, Facebook, or Email). Features an attractive **Vibrant Pink "SB"** badge.
- **Hardware & Device ID Spoofing:** Randomizes `Android ID` and device fingerprints (Galaxy S24 Ultra, Pixel 9 Pro, OnePlus 12) per instance for anti-ban and privacy isolation.
- **Home Screen Shortcuts:** Pinned shortcuts with dynamic circular "SB" badges at the bottom-left corner.
- **3-Dot Context Menu:** Quick actions for each cloned app:
  - 📌 Add / Recreate Home Screen Shortcut
  - 🧹 Clear Cache
  - 🗑️ Clear All Data
  - ❌ Delete Cloned App
- **iOS Power User Cloner Bridge:** Dynamic IPA patcher, `CFBundleIdentifier` mutator, and Keychain sanitizer ready for AltStore / SideStore / TrollStore.

---

## Project Structure

```
SandBox/
├── android/
│   ├── app/src/main/
│   │   ├── aidl/com/android/vending/billing/ # Google Play Billing AIDL
│   │   ├── kotlin/com/blackbox/sandbox/
│   │   │   ├── billing/                      # BillingBridgeManager
│   │   │   ├── core/                         # SandboxManager, SandboxContainerActivity, DeviceAdmin
│   │   │   └── vfs/                          # Virtual File System
│   │   └── res/                              # Adaptive icons, styles, device_admin.xml
├── ios/
│   └── Runner/                               # IPAPatcherEngine & iOS Power User bridges
├── lib/
│   ├── models/                               # ClonedInstance data model
│   ├── screens/                              # DashboardScreen (Cyberpunk Blackbox THC UI)
│   ├── services/                             # SandboxService (MethodChannels)
│   └── main.dart                             # App entrypoint
└── icon/                                     # Official Sandbox App Icon
```

---

## Getting Started

### Prerequisites
- Flutter SDK (>= 3.13.7)
- Android SDK (API 34/36)
- JDK 17

### Build & Run
```bash
# Get dependencies
flutter pub get

# Run on connected device
flutter run

# Build release APK
flutter build apk
```

---

## License
Proprietary • Copyright © 2026 **Blackbox THC**. All rights reserved.
