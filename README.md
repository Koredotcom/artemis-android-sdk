# Artemis Socket SDK

The Artemis Socket SDK is a headless Android client for opening an Artemis
chat session over WebSocket. It handles API-key initialization, WebSocket ticket
creation, session callbacks, chat messages, action submissions, feedback
submissions, and optional reconnection. It does not provide chat UI.

## Requirements

- Android API 24 or later
- Java 17

## Add the dependency

### From the sample app in this repository

The sample app already uses the local Gradle module:

```groovy
dependencies {
    implementation project(':artemis_socket_sdk')
}
```

### From another Android project

Add JitPack to the repositories in `settings.gradle` (or
`settings.gradle.kts`):

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

After a GitHub release is created from this branch, add the SDK dependency to
your app module. The artifact ID is `artemis-socket-sdk`; replace `v1.0.0` with
the tag of the release you want to use:

```groovy
dependencies {
    implementation 'com.github.Koredotcom.artemis-android-sdk:artemis-socket-sdk:v1.0.0'
}
```

For the current unreleased branch, use
`artemis_socket_sdk-SNAPSHOT` in place of the release tag.

## Configure and connect

Add the Internet permission to the host app's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Create a configuration with the runtime endpoint, project ID, API key, and at
least one of `channelId` or `channelName`. Keep environment-specific API keys
out of source control.

```java
import java.util.HashMap;
import java.util.Map;

import artemis.socket.ArtemisFeedbackCallback;
import artemis.socket.ArtemisSocketClient;
import artemis.socket.ArtemisSocketConfiguration;
import artemis.socket.ArtemisSocketListener;

String apiKey = "<your-api-key>"; // Supply this from the host app's secure configuration.
ArtemisSocketConfiguration configuration = ArtemisSocketConfiguration.builder()
        .endpoint("https://runtime.example.com")
        .projectId("your-project-id")
        .apiKey(apiKey)
        .channelId("your-channel-id")
        // Or use .channelName("your-channel-name") instead.
        .reconnection(true, 5, 1000L, 30000L)
        .build();

ArtemisSocketClient client = new ArtemisSocketClient(configuration,
        new ArtemisSocketListener() {
            @Override public void onLog(String message) { /* Optional diagnostics. */ }
            @Override public void onConnecting() { /* Show connecting state. */ }
            @Override public void onConnected(String sessionId) { /* Session started. */ }
            @Override public void onDisconnected(String reason) { /* Session closed. */ }
            @Override public void onMessage(String payload) { /* Raw JSON event. */ }
            @Override public void onError(Throwable error) { /* Handle connection error. */ }
        });

client.connect();
```

The endpoint may use `https://`, `http://`, `wss://`, or `ws://`; the SDK maps
between the HTTP and WebSocket schemes for the API and socket requests. The
default reconnection policy is enabled, with five attempts, a 1 second initial
delay, and a 30 second maximum delay. Override it with
`reconnection(enabled, maxAttempts, baseDelayMs, maxDelayMs)`.

Listener callbacks run on the Android main thread. `onMessage` receives the raw
JSON payload for each socket event. `onConnected` is called when the runtime
starts the session. Use `isConnected()` and `getSessionId()` to inspect the
current connection.

## Send messages and actions

```java
client.sendMessage("Hello");

Map<String, String> formData = new HashMap<>();
formData.put("plan", "basic");
client.submitAction("select-plan", "basic", formData, "render-1");
```

`sendMessage` requires nonblank text. `submitAction` sends an `action_submit`
frame; it does not provide a server acknowledgement. Both methods throw if the
client is disconnected or the socket rejects the send. Action `value`, form
data, and render ID are optional.

## Submit feedback

Feedback is correlated with a message ID and optional action render ID. The
default acknowledgement timeout is 10 seconds; an overload accepts a custom
positive timeout in milliseconds.

```java
client.submitFeedback(
        "assistant-message-id",
        "star",
        5,
        null,       // Optional feedback text.
        null,       // Optional action render ID.
        new ArtemisFeedbackCallback() {
            @Override public void onSuccess(String feedbackId) {
                // The runtime accepted the rating.
            }

            @Override public void onFailure(String code, String message) {
                // The runtime rejected it, the socket disconnected, or it timed out.
            }
        });
```

Supported ratings are `thumbs` with values `0` or `1`, and `star` with values
from `1` through `10`. Feedback callbacks also run on the Android main thread.
Only one feedback request for a message/render ID can be pending at a time.

## Close the client

Call `shutdown()` when the host is finished with the client. It disconnects the
socket, fails any pending feedback callbacks, and stops the client's network
resources:

```java
client.shutdown();
```

## Run the sample app

Open the repository in Android Studio and run the `app` configuration, or
install it from the repository root:

```shell
bash ./gradlew :app:installDebug
```

The sample reads placeholder values from
`app/src/main/assets/artemis_example_config.properties`. For local testing,
create `app/src/main/assets/artemis_example_config.local.properties` with your
endpoint, project ID, channel ID or name, and API key. That local file is
ignored by Git.

## Build

Build the library and run its tests from the repository root:

```shell
bash ./gradlew :artemis_socket_sdk:assembleRelease
```

The Maven publication is configured as `com.artemis:artemis-socket-sdk:1.0.0`.
The JitPack release dependency above uses JitPack's multi-module coordinates
and the GitHub release tag.
