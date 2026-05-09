# Project-specific ProGuard rules.
#
# The Android Gradle Plugin includes the default optimized rule set; keep this
# file for release builds and add app-specific rules here when reflection or
# serialization libraries require them.

# ---------------------------------------------------------------------------
# BouncyCastle primitives used by the BLE feedback handshake.
#
# `FeedbackSession` calls `org.bouncycastle.math.ec.rfc7748.X25519` directly
# for ephemeral keypair derivation and shared-secret computation; the
# handshake parser calls `org.bouncycastle.math.ec.rfc8032.Ed25519` for
# host-identity signature verification. Both classes expose only static
# methods, so R8 might not strip them — but the inner helpers can be
# reached reflectively at provider initialisation. Keep the entire
# `org.bouncycastle.math.ec.rfc{7748,8032}` package plus the agreement /
# signer helpers explicitly so a release build does not silently boot a
# partial BC and crash on the first handshake.
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-keep class org.bouncycastle.crypto.agreement.X25519Agreement { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.params.X25519** { *; }
-keep class org.bouncycastle.crypto.params.Ed25519** { *; }

# Suppress warnings about unused BC subsystems we deliberately do not pull
# in (PGP, S/MIME, PKCS#12, etc.).
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# Bluetrack BLE protocol entry points reached from the GATT server callback
# stack. These are app classes, but the rule is explicit so a future
# refactor that gates them behind reflection (a feature-flag dispatcher,
# a plugin loader, etc.) does not silently break the handshake under R8.
-keep class dev.xd.bluetrack.ble.FeedbackSession { *; }
-keep class dev.xd.bluetrack.ble.PayloadDecryptor { *; }
-keep class dev.xd.bluetrack.ble.PayloadDecryptor$HandshakeOutcome { *; }
-keep class dev.xd.bluetrack.ble.FeedbackHandshakePayload { *; }
-keep class dev.xd.bluetrack.ble.FeedbackHandshakePayload$Companion { *; }
-keep class dev.xd.bluetrack.ble.TrustedHostStore { *; }
-keep interface dev.xd.bluetrack.ble.TrustedHostPolicy { *; }
-keep class dev.xd.bluetrack.ble.InMemoryTrustedHostPolicy { *; }
