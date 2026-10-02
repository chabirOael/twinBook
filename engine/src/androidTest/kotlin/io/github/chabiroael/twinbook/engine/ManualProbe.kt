package io.github.chabiroael.twinbook.engine

/**
 * Marks test classes that only tools/ scripts run (with `am instrument` and their own
 * arguments). Gradle's connected tests exclude them through the `notAnnotation` runner
 * argument in engine/build.gradle.kts.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ManualProbe
