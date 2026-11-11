package me.capcom.smsgateway.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.text.BidiFormatter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentAccountCodeBinding
import me.capcom.smsgateway.databinding.FragmentAccountCreateBinding
import me.capcom.smsgateway.databinding.FragmentAccountLinkingBinding
import me.capcom.smsgateway.databinding.FragmentAccountPermissionsBinding
import me.capcom.smsgateway.databinding.FragmentAccountSigninBinding
import me.capcom.smsgateway.databinding.FragmentAccountWelcomeBinding
import me.capcom.smsgateway.modules.account.EnrolmentState
import me.capcom.smsgateway.modules.account.IsoTime
import me.capcom.smsgateway.modules.account.IntlPhone
import me.capcom.smsgateway.modules.account.MobileInput
import me.capcom.smsgateway.modules.account.OtpDigits
import me.capcom.smsgateway.modules.account.OtpSmsReader
import me.capcom.smsgateway.modules.account.PendingVerification
import java.util.Locale

/*
 * Mnaseb SIM v0.3.0 first-run screens. Each one is a thin view over
 * AccountViewModel: it validates the form, hands the values over and shows the
 * state. No screen shows or logs a code, a key or a token, and no field of any
 * of them keeps a password after submit.
 */

private fun Fragment.accountViewModel(): AccountViewModel =
    ViewModelProvider(requireActivity())[AccountViewModel::class.java]

private fun uiLocale(): String = if (Locale.getDefault().language == "ar") "ar" else "en"

/** The calling code of the SIM's country (967 for Yemen), or null when the SIM does not say. */
private fun simCountryCode(context: Context): Int? {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        ?: return null
    val iso = telephony.simCountryIso?.uppercase(Locale.ROOT)
    if (iso.isNullOrEmpty()) return null
    val code = PhoneNumberUtil.getInstance().getCountryCodeForRegion(iso)
    return if (code > 0) code else null
}

//region Welcome

class WelcomeFragment : Fragment() {
    private var _binding: FragmentAccountWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()
        val mainUser = AccountPermissions.isMainUser(requireContext())
        binding.layoutWelcomeButtons.isVisible = mainUser
        binding.layoutSecondaryUser.isVisible = !mainUser

        binding.buttonCreateAccount.setOnClickListener { viewModel.chooseCreate() }
        binding.buttonSignIn.setOnClickListener { viewModel.chooseSignIn() }
        binding.buttonHaveLinkCode.setOnClickListener {
            (activity as? AccountActivity)?.useLinkCode()
        }
        binding.buttonLanguage.setOnClickListener {
            (activity as? AccountActivity)?.toggleLanguage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

//endregion

//region Permissions

class PermissionsFragment : Fragment() {
    private var _binding: FragmentAccountPermissionsBinding? = null
    private val binding get() = _binding!!

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val ctx = context ?: return@registerForActivityResult
        if (AccountPermissions.missing(ctx).isEmpty()) {
            accountViewModel().permissionsDone()
        } else {
            // A prompt that never appeared, or a refusal: explain, never block.
            _binding?.layoutDenied?.isVisible = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()

        binding.buttonAllow.setOnClickListener {
            val missing = AccountPermissions.missing(requireContext())
            if (missing.isEmpty()) {
                viewModel.permissionsDone()
            } else {
                request.launch(missing.toTypedArray())
            }
        }
        binding.buttonOpenSettings.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )
            startActivity(intent)
        }
        binding.buttonContinueAnyway.setOnClickListener { viewModel.permissionsDone() }
    }

    override fun onResume() {
        super.onResume()
        // Back from the app settings with everything allowed.
        if (_binding?.layoutDenied?.isVisible == true &&
            AccountPermissions.missing(requireContext()).isEmpty()
        ) {
            accountViewModel().permissionsDone()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

//endregion

//region Create account

class CreateAccountFragment : Fragment() {
    private var _binding: FragmentAccountCreateBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()
        val ctx = requireContext()

        binding.textTerms.movementMethod = LinkMovementMethod.getInstance()
        binding.textTerms.text = getText(R.string.account_terms_checkbox)

        // Only the country is preselected (the SIM, else the network, else the locale); the
        // number is never prefilled (not from the SIM, never an IMSI): the customer types it.
        // After a rotation the field restores its own country and number.
        if (savedInstanceState == null) binding.phoneMobile.preselectDefault()

        binding.buttonCreateSubmit.setOnClickListener { submit(viewModel) }
        binding.buttonCreateBack.setOnClickListener { viewModel.back() }

        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.buttonCreateSubmit.isEnabled = !busy
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.textCreateError.isVisible = true
                binding.textCreateError.text = AccountMessages.text(ctx, error)
                viewModel.errorShown()
            }
        }
    }

