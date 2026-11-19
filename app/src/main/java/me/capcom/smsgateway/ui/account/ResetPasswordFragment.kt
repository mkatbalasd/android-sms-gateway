package me.capcom.smsgateway.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentAccountResetBinding
import me.capcom.smsgateway.modules.account.IntlPhone
import me.capcom.smsgateway.modules.account.ResetState
import java.util.Locale

/**
 * «نسيت كلمة المرور» (V2-L33): the number, then the code with a new password. A thin view over
 * [PasswordResetViewModel]. The number is typed in the international field and never prefilled;
 * the code and the passwords are read, handed over and cleared from the fields at once; the
 * screen is not captured (FLAG_SECURE) while it is visible; nothing here is logged.
 */
class ResetPasswordFragment : Fragment() {
    private var _binding: FragmentAccountResetBinding? = null
    private val binding get() = _binding!!

    private var current: ResetState = ResetState.EnterPhone

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountResetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = ViewModelProvider(requireActivity())[PasswordResetViewModel::class.java]
        val accountViewModel = ViewModelProvider(requireActivity())[AccountViewModel::class.java]
        val ctx = requireContext()

        // Only the country is preselected; the number is typed by the customer.
        if (savedInstanceState == null) binding.phoneReset.preselectDefault()

        binding.buttonResetSend.setOnClickListener {
            binding.textResetError.isVisible = false
            binding.phoneReset.setError(null)
            val mobile = (binding.phoneReset.result() as? IntlPhone.Result.Valid)?.e164
            if (mobile == null) {
                binding.phoneReset.setError(getString(R.string.intl_phone_error))
            } else {
                viewModel.requestCode(mobile, uiLocale())
            }
        }
        binding.buttonResetConfirm.setOnClickListener { confirm(viewModel) }
        binding.buttonResetResend.setOnClickListener { viewModel.resend() }
        binding.buttonResetChangeNumber.setOnClickListener {
            clearSecrets()
            viewModel.changeNumber()
        }
        binding.buttonResetBack.setOnClickListener {
            clearSecrets()
            viewModel.begin()
            accountViewModel.back()
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            current = state
            if (state is ResetState.Done) {
                clearSecrets()
                accountViewModel.resetDone()
            } else {
                render(state)
            }
        }
        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.buttonResetSend.isEnabled = !busy
            binding.buttonResetConfirm.isEnabled = !busy
        }
        viewModel.failure.observe(viewLifecycleOwner) { failure ->
            if (failure != null) {
                binding.textResetError.isVisible = true
                binding.textResetError.text = ResetMessages.text(ctx, failure)
                viewModel.failureShown()
            }
        }

        // The resend button counts down to the moment counted from the 202 (the server sends no time).
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val state = current as? ResetState.EnterCode
                val seconds = if (state == null) 0 else {
                    ((state.resendAtMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
                }
                val busy = viewModel.busy.value == true
                binding.buttonResetResend.isEnabled = seconds == 0 && !busy
                binding.buttonResetResend.text = if (seconds > 0) {
                    getString(R.string.account_reset_resend_in, seconds)
                } else {
                    getString(R.string.account_reset_resend)
                }
                delay(1000)
            }
        }
    }

    private fun render(state: ResetState) {
        val code = state is ResetState.EnterCode
        binding.layoutResetPhone.isVisible = !code
        binding.layoutResetCode.isVisible = code
        binding.textResetTitle.setText(
            if (code) R.string.account_reset_code_title else R.string.account_reset_title
        )
    }

    private fun confirm(viewModel: PasswordResetViewModel) {
        binding.textResetError.isVisible = false
        val code = binding.editResetCode.text?.toString().orEmpty()
        val password = binding.editResetNewPassword.text?.toString().orEmpty()
        val again = binding.editResetConfirmPassword.text?.toString().orEmpty()
        // Whatever the answer is, nothing sensitive stays in the fields.
        clearSecrets()
        viewModel.submit(code, password, again)
    }

    private fun clearSecrets() {
        val b = _binding ?: return
        b.editResetCode.setText("")
        b.editResetNewPassword.setText("")
        b.editResetConfirmPassword.setText("")
    }

    override fun onResume() {
        super.onResume()
        // The code and the new password are never captured in screenshots or the recents list.
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun onPause() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onDestroyView() {
        clearSecrets()
        super.onDestroyView()
        _binding = null
    }

    private fun uiLocale(): String = if (Locale.getDefault().language == "ar") "ar" else "en"
}
