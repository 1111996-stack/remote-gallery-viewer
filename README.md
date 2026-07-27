# Remote Gallery Viewer

A secure remote file access system allowing admins to browse and download images/videos from Android devices in real-time using WebRTC P2P technology.

## Features

- **Real-time P2P Transfer** - WebRTC DataChannel for direct device-to-admin file transfer
- **Firebase Signaling** - Real-time database for connection management and status tracking
- **Cloudinary Fallback** - Automatic CDN backup when P2P connection fails
- **Background Persistence** - Android foreground service with wake locks for 24/7 operation
- **Network Monitoring** - Instant online/offline status detection
- **Paginated Gallery** - Efficient loading of large media libraries
- **Cross-platform** - Web admin panel + Android app

## Tech Stack

**Frontend:** HTML5, CSS3, Vanilla JavaScript, Firebase SDK v9
**Backend:** Firebase Realtime Database, Cloudinary CDN
**Mobile:** Kotlin, Android SDK, WebRTC, Coroutines, WorkManager
**Protocols:** WebRTC (ICE/STUN), WebSockets (Firebase)

## Setup Instructions

### 1. Firebase Setup

1. Create a Firebase project at https://console.firebase.google.com/
2. Enable Realtime Database
3. Set database rules to allow read/write for development:
```json
{
  "rules": {
    ".read": true,
    ".write": true
  }
}
```

### 2. Admin Panel Configuration

1. Open `admin-panel/index.html`
2. Replace the placeholder Firebase config with your actual credentials:
```javascript
const firebaseConfig = {
  apiKey: "YOUR_FIREBASE_API_KEY",
  authDomain: "YOUR_PROJECT.firebaseapp.com",
  databaseURL: "https://YOUR_PROJECT-default-rtdb.firebaseio.com",
  projectId: "YOUR_PROJECT_ID",
  storageBucket: "YOUR_PROJECT.firebasestorage.app",
  messagingSenderId: "YOUR_SENDER_ID",
  appId: "YOUR_APP_ID"
};
```

### 3. Android App Configuration

1. In Firebase Console, add an Android app
2. Package name: `com.arman.secureviewer`
3. Download `google-services.json`
4. Place it in `android-app/app/google-services.json`

### 4. Cloudinary Setup (Optional)

1. Create a Cloudinary account at https://cloudinary.com/
2. Get your API credentials
3. Update `ViewerService.kt`:
```kotlin
val config = mapOf(
    "cloud_name" to "YOUR_CLOUDINARY_CLOUD_NAME",
    "api_key"    to "YOUR_CLOUDINARY_API_KEY",
    "api_secret" to "YOUR_CLOUDINARY_API_SECRET"
)
```

### 5. Build and Run

**Admin Panel:**
- Simply open `admin-panel/index.html` in a web browser

**Android App:**
```bash
cd android-app
./gradlew assembleDebug
```

## Usage

1. Install the Android app on your device
2. Open the admin panel in a browser
3. Enter the device ID (default: "99")
4. Wait for the device to come online
5. Browse and download files

## Architecture

```
Admin Panel ←→ Firebase Realtime Database ←→ Android App
     (WebRTC P2P for actual file transfers)
```

## Project Structure

```
RemoteGalleryViewer/
├── admin-panel/
│   └── index.html          # Web admin interface
├── android-app/
│   ├── app/
│   │   ├── src/main/java/com/arman/secureviewer/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ViewerService.kt    # Background service
│   │   │   └── MediaUtils.kt
│   │   └── google-services.json    # Firebase config (add your own)
│   └── build.gradle
└── README.md
```

## Security Notes

- This project uses placeholder API keys. Replace them with your own credentials.
- For production, implement proper authentication and secure Firebase rules.
- Consider using certificate pinning for enhanced security.

## License

This project is for educational purposes. Use responsibly and comply with privacy laws.

## Contributing

Feel free to submit issues and pull requests!
