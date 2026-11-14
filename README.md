# FamilyBoard

FamilyBoard is a native Android and iOS app for contacting family. Save a preferred family contact on the device, open the system dialer or a prefilled help message, and optionally add your current location to the message. These actions are available without a FamilyBoard server connection. Calls and messages use the device's available services and require the user's action.

The family board lets members create or join a group via a one-time invitation, post a help request, and acknowledge someone else's request. Acknowledgements identify the family members who explicitly confirmed receipt. Members see new requests when they open and refresh the board. For immediate contact, use the call and text buttons. Background push delivery is outside this version.

## Project layout

- `proto/` contains the shared Protobuf schema. Both apps use binary gRPC over HTTP/2 with TLS.
- `server/` contains the Rust, tonic, and SQLite backend.
- `android/` contains the native Kotlin and Jetpack Compose app.
- `ios/` contains the native Swift and SwiftUI app.

## Run the backend

Install current stable Rust. Supply a PEM certificate chain and private key. For a phone test, use a DNS name that resolves to the backend and a certificate trusted by the phone. The service terminates TLS itself and requires HTTP/2.

```sh
export FAMILYBOARD_BIND=0.0.0.0:8443
export FAMILYBOARD_DB=/path/to/familyboard.sqlite
export FAMILYBOARD_TLS_CERT=/path/to/fullchain.pem
export FAMILYBOARD_TLS_KEY=/path/to/privkey.pem
cargo run --locked --manifest-path server/Cargo.toml
```

Run the backend integration test with `cargo test --locked --manifest-path server/Cargo.toml`. It uses a local TLS listener and a temporary database.

## Build the apps

- **Android:** Open `android/` in Android Studio with SDK 35, Gradle 8.13 and JDK 17 or 21, or run `gradle -p android assembleDebug`. The Android Gradle plugin generates the client from the shared schema. Minimum Android version is 8.0.
- **iOS:** On a Mac with Xcode 26 or later, install Protobuf and XcodeGen with `brew install protobuf xcodegen`. Run `./ios/setup.sh`, then open `ios/FamilyBoard.xcodeproj`. The setup script generates the client from the shared schema and creates the Xcode project. Minimum iOS version is 18.0.

## Try it with two devices

1. Enter the same HTTPS server address on both devices, for example `https://family.example.com:8443`.
2. Create a family on one device and share an invitation. Join using the code on the other device.
3. Post a help request, refresh the second device, and acknowledge it. Refresh the sending device to verify the acknowledgement.
4. On both devices, save a family contact and test the call and SMS composers. Allow and deny location permission to verify that the optional SMS location behaves as expected.

The device credential is scoped to its server and stored in the platform credential store. Keep the backend database private. The location is only added to the SMS draft when requested.
