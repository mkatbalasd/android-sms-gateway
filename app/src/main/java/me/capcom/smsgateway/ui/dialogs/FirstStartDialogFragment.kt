package me.capcom.smsgateway.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.DialogFirstStartBinding
import me.capcom.smsgateway.modules.gateway.LoginCodeSanitizer

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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFirstStartBinding.inflate(layoutInflater)

        binding.buttonContinue.setOnClickListener {
            actionSignInByCode()
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

        fun newInstance(): FirstStartDialogFragment = FirstStartDialogFragment()

        fun getResult(data: Bundle): Result =
            Result.valueOf(data.getString(KEY_RESULT_CODE) ?: Result.Canceled.name)

        fun getCode(data: Bundle): String = requireNotNull(data.getString(KEY_CODE))

        /** Tenant label for "Linked to {t}"; only a QR payload carries one. */
        fun getLabel(data: Bundle): String? = data.getString(KEY_LABEL)
    }
}
