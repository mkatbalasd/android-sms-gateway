package me.capcom.smsgateway.ui.account

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.FragmentNumbersBinding
import me.capcom.smsgateway.databinding.ItemSimSlotBinding

/** F1-6: «وجدنا شريحة في هذا الهاتف». Draws [NumberRow]s; all decisions live in [NumbersState]. */
class NumbersFragment : Fragment() {

    private var _binding: FragmentNumbersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NumbersViewModel by lazy {
        ViewModelProvider(requireActivity())[NumbersViewModel::class.java]
    }
    private val slotViews = HashMap<Int, ItemSimSlotBinding>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNumbersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonNumbersVerify.setOnClickListener { viewModel.verifyTicked() }
        binding.buttonNumbersContinue.setOnClickListener { (activity as? NumbersActivity)?.next() }
        binding.buttonNumbersSkip.setOnClickListener { (activity as? NumbersActivity)?.next() }
        viewModel.rows.observe(viewLifecycleOwner) { render(it) }
        viewModel.loaded.observe(viewLifecycleOwner) { render(viewModel.rows.value.orEmpty()) }
        viewModel.load()
    }

    private fun render(rows: List<NumberRow>) {
        val b = _binding ?: return
        val loaded = viewModel.loaded.value == true
        b.textNumbersTitle.text = when {
            !loaded -> ""
            rows.isEmpty() -> getString(R.string.numbers_title_none)
            rows.size == 1 -> getString(R.string.numbers_title_one)
            else -> getString(R.string.numbers_title_many, rows.size)
        }
        b.textNumbersEmpty.isVisible = loaded && rows.isEmpty()
        b.textNumbersNote.isVisible = rows.isNotEmpty()

        for (row in rows) {
            val holder = slotViews.getOrPut(row.slot) { createRow(row) }
            bind(holder, row)
        }

        b.buttonNumbersVerify.isEnabled = NumbersState.canVerify(rows)
        b.buttonNumbersVerify.isVisible = rows.isNotEmpty()
        b.buttonNumbersContinue.isVisible = NumbersState.canContinue(rows)
        b.buttonNumbersSkip.isVisible = !NumbersState.canContinue(rows)
        b.buttonNumbersSkip.isEnabled = rows.none { it.busy }
    }

    private fun createRow(row: NumberRow): ItemSimSlotBinding {
        val item = ItemSimSlotBinding.inflate(layoutInflater, binding.layoutNumbersRows, false)
        binding.layoutNumbersRows.addView(item.root)
        val slot = row.slot
        item.checkSlot.setOnCheckedChangeListener { button, checked ->
            // Only a keeper's tap counts; a redraw sets the box without calling back here.
            if (button.isPressed) viewModel.tick(slot, checked)
        }
        item.editSlotNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.type(slot, s?.toString().orEmpty())
            }
        })
        return item
    }

    private fun bind(item: ItemSimSlotBinding, row: NumberRow) {
        val carrier = row.carrier ?: getString(R.string.numbers_carrier_unknown)
        item.checkSlot.text = getString(R.string.numbers_slot_title, row.slot, carrier)
        if (item.checkSlot.isChecked != (row.ticked && !row.verified)) {
            item.checkSlot.isChecked = row.ticked && !row.verified
        }
        item.checkSlot.isEnabled = !row.busy && !row.verified

        item.textSlotHint.text = when {
            row.verified -> getString(R.string.numbers_verified_number, row.verifiedMasked.orEmpty())
            // Only a number the carrier asserted (verified) is shown; never a SIM-reported hint.
            else -> getString(R.string.numbers_number_unknown)
        }

        val showField = !row.verified && row.asksForNumber
        item.layoutSlotNumber.isVisible = showField
        item.editSlotNumber.isEnabled = !row.busy
        if (item.editSlotNumber.text?.toString().orEmpty() != row.typed) {
            item.editSlotNumber.setText(row.typed)
        }

        val status = statusText(row)
        item.textSlotStatus.isVisible = status != null
        item.textSlotStatus.text = status.orEmpty()
    }

    private fun statusText(row: NumberRow): String? = when (row.phase) {
        RowPhase.IDLE -> null
        RowPhase.STARTING, RowPhase.SENDING -> getString(R.string.numbers_status_sending)
        RowPhase.WAITING -> getString(R.string.numbers_status_waiting)
        RowPhase.VERIFIED ->
            if (row.weak) getString(R.string.numbers_status_verified_weak)
            else getString(R.string.numbers_status_verified)

        RowPhase.SEND_FAILED -> getString(R.string.numbers_status_send_failed)
        RowPhase.TIMED_OUT -> getString(R.string.numbers_status_timeout)
        RowPhase.NEEDS_NUMBER -> getString(R.string.numbers_status_needs_number)
        RowPhase.OPEN_PROOF -> getString(R.string.numbers_status_open_proof, row.retryMinutes ?: 10)
        RowPhase.FAILED -> AccountMessages.text(
            requireContext(), UiError(row.errorCode ?: UiError.GENERIC)
        )
    }

    override fun onDestroyView() {
        slotViews.clear()
        _binding = null
        super.onDestroyView()
    }
}
