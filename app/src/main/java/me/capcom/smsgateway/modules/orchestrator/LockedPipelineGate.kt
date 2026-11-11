package me.capcom.smsgateway.modules.orchestrator

/**
 * CARD-21: the lifecycle of the locked (before the first unlock) pipeline inside one process.
 * Pure, so the decisions are unit-tested on the JVM.
 *
 * - [shouldStartLocked]: whether an entry that runs while the user is locked (the boot
 *   receiver, or the process start of any component) starts the pipeline;
 * - [tryStart]: the pipeline starts once per process, however many entries ask;
 * - [requestFullStart] / [beginHandover]: the full start (WorkManager and everything) never
 *   runs next to the locked pipeline. While it exists, the full start belongs to the unlock
 *   handover, which lets the locked loops finish first and then starts everything.
 */
class LockedPipelineGate {
    enum class Phase { IDLE, LOCKED, HANDOVER }

    enum class FullStart {
        /** No locked pipeline: start everything now. */
        RUN_NOW,

        /** The locked pipeline exists and has not handed over yet: the caller triggers the handover. */
        START_HANDOVER,

        /** The handover is already draining; it ends with the full start. */
        WAIT_FOR_HANDOVER,
    }

    private var phase = Phase.IDLE

    /** @return `true` for the one caller that must start the locked pipeline. */
    @Synchronized
    fun tryStart(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.LOCKED
        return true
    }

    /** @return `true` for the one caller that must run the handover. */
    @Synchronized
    fun beginHandover(): Boolean {
        if (phase != Phase.LOCKED) return false
        phase = Phase.HANDOVER
        return true
    }

    /** The locked pipeline is gone (the handover is done, or the start failed). */
    @Synchronized
    fun finish() {
        phase = Phase.IDLE
    }

    @Synchronized
    fun requestFullStart(): FullStart = when (phase) {
        Phase.IDLE -> FullStart.RUN_NOW
        Phase.LOCKED -> {
            phase = Phase.HANDOVER
            FullStart.START_HANDOVER
        }

        Phase.HANDOVER -> FullStart.WAIT_FOR_HANDOVER
    }

    @Synchronized
    fun phase(): Phase = phase

    companion object {
        /** Direct Boot exists from API 24. */
        const val FIRST_DIRECT_BOOT_API = 24

        /**
         * @param migrated the device-protected storage holds the migrated data (flag set);
         * before that it is empty or partial and nothing may run on it.
         */
        fun shouldStartLocked(
            unlocked: Boolean,
            enabled: Boolean,
            linked: Boolean,
            migrated: Boolean,
            sdkInt: Int,
        ): Boolean = !unlocked
                && sdkInt >= FIRST_DIRECT_BOOT_API
                && migrated
                && enabled
                && linked
    }
}
