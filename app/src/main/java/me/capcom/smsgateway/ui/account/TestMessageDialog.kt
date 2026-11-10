package me.capcom.smsgateway.ui.account

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.DialogTestMessageBinding
import me.capcom.smsgateway.modules.account.TestRecipient

/** «أرسل رسالة تجريبية»: pick a verified test number, edit the text, send, watch it arrive. */
class TestMessageDialog : DialogFragment() {

    private var _binding: DialogTestMessageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TestMessageViewModel by lazy {
        ViewModelProvider(this)[TestMessageViewModel::class.java]
    }
    private var recipients: List<TestRecipient> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogTestMessageBinding.inflate(layoutInflater)
        val tenant = arguments?.getString(ARG_TENANT)

        if (savedInstanceState == null) {
            binding.editTestText.setText(
                TestMessagePolling.defaultText(getString(R.string.test_message_default), tenant)
            )
        }

        val dialog = AlertDialog.Builder(requireActivity())
            .setTitle(R.string.test_message_title)
            .setView(binding.root)
            .setPositiveButton(R.string.test_message_send, null)
            .setNegativeButton(R.string.test_message_close, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { send() }
        }

        viewModel.recipients.observe(this) { list ->
            recipients = list.orEmpty()
            binding.spinnerRecipient.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                recipients.map { label(it) }
            )
            val none = list != null && list.isEmpty()
            binding.textRecipientsNote.isVisible = none
            binding.textRecipientsNote.setText(R.string.test_message_no_recipients)
            refreshButtons()
        }
        viewModel.recipientsError.observe(this) { error ->
            binding.textRecipientsNote.isVisible = error != null
            if (error != null) {
                binding.textRecipientsNote.text = AccountMessages.text(requireContext(), error)
            }
        }
        viewModel.state.observe(this) { render(it) }
        binding.spinnerRecipient.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.reset()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        viewModel.loadRecipients()
        return dialog
    }

    private fun label(recipient: TestRecipient): String {
        val name = recipient.label?.takeIf { it.isNotBlank() }
        val number = recipient.msisdnMasked.orEmpty()
        return if (name != null) "$name · $number" else number
    }

    private fun send() {
        val recipient = recipients.getOrNull(binding.spinnerRecipient.selectedItemPosition) ?: return
        val id = recipient.id ?: return
        viewModel.send(id, binding.editTestText.text?.toString().orEmpty())
    }

    private fun render(state: TestUiState) {
        val b = _binding ?: return
        val result = b.textTestResult
        when (state) {
            TestUiState.Idle -> result.isVisible = false
            TestUiState.OnItsWay -> {
                result.isVisible = true
                result.setText(R.string.test_message_on_its_way)
            }

            is TestUiState.Done -> {
                result.isVisible = true
                result.text = outcomeText(state.outcome)
            }
        }
        refreshButtons()
    }

    private fun outcomeText(outcome: TestOutcome): String = when (outcome) {
        is TestOutcome.Arrived -> getString(R.string.test_message_arrived, outcome.seconds)
        is TestOutcome.Failed -> getString(
            when (outcome.status) {
                "EXPIRED" -> R.string.test_message_expired
                "ACCEPTANCE_UNKNOWN" -> R.string.test_message_unknown
                else -> R.string.test_message_failed
            }
        )

        TestOutcome.StillOnItsWay -> getString(R.string.test_message_still)
        is TestOutcome.Refused -> when (outcome.code) {
            TestMessagePolling.CAP_CODE ->
                getString(R.string.test_message_cap, arguments?.getInt(ARG_DAILY_MAX, 0) ?: 0)

            "CONFLICT" -> getString(R.string.test_message_no_line)
            else -> AccountMessages.text(requireContext(), UiError(outcome.code))
        }
    }

    private fun refreshButtons() {
        val dialog = dialog as? AlertDialog ?: return
        val sending = viewModel.state.value == TestUiState.OnItsWay
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            !sending && recipients.isNotEmpty()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "test_message"
        private const val ARG_TENANT = "tenant"
        private const val ARG_DAILY_MAX = "daily_max"

        fun newInstance(tenant: String?, dailyMax: Int?) = TestMessageDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_TENANT, tenant)
                putInt(ARG_DAILY_MAX, dailyMax ?: 0)
            }
        }
    }
}
