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
import me.capcom.smsgateway.modules.gateway.LinkPayload
import me.capcom.smsgateway.modules.gateway.LoginCodeSanitizer
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
            // After the dialog is shown, so a valid payload can dismiss it.
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

    /** QR scan and deep link: payload v1, docs/design/ux/07-delivery.md §3.2. */
    private fun handlePayload(raw: String) {
        val result = LinkPayload.parse(
            raw,
            gatewaySettings.serverUrl,
            System.currentTimeMillis() / 1000
        )
        when (result) {
            is LinkPayload.Result.Ok -> {
                setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply {
                        putString(KEY_RESULT_CODE, Result.SignInByCode.name)
                        putString(KEY_CODE, result.code)
                        putString(KEY_LABEL, result.label)
                    }
                )
                dismiss()
            }

            is LinkPayload.Result.Failed -> {
                _binding?.editCodeLayout?.error = getString(
                    when (result.error) {
                        LinkPayload.Error.Invalid -> R.string.link_invalid_payload
                        LinkPayload.Error.UnsupportedVersion -> R.string.link_unsupported_version
                        LinkPayload.Error.ForbiddenServer -> R.string.link_forbidden_server
                        LinkPayload.Error.Expired -> R.string.link_code_expired
                    }
                )
            }
        }
    }

    private fun actionSignInByCode() {
        val code = LoginCodeSanitizer.clean(binding.editCode.text.toString())

        binding.editCodeLayout.error = null

        if (code.isEmpty()) {
            binding.editCodeLayout.error = getString(R.string.first_run_code_required)
            return
        }

        setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(KEY_RESULT_CODE, Result.SignInByCode.name)
                putString(KEY_CODE, code)
            }
        )
        dismiss()
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
