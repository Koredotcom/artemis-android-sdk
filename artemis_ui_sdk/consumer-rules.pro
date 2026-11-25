# Public SDK entry points are referenced by host applications.
-keep public class com.artemis.uisdk.** { public *; }

# ArtemisContactCenterCallActivity round-trips these models through Gson reflection
# (Intent extras) and sends AgentMediaIceServer field names to the WebView call stack.
# Keep field names and generic signatures so minified host apps do not get renamed
# JSON keys or a LinkedTreeMap ClassCastException for List<AgentMediaIceServer>.
-keepattributes Signature
-keep class com.artemis.uisdk.chat.AgentMediaEndpoint { <fields>; }
-keep class com.artemis.uisdk.chat.AgentMediaSbcDetails { <fields>; }
-keep class com.artemis.uisdk.chat.AgentMediaIceServer { <fields>; }
