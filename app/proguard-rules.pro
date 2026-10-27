# Zatím se neuplatní – isMinifyEnabled = false. Až se zapne, bez těchto
# pravidel by Gson tiše vracel objekty s null poli, protože R8 přejmenuje
# vlastnosti, podle kterých se JSON mapuje.

# Datové modely a DTO
-keep class cz.dias.dpmbwear.data.model.** { *; }
-keep class cz.dias.dpmbwear.data.DeparturesResponse { *; }
-keep class cz.dias.dpmbwear.data.PostNode { *; }
-keep class cz.dias.dpmbwear.data.ApiDeparture { *; }
-keep class cz.dias.dpmbwear.data.StopsResponse { *; }
-keep class cz.dias.dpmbwear.data.ServerStop { *; }
-keep class cz.dias.dpmbwear.data.ServerDeparturesResponse { *; }
-keep class cz.dias.dpmbwear.data.ServerDeparture { *; }

# Gson potřebuje generické typy pro TypeToken
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# Retrofit
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
