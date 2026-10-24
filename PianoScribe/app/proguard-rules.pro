# TensorFlow Lite uses JNI and reflection on these classes.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# Room generated code is kept by its own consumer rules; keep entity field names for clarity in crash logs.
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime is called through JNI.
-keep class ai.onnxruntime.** { *; }
