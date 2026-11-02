package me.capcom.smsgateway.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.DialogFirstStartBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.LinkFlow
import me.capcom.smsgateway.modules.gateway.LinkPayload
import org.koin.android.ext.android.inject

/**
 * Mnaseb SIM first run: the phone links to the tenant's account on the fixed
 * Mnaseb server with a one-time code. The upstream Sign Up and Sign In
 * (username/password) tabs are removed, so neither is reachable.
 */
class FirstStartDialogFragment : DialogFragment() {

    private var _binding: DialogFirstStartBinding? = null

    // This property is only valid between onCreateDialog and
    // onDestroyView.
    private val binding get() = _binding!!

    private val gatewaySettings: GatewaySettings by inject()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFirstStartBinding.inflate(layoutInflater)

        binding.buttonScan.setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.first_run_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        binding.buttonContinue.setOnClickListener {
            actionSignInByCode()
        }

        // A deep link opens this screen with its payload.
        val payload = arguments?.getString(ARG_PAYLOAD)
        if (savedInstanceState == null && payload != null) {
            // After the dialog is shown: a valid payload opens the confirmation.
            binding.root.post { if (isAdded) handlePayload(payload) }
        }

        binding.buttonCancel.setOnClickListener {
            actionCancel()
        }

        return AlertDialog.Builder(requireActivity())
            .apply {
                setView(binding.root)
                setCancelable(false)
            }
            .create()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handlePayload(it) }
    }

    /**
     * QR scan and deep link: payload v1, docs/design/ux/07-delivery.md §3.2.
     * The payload comes from outside the app, so it never registers by itself:
     * LinkFlow returns a confirmation that the keeper must accept first.
     */
    private fun handlePayload(raw: String) {
        val serverUrl = gatewaySettings.serverUrl
        val result = LinkPayload.parse(raw, serverUrl, System.currentTimeMillis() / 1000)
        applyStep(LinkFlow.onPayload(result, serverUrl))
    }

    private fun actionSignInByCode() {
        applyStep(LinkFlow.onManualCode(binding.editCode.text.toString()))
    }

    private fun applyStep(step: LinkFlow.Step) {
        _binding?.editCodeLayout?.error = null
        when (step) {
            is LinkFlow.Step.ShowError -> {
                _binding?.editCodeLayout?.error = getString(
                    when (step.error) {
                        LinkPayload.Error.Invalid -> R.string.link_invalid_payload
                        LinkPayload.Error.UnsupportedVersion -> R.string.link_unsupported_version
                        LinkPayload.Error.ForbiddenServer -> R.string.link_forbidden_server
                        LinkPayload.Error.Expired -> R.string.link_code_expired
                    }
                )
            }

            LinkFlow.Step.CodeRequired -> {
                _binding?.editCodeLayout?.error = getString(R.string.first_run_code_required)
            }

            is LinkFlow.Step.Confirm -> confirm(step)

            is LinkFlow.Step.Register -> {
                setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putString(KEY_RESULT_CODE, Result.SignInByCode.name)
                        putString(KEY_CODE, step.code)
                        putString(KEY_LABEL, step.label)
                    }
                )
                dismiss()
            }
        }
    }

    // "Link this phone to {t}?" with the server host; the label is untrusted
    // and already sanitised and capped by LinkFlow.displayLabel.
    private fun confirm(step: LinkFlow.Step.Confirm) {
        val title = if (step.label == null) {
            getString(R.string.link_confirm_title_no_label)
        } else {
            getString(R.string.link_confirm_title, step.label)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(getString(R.string.link_confirm_server, step.host))
            .setCancelable(true)
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                if (isAdded) applyStep(LinkFlow.onConfirm(step))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun actionCancel() {
        setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(KEY_RESULT_CODE, Result.Canceled.name)
            }
        )
        dismiss()
    }

    enum class Result {
        Canceled,
        SignInByCode,
    }

    companion object {
        const val REQUEST_KEY = "FirstStartDialogFragment"
        const val TAG = "signin"

        private const val KEY_RESULT_CODE = "result_code"
        private const val KEY_CODE = "code"
        private const val KEY_LABEL = "label"
        private const val ARG_PAYLOAD = "payload"

        fun newInstance(payload: String? = null): FirstStartDialogFragment =
            FirstStartDialogFragment().apply {
                if (payload != null) {
                    arguments = Bundle().apply { putString(ARG_PAYLOAD, payload) }
                }
            }

        fun getResult(data: Bundle): Result =
            Result.valueOf(data.getString(KEY_RESULT_CODE) ?: Result.Canceled.name)

        fun getCode(data: Bundle): String = requireNotNull(data.getString(KEY_CODE))

        /** Tenant label for "Linked to {t}"; only a QR payload carries one. */
        fun getLabel(data: Bundle): String? = data.getString(KEY_LABEL)
    }
}
