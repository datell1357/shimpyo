# Pleos Connect SDK — 서비스 연결(AIDL)과 직렬화 모델을 그대로 둔다
-keep class ai.pleos.playground.** { *; }
-keep interface ai.pleos.playground.** { *; }
-dontwarn ai.pleos.playground.**

# AAOS Car API (런타임에 플랫폼이 제공)
-dontwarn android.car.**

# kotlinx.serialization (NaviHelper 데이터)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
