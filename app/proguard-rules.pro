# The engine (Go, gomobile) calls into these Java classes by name from native code: keep them as they are.
-keep class go.** { *; }
-keep class mobile.** { *; }
-keepclassmembers class * { native <methods>; }
