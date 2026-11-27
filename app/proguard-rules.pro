# PixelCineRaw R8 Optimization & Keep Rules

# Preserve line numbers and source file names for clear crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep ViewModels instantiated via ViewModelProvider / by viewModels()
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Keep custom Android Views / SurfaceViews used in XML or Compose AndroidView
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelables and Enums
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep PixelCineRaw core camera, GL, audio, and encoder classes intact against accidental member stripping
-keep class com.kunal.pixelcineraw.camera.CinemaViewModel { *; }
-keep class com.kunal.pixelcineraw.ui.AutoFitSurfaceView { *; }
-keep class com.kunal.pixelcineraw.gl.HlgGlRenderer { *; }
-keep class com.kunal.pixelcineraw.camera.** { *; }
-keep class com.kunal.pixelcineraw.video.** { *; }
-keep class com.kunal.pixelcineraw.audio.** { *; }
-keep class com.kunal.pixelcineraw.raw.** { *; }

# Keep AndroidX / Compose runtime internals if referenced dynamically
-dontwarn kotlin.reflect.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
