# WebRTC ProGuard Rules
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep Firebase and other essentials
-keep class com.google.firebase.** { *; }
-keep class com.cloudinary.** { *; }
-keep class com.arman.secureviewer.** { *; }
