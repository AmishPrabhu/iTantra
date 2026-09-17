# Keep JNI native bridges
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep Oboe
-keep class com.google.oboe.** { *; }
