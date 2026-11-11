package me.capcom.smsgateway.ui.account

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentHomeV2Binding
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AccountSettings
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.account.AppHome
import me.capcom.smsgateway.modules.account.DeviceReattach
import me.capcom.smsgateway.modules.account.ReattachOutcome
import me.capcom.smsgateway.modules.account.KeepAliveChecklist
import me.capcom.smsgateway.modules.account.IsoTime
import me.capcom.smsgateway.modules.account.PauseController
import me.capcom.smsgateway.modules.account.PauseHold
import me.capcom.smsgateway.modules.account.PauseStore
import me.capcom.smsgateway.modules.account.ServiceSwitch
import me.capcom.smsgateway.modules.account.StaleSweep
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.ping.PingSettings
import me.capcom.smsgateway.ui.dialogs.KeepAliveGuideDialogFragment
import org.koin.android.ext.android.inject
import java.io.IOException

/**
 * The home screen of an app-enrolled phone (F1-8, 01-flows §1.1). Apart from the
 * pause and resume of the phone (V2-L23) nothing here changes the account. It polls `GET /v1/app/home` every minute
 * while it is on screen and stops when it is not.
 */
class HomeV2Fragment : Fragment() {

    private var _binding: FragmentHomeV2Binding? = null
    private val binding get() = _binding!!

    private val client: AppClient by inject()
    private val accountSettings: AccountSettings by inject()
    private val pauseStore: PauseStore by inject()
    private val settingsHelper: SettingsHelper by inject()
    private val gatewaySvc: GatewayService by inject()
    private val orchestratorSvc: OrchestratorService by inject()
    private val staleSweep: StaleSweep by inject()

    private val pauseController by lazy {
        val appContext = requireContext().applicationContext
        PauseController(
            client, pauseStore,
            object : ServiceSwitch {
                // A pause stops only pulling and sending; the full start is the legacy one.
                override fun pause() = orchestratorSvc.pause(appContext)
                override fun start() = orchestratorSvc.start(appContext, false)

                // The start is idempotent and, while paused, brings up reporting only.
                override fun keepReporting() = orchestratorSvc.start(appContext, false)
            },
            staleSweep,
        )
    }
    private var pauseBusy = false

    private val gatewaySettings: GatewaySettings by inject()
    private val pingSettings: PingSettings by inject()
    private val reattach: DeviceReattach by inject()
    private var reattachError: String? = null
    private var reattachBusy = false

    private var home: AppHome? = null
    private var homeFailed = false
    private var homeErrorCode: String? = null