    private fun submit(viewModel: AccountViewModel) {
        binding.layoutBusinessName.error = null
        binding.phoneMobile.setError(null)
        binding.layoutPassword.error = null
        binding.textTermsError.isVisible = false
        binding.textCreateError.isVisible = false

        val name = binding.editBusinessName.text?.toString().orEmpty().trim()
        val mobile = (binding.phoneMobile.result() as? IntlPhone.Result.Valid)?.e164
        val password = binding.editPassword.text?.toString().orEmpty()

        var valid = true
        if (name.length !in 3..100) {
            binding.layoutBusinessName.error = getString(R.string.account_error_name)
            valid = false
        }
        if (mobile == null) {
            binding.phoneMobile.setError(getString(R.string.intl_phone_error))
            valid = false
        }
        if (password.length < MIN_PASSWORD) {
            binding.layoutPassword.error = getString(R.string.account_error_password)
            valid = false
        }
        if (!binding.checkTerms.isChecked) {
            binding.textTermsError.isVisible = true
            valid = false
        }
        if (!valid || mobile == null) return

        viewModel.submitSignup(name, mobile, password, uiLocale())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val MIN_PASSWORD = 10
    }
}

//endregion

//region Sign in

class SignInFragment : Fragment() {
    private var _binding: FragmentAccountSigninBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountSigninBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()
        val ctx = requireContext()

        binding.buttonSignInSubmit.setOnClickListener { submit(viewModel) }
        binding.buttonSignInBack.setOnClickListener {
            // A linked phone signing in again has no welcome screen: back closes the screen.
            if (!viewModel.back()) requireActivity().finish()
        }

        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.buttonSignInSubmit.isEnabled = !busy
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.textSignInError.isVisible = true
                binding.textSignInError.text = AccountMessages.text(ctx, error)
                viewModel.errorShown()
            }
        }
        viewModel.info.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                binding.textSignInError.isVisible = true
                binding.textSignInError.text = getString(info)
                viewModel.infoShown()
            }
        }
    }

    private fun submit(viewModel: AccountViewModel) {
        binding.layoutContact.error = null
        binding.layoutSignInPassword.error = null
        binding.textSignInError.isVisible = false

        val typed = binding.editContact.text?.toString().orEmpty().trim()
        val password = binding.editSignInPassword.text?.toString().orEmpty()

        var contact: String? = null
        if (typed.isEmpty()) {
            binding.layoutContact.error = getString(R.string.account_error_contact)
        } else if (MobileInput.looksLikePhone(typed)) {
            contact = MobileInput.normalize(typed, simCountryCode(requireContext()))
            if (contact == null) binding.layoutContact.error = getString(R.string.account_error_mobile)
        } else {
            contact = typed
        }
        if (password.isEmpty()) {
            binding.layoutSignInPassword.error = getString(R.string.account_error_signin_password)
        }
        if (contact == null || password.isEmpty()) return

        viewModel.submitSignIn(contact, password, uiLocale())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

//endregion

//region Code

class CodeFragment : Fragment() {
    private var _binding: FragmentAccountCodeBinding? = null
    private val binding get() = _binding!!

    private var pending: PendingVerification? = null
    private var reader: OtpSmsReader? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()
        val ctx = requireContext()

        binding.buttonCodeConfirm.setOnClickListener { confirm(viewModel) }
        binding.buttonCodeResend.setOnClickListener { viewModel.resend() }
        binding.buttonCodeBack.setOnClickListener { viewModel.startOver() }

        viewModel.pending.observe(viewLifecycleOwner) { value ->
            pending = value
            showPending(ctx, value)
            if (isResumed) armReader()
        }
        viewModel.busy.observe(viewLifecycleOwner) { busy ->
            binding.buttonCodeConfirm.isEnabled = !busy
        }
        viewModel.autofilled.observe(viewLifecycleOwner) { auto ->
            binding.textCodeAuto.isVisible = auto
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            // A dead code moves to the form again; that screen shows the sentence.
            if (error != null && error.code != "OTP_EXPIRED") {
                binding.textCodeError.isVisible = true
                binding.textCodeError.text = AccountMessages.text(ctx, error)
                viewModel.errorShown()
            }
        }

        // The resend button counts down to the server's `resend_after`; the server decides.
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val seconds = IsoTime.secondsUntil(
                    pending?.resendAfter, System.currentTimeMillis(), MAX_COUNTDOWN_S
                )
                val busy = viewModel.busy.value == true
                binding.buttonCodeResend.isEnabled = seconds == 0 && !busy
                binding.buttonCodeResend.text = if (seconds > 0) {
                    getString(R.string.account_code_resend_in, seconds)
                } else {
                    getString(R.string.account_code_resend)
                }
                delay(1000)
            }
        }
    }

    private fun showPending(ctx: Context, value: PendingVerification?) {
        val masked = value?.sentToMasked
        if (value == null || masked.isNullOrEmpty()) {
            binding.textCodeSentTo.text = ""
            return
        }
        val wrapped = BidiFormatter.getInstance().unicodeWrap(masked)
        binding.textCodeSentTo.text = if (value.kind == PendingVerification.KIND_LOGIN) {
            ctx.getString(R.string.account_code_sent_login, wrapped)
        } else {
            ctx.getString(R.string.account_code_sent_to, wrapped)
        }
    }

    private fun confirm(viewModel: AccountViewModel) {
        binding.layoutCode.error = null
        binding.textCodeError.isVisible = false
        val code = OtpDigits.normalize(binding.editCode.text?.toString().orEmpty())
        if (code.length != CODE_LENGTH) {
            binding.layoutCode.error = getString(R.string.account_error_code_length)
            return
        }
        viewModel.submitCode(code)
    }

    // The reader lives only while this screen is visible and RECEIVE_SMS is granted.
    private fun armReader() {
        disarmReader()
        val ctx = context ?: return
        val sender = pending?.sender
        if (sender.isNullOrEmpty() || !AccountPermissions.canReadSms(ctx)) return
        val fresh = OtpSmsReader(sender) { code ->
            val currentBinding = _binding ?: return@OtpSmsReader
            currentBinding.editCode.setText(code)
            accountViewModel().submitCode(code, fromSms = true)
        }
        fresh.register(ctx)
        reader = fresh
    }

    private fun disarmReader() {
        val ctx = context
        val current = reader
        reader = null
        if (current != null && ctx != null) current.unregister(ctx)
    }

    override fun onResume() {
        super.onResume()
        armReader()
    }

    override fun onPause() {
        disarmReader()
        super.onPause()
    }

    override fun onDestroyView() {
        disarmReader()
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val MAX_COUNTDOWN_S = 120
    }
}

