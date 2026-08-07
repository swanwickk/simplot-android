# Add project specific ProGuard rules here.
# Keep Gson model classes (they are plain data holders, no reflection-based serialization is used beyond Gson).
-keepattributes Signature
-keep class com.simplot.android.data.model.** { *; }