    // A linked phone with no app session signs in again here; the gateway link stays as it is.
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AccountActivity.RESULT_SIGNED_IN) {
            homeErrorCode = null
            startPolling()
        }
        render()
    }
    private var serviceRunning = false
    private var pollJob: Job? = null
    private var handoffBusy = false
    private var lastReported: KeepAliveChecklist? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonHomeTest.setOnClickListener { openTestMessage() }
        binding.buttonHomeDashboard.setOnClickListener { openWorkspace() }
        binding.cardHomeApproval.setOnClickListener { openWorkspace() }
        binding.textHomeTestRecipients.setOnClickListener { openWorkspace() }
        binding.buttonHomeVerify.setOnClickListener {
            startActivity(NumbersActivity.starter(requireContext()))
        }
        binding.cardHomeKeepAlive.setOnClickListener { showKeepAliveGuide() }
        binding.buttonHomeReattach.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                reattachIfPending()
                refresh()
            }
        }
        binding.buttonHomeSignIn.setOnClickListener {
            signInLauncher.launch(AccountActivity.starter(requireContext()))
        }
        binding.buttonHomeStart.setOnClickListener {
            orchestratorSvc.start(requireContext().applicationContext, false)
        }
        binding.buttonHomePause.setOnClickListener {
            if (isPausedNow()) resumeDevice() else confirmPause()
        }

        parentFragmentManager.setFragmentResultListener(
            KeepAliveGuideDialogFragment.REQUEST_KEY, viewLifecycleOwner
        ) { _, _ ->
            render()
            reportKeepAlive()
        }

        gatewaySvc.isActiveLiveData(requireContext()).observe(viewLifecycleOwner) {
            serviceRunning = it
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    // The server's liveness poller is the truth; the screen only asks while it is visible.
    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                // After a sign-in again the new session is tied to this phone first.
                // (A failed attempt waits for the retry button instead of repeating each minute.)
                if (reattachError == null) reattachIfPending()
                refresh()
                reportKeepAlive()
                delay(POLL_MS)
            }
        }
    }

    /** Never unlinks and never enrols: a failure keeps the link and shows a retry. */
    private suspend fun reattachIfPending() {
        if (!accountSettings.linked || !accountSettings.hasSession || !accountSettings.reattachPending) {
            reattachError = null
            return
        }
        if (reattachBusy) return
        reattachBusy = true
        try {
            val outcome = reattach.run()
            reattachError = (outcome as? ReattachOutcome.Failed)?.code
        } finally {
            reattachBusy = false
            render()
        }
    }

    private suspend fun refresh() {
        try {
            val requestedAt = System.currentTimeMillis()
            val fresh = client.home()
            home = fresh
            homeFailed = false
            homeErrorCode = null
            applyPauseFrom(fresh, requestedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            homeFailed = true
            homeErrorCode = e.error.code
        } catch (e: IOException) {
            homeFailed = true
        } catch (e: Exception) {
            homeFailed = true
        }
        render()
    }

    private fun reportKeepAlive() {
        val ctx = context ?: return
        val checklist = KeepAliveReport.checklist(
            KeepAliveGuideDialogFragment.currentState(ctx, settingsHelper)
        )
        if (checklist == lastReported) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (KeepAliveReport.sendIfChanged(client, checklist, lastReported)) {
                lastReported = checklist
            }
        }
    }

    //region Drawing

    private fun localChecks(): LocalChecks {
        val ctx = requireContext()
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        return LocalChecks(
            serviceRunning = serviceRunning,
            smsPermissionGranted = granted(Manifest.permission.SEND_SMS) &&
                granted(Manifest.permission.RECEIVE_SMS),
            simPresent = SubscriptionsHelper.getActiveSimCards(ctx).isNotEmpty(),
            batteryExempt = KeepAliveGuideDialogFragment.currentState(ctx, settingsHelper).batteryExempt,
        )
    }

    private fun render() {
        val b = _binding ?: return
        val ctx = context ?: return
        val data = home

        b.textHomeTenant.text = data?.tenant?.name ?: accountSettings.tenantName.orEmpty()
        val live = if (data != null) HomeStatus.isLive(data)
        else accountSettings.tenantMode.equals("LIVE", ignoreCase = true)
        b.textHomeMode.setText(if (live) R.string.home_mode_live else R.string.home_mode_test)

        renderStatus(b, data)
        renderKeepAlive(b, data)
        renderNumbers(b, data)

        b.textHomeCounters.isVisible = data != null
        if (data != null) {
            val c = HomeStatus.counters(data)
            b.textHomeCounters.text = when {
                c.live -> getString(R.string.home_counters_live, c.balance, c.sentToday)
                c.dailyMax != null -> getString(R.string.home_counters_test, c.sentToday, c.dailyMax)
                else -> getString(R.string.home_counters_test_nolimit, c.sentToday)
            }
        }

        val approval = data?.let { HomeStatus.approvalCard(it.tenant?.mode, it.tenant?.approvalStatus) }
        b.cardHomeApproval.isVisible = approval != null
        if (approval != null) {
            b.textHomeApproval.setText(
                when (approval) {
                    ApprovalCard.START -> R.string.home_approval_start
                    ApprovalCard.REVIEW -> R.string.home_approval_review
                    ApprovalCard.NEEDS_INFO -> R.string.home_approval_needs_info
                    ApprovalCard.REJECTED -> R.string.home_approval_rejected
                }
            )
        }

        val recipients = data?.testRecipients
        b.textHomeTestRecipients.isVisible = recipients != null
        if (recipients != null) {
            b.textHomeTestRecipients.text = ctx.getString(
                R.string.home_test_numbers, recipients.verified ?: 0, recipients.max ?: 0
            )
        }

        b.buttonHomeTest.isEnabled = data != null || !homeFailed
        b.buttonHomeDashboard.isEnabled = !handoffBusy
    }

    private fun renderStatus(b: FragmentHomeV2Binding, data: AppHome?) {
        // The session line is separate from the connection line: a revoked app session says
        // «سجّل الدخول من جديد» and never makes the phone «لا يتصل».
        val signIn = HomeStatus.sessionNeedsSignIn(
            accountSettings.linked, accountSettings.hasSession, homeErrorCode
        )
        // Signed in again but not yet tied to this phone on the platform: a clear message
        // and a retry; the gateway link is untouched either way.
        val needsReattach = !signIn && reattachError != null && HomeStatus.sessionNeedsReattach(
            accountSettings.linked, accountSettings.hasSession, homeErrorCode,
            accountSettings.reattachPending
        )
        b.textHomeSession.isVisible = signIn || needsReattach
        b.textHomeSession.setText(
            when {
                signIn -> R.string.home_session_ended
                reattachError == DeviceReattach.DEVICE_NOT_FOUND -> R.string.home_reattach_not_found
                else -> R.string.home_reattach_failed
            }
        )
        b.buttonHomeSignIn.isVisible = signIn
        b.buttonHomeReattach.isVisible = needsReattach

        // The connection line is the gateway's own last contact, not the app session.
        val status = HomeStatus.phoneStatus(
            localChecks(),
            gatewaySettings.lastContactAtMs,
            System.currentTimeMillis(),
            pingSettings.intervalSeconds,
            paused = isPausedNow(),
        )
        b.textHomeStatus.text = when (status) {
            PhoneStatus.Paused -> getString(R.string.home_status_paused)

            is PhoneStatus.Working ->
                if (status.agoMinutes == null) getString(R.string.home_status_working)
                else getString(R.string.home_status_working_ago, status.agoMinutes)

            is PhoneStatus.Stopped -> getString(
                when (status.reason) {
                    PhoneStatus.Reason.PERMISSION -> R.string.home_status_stopped_permission
                    PhoneStatus.Reason.SERVICE -> R.string.home_status_stopped
                    PhoneStatus.Reason.SIM -> R.string.home_status_stopped_sim
                }
            )

            PhoneStatus.Offline -> getString(R.string.home_status_offline)
        }
        b.buttonHomeStart.isVisible =
            status is PhoneStatus.Stopped && status.reason == PhoneStatus.Reason.SERVICE
        // «إيقاف مؤقت» only while the phone runs; «استئناف» only while it is paused.
        b.buttonHomePause.isVisible =
            status == PhoneStatus.Paused || status is PhoneStatus.Working || status == PhoneStatus.Offline
        b.buttonHomePause.setText(
            if (status == PhoneStatus.Paused) R.string.home_resume else R.string.home_pause
        )
        b.buttonHomePause.isEnabled = !pauseBusy
    }

    private fun renderKeepAlive(b: FragmentHomeV2Binding, data: AppHome?) {
        val ctx = context ?: return
        val state = KeepAliveGuideDialogFragment.currentState(ctx, settingsHelper)
        val visible = HomeStatus.keepAliveCardVisible(data?.keepAlivePending == true, state)
        b.cardHomeKeepAlive.isVisible = visible
        if (visible) {
            val left = HomeStatus.keepAliveStepsLeft(state)
            b.textHomeKeepAlive.text =
                if (left > 0) getString(R.string.home_keep_alive_left, left)
                else getString(R.string.home_keep_alive_waiting)
        }
    }

    private fun renderNumbers(b: FragmentHomeV2Binding, data: AppHome?) {
        val lines = HomeStatus.numberLines(data)
        b.layoutHomeNumbers.removeAllViews()
        for (line in lines) {
            val row = TextView(requireContext())
            row.text = getString(
                if (line.ready) R.string.home_number_ready else R.string.home_number_not_ready,
                line.masked
            )
            b.layoutHomeNumbers.addView(row)
        }
        val empty = data != null && lines.none { it.ready }
        b.textHomeNoNumbers.isVisible = empty
        b.buttonHomeVerify.isVisible = empty
    }

    private fun showMessage(text: String?) {
        val b = _binding ?: return
        b.textHomeMessage.isVisible = text != null
        b.textHomeMessage.text = text.orEmpty()
    }

    //endregion

    //region Actions

    // An unreadable pause state shows as paused (fail closed); the resume repairs it.
    private fun isPausedNow(): Boolean = HomeStatus.isPaused(
        try {
            pauseStore.isPaused
        } catch (e: Exception) {
            true
        },
        home
    )

    /**
     * Keeps the platform's hold limit, and takes over a pause the platform knows and this
     * phone does not (a new sign-in, a data clear, a reinstall): the screen then says
     * «متوقف مؤقتًا» and offers «استئناف».
     */
    private fun applyPauseFrom(data: AppHome, requestedAtMs: Long) {
        data.pauseHoldMaxMin?.takeIf { it > 0 }?.let { pauseStore.holdMaxMin = it }
        if (pauseBusy) return
        pauseController.restore(
            serverPaused = data.paused,
            serverPausedAtMs = IsoTime.parseMillis(data.pausedAt),
            requestedAtMs = requestedAtMs,
        )
    }

    private fun confirmPause() {
        val ctx = context ?: return
        if (pauseBusy) return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.home_pause_confirm_title)
            .setMessage(
                getString(
                    R.string.home_pause_confirm_message,
                    PauseHold.holdMaxMin(pauseStore.holdMaxMin)
                )
            )
            .setPositiveButton(R.string.home_pause_confirm_yes) { _, _ -> pauseDevice() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The platform is told first; only a success stops the services. A failure leaves the phone running. */
    private fun pauseDevice() = runPauseAction(R.string.home_pause_failed) {
        pauseController.pause()
        home = home?.copy(paused = true)
    }

    private fun resumeDevice() = runPauseAction(R.string.home_resume_failed) {
        pauseController.resume()
        home = home?.copy(paused = false, pausedAt = null)
    }

    private fun runPauseAction(failure: Int, action: suspend () -> Unit) {
        if (pauseBusy) return
        pauseBusy = true
        showMessage(null)
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(getString(failure))
            } finally {
                pauseBusy = false
                render()
            }
        }
    }

    private fun showKeepAliveGuide() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(KeepAliveGuideDialogFragment.TAG) != null) return
        KeepAliveGuideDialogFragment.newInstance()
            .show(parentFragmentManager, KeepAliveGuideDialogFragment.TAG)
    }

    private fun openTestMessage() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(TestMessageDialog.TAG) != null) return
        val data = home
        TestMessageDialog.newInstance(
            data?.tenant?.name ?: accountSettings.tenantName,
            data?.sandboxDailyMax
        ).show(parentFragmentManager, TestMessageDialog.TAG)
    }

    private fun openWorkspace() {
        if (handoffBusy) return
        val activity = activity ?: return
        handoffBusy = true
        showMessage(null)
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!HandoffOpener.open(activity, client)) {
                    showMessage(getString(R.string.home_no_browser))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                showMessage(AccountMessages.text(activity, UiError.from(e.error)))
            } catch (e: IOException) {
                showMessage(AccountMessages.text(activity, UiError(UiError.NETWORK)))
            } catch (e: Exception) {
                showMessage(AccountMessages.text(activity, UiError(UiError.GENERIC)))
            } finally {
                handoffBusy = false
                render()
            }
        }
    }

    //endregion

    override fun onDestroyView() {
        pollJob?.cancel()
        pollJob = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val POLL_MS = 60_000L
    }
}