//endregion

//region Linking

class LinkingFragment : Fragment() {
    private var _binding: FragmentAccountLinkingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountLinkingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val viewModel = accountViewModel()

        binding.buttonLinkingRetry.setOnClickListener { viewModel.retryEnrolment() }
        binding.buttonLinkingContinue.setOnClickListener {
            (activity as? AccountActivity)?.finishLinked()
        }

        viewModel.enrolment.observe(viewLifecycleOwner) { state -> render(state) }
    }

    private fun render(state: EnrolmentState) {
        val tick = getString(R.string.account_tick_done)
        val wait = getString(R.string.account_tick_wait)
        val todo = getString(R.string.account_tick_todo)
        val account = getString(R.string.account_linking_step_account)
        val phone = getString(R.string.account_linking_step_phone)
        val confirm = getString(R.string.account_linking_step_confirm)

        binding.textStepAccount.text = "$tick $account"
        when (state) {
            EnrolmentState.Idle, EnrolmentState.RetryMint, EnrolmentState.Registering -> {
                binding.textStepPhone.text = "$wait $phone"
                binding.textStepConfirm.text = "$todo $confirm"
            }

            EnrolmentState.Completing -> {
                binding.textStepPhone.text = "$tick $phone"
                binding.textStepConfirm.text = "$wait $confirm"
            }

            EnrolmentState.Linked -> {
                binding.textStepPhone.text = "$tick $phone"
                binding.textStepConfirm.text = "$tick $confirm"
            }

            is EnrolmentState.Failed -> {
                binding.textStepPhone.text = "$todo $phone"
                binding.textStepConfirm.text = "$todo $confirm"
            }
        }

        val linked = state is EnrolmentState.Linked
        val failed = state is EnrolmentState.Failed
        binding.progressLinking.isVisible = !linked && !failed
        binding.buttonLinkingContinue.isVisible = linked
        binding.buttonLinkingRetry.isVisible = failed
        binding.textLinkingError.isVisible = failed
        binding.textLinkingTitle.setText(
            if (linked) R.string.account_linking_done else R.string.account_linking_title
        )
        if (state is EnrolmentState.Failed) {
            binding.textLinkingError.text =
                AccountMessages.text(requireContext(), UiError(state.reason))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

//endregion
