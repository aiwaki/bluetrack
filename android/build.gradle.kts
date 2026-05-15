plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Kotlin linter / formatter. `./gradlew :app:ktlintCheck` is the
    // gate; `./gradlew :app:ktlintFormat` auto-fixes. CI runs the
    // check task on every PR.
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}
