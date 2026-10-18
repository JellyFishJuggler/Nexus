# ProGuard/R8 rules for the Nexus Android app.
#
# R8 is disabled for v0.1 (`isMinifyEnabled = false`), so this file is
# intentionally empty of keep rules. It exists because the release build type
# references it via `proguardFiles(...)`. If shrinking is enabled later, add
# keep rules here for:
#   - org.bouncycastle (BC provider) classes,
#   - any reflectively-accessed members,
#   - the Compose/BouncyCastle-related metadata.
