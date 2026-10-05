# MediaPipe Tasks and LiteRT-LM both call back into Java/Kotlin from native code via JNI,
# so their classes must keep their names and members.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.protobuf.** { *; }

-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn org.checkerframework.**
