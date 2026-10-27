# Pleos Connect SDK — 서비스 연결(AIDL)과 직렬화 모델을 그대로 둔다
-keep class ai.pleos.playground.** { *; }
-keep interface ai.pleos.playground.** { *; }
-dontwarn ai.pleos.playground.**

# AAOS Car API (런타임에 플랫폼이 제공)
-dontwarn android.car.**

# SDK가 쓰는 Gson TypeToken·kotlinx.serialization — 제네릭 시그니처를 남겨야 한다
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontnote kotlinx.serialization.**
