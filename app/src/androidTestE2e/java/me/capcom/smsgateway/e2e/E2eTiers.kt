package me.capcom.smsgateway.e2e

/**
 * Tiers of the emulator e2e suite, selected in .github/workflows/emulator-e2e.yml with
 * `am instrument -e annotation <fqcn>`. A flaky class is quarantined by adding its FQCN to
 * e2e/quarantine.txt (honoured with `-e notClass`), never by deleting or weakening it.
 */

/** Draft pushes: install, the sign-up screen, the stub reachable. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class E2eSmoke

/** Ready PRs and pushes to mnaseb-sim: the whole flow against the stub. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class E2eFull

/** The Direct Boot job (API 30+): the host script drives the reboot between the steps. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class E2eDirectBoot
