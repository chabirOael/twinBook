package io.github.chabiroael.twinbook

/** Marks probes that only tools/ scripts run (excluded from Gradle connected runs). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ManualProbe
