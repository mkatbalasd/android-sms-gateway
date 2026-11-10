package me.capcom.smsgateway.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import me.capcom.smsgateway.R
import me.capcom.smsgateway.databinding.DialogKeepAliveGuideBinding
import me.capcom.smsgateway.helpers.KeepAliveGuideState
import me.capcom.smsgateway.helpers.KeepAliveGuideState.Row
import me.capcom.smsgateway.helpers.OemAutostart
import me.capcom.smsgateway.helpers.SettingsHelper
import org.koin.android.ext.android.inject

/**
 * Mnaseb SIM: three-row checklist that keeps the app alive on the phone
 * (battery exemption, start on boot, the maker's autostart screen).
 * It never blocks sending and can be closed at any time.
 */
class KeepAliveGuideDialogFragment : DialogFragment() {

    private var _binding: DialogKeepAliveGuideBinding? = null
    private val binding get() = _binding!!

    private val settingsHelper: SettingsHelper by inject()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogKeepAliveGuideBinding.inflate(layoutInflater)
        val ctx = requireContext()

        markShown(ctx)

        binding.buttonBattery.setOnClickListener { requestBatteryExemption() }

        binding.switchBoot.isChecked = settingsHelper.autostart
        binding.switchBoot.setOnCheckedChangeListener { _, isChecked ->
            settingsHelper.autostart = isChecked
            refresh()
        }

        val maker = OemAutostart.makerOf(Build.MANUFACTURER)
        binding.textOemTitle.text = getString(R.string.keep_alive_row_oem, makerName(maker))
        binding.textOemSteps.text = getString(oemStepsRes(maker))

        binding.buttonOem.setOnClickListener {
            val opened = OemAutostart.open(requireContext())
            binding.textOemResult.text = getString(
                when (opened) {
                    OemAutostart.Opened.OEM_SCREEN -> R.string.keep_alive_opened_oem
                    OemAutostart.Opened.APP_INFO -> R.string.keep_alive_opened_app_info
                    OemAutostart.Opened.NONE -> R.string.keep_alive_opened_none
                }
            )
        }

        binding.checkOemDone.isChecked = isOemConfirmed(ctx)
        binding.checkOemDone.setOnCheckedChangeListener { _, isChecked ->
            prefs(requireContext()).edit { putBoolean(PREF_OEM_CONFIRMED, isChecked) }
            refresh()
        }

        binding.buttonClose.setOnClickListener { dismiss() }

        refresh()

        return AlertDialog.Builder(requireActivity())
            .apply { setView(binding.root) }
            .create()
    }

    override fun onResume() {
        super.onResume()
        // Back from a system screen: re-read the live status.
        if (_binding != null) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // Mnaseb SIM v0.3.0: the screen that opened the guide learns when it is closed,
    // so it can report the checklist and move on.
    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Not attached (the screen is going away): nobody is waiting for the result.
        runCatching { parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle.EMPTY) }
    }

    private fun refresh() {
        val b = _binding ?: return
        val ctx = context ?: return
        val state = currentState(ctx, settingsHelper)
        b.textBatteryStatus.text = statusText(state.battery)
        b.textBootStatus.text = statusText(state.boot)
        b.textAllSet.text = if (state.allSet) getString(R.string.keep_alive_all_set) else ""
        if (b.switchBoot.isChecked != settingsHelper.autostart) {
            b.switchBoot.isChecked = settingsHelper.autostart
        }
    }

    private fun statusText(row: Row): String = getString(
        when (row) {
            Row.ON -> R.string.keep_alive_status_on
            Row.OFF -> R.string.keep_alive_status_off
            Row.UNKNOWN -> R.string.keep_alive_status_unknown
        }
    )

    private fun requestBatteryExemption() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(ctx, R.string.keep_alive_status_unknown, Toast.LENGTH_SHORT).show()
            return
        }
        if (isIgnoringBatteryOptimizations(ctx)) {
            Toast.makeText(ctx, R.string.keep_alive_status_on, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.keep_alive_opened_none, Toast.LENGTH_SHORT).show()
        }
    }

    private fun makerName(maker: OemAutostart.Maker): String = when (maker) {
        OemAutostart.Maker.HUAWEI -> getString(R.string.keep_alive_maker_huawei)
        OemAutostart.Maker.XIAOMI -> getString(R.string.keep_alive_maker_xiaomi)
        OemAutostart.Maker.SAMSUNG -> getString(R.string.keep_alive_maker_samsung)
        OemAutostart.Maker.OPPO -> getString(R.string.keep_alive_maker_oppo)
        OemAutostart.Maker.VIVO -> getString(R.string.keep_alive_maker_vivo)
        OemAutostart.Maker.ONEPLUS -> getString(R.string.keep_alive_maker_oneplus)
        OemAutostart.Maker.OTHER -> getString(R.string.keep_alive_maker_other)
    }

    private fun oemStepsRes(maker: OemAutostart.Maker): Int = when (maker) {
        OemAutostart.Maker.HUAWEI -> R.string.keep_alive_steps_huawei
        OemAutostart.Maker.XIAOMI -> R.string.keep_alive_steps_xiaomi
        OemAutostart.Maker.SAMSUNG -> R.string.keep_alive_steps_samsung
        else -> R.string.keep_alive_steps_generic
    }

    companion object {
        const val TAG = "keep_alive_guide"

        /** Fragment result key, sent to the parent fragment manager when the guide closes. */
        const val REQUEST_KEY = "keep_alive_guide_closed"

        private const val PREF_SHOWN = "keep_alive_guide_shown"
        private const val PREF_OEM_CONFIRMED = "keep_alive_guide_oem_confirmed"

        fun newInstance() = KeepAliveGuideDialogFragment()

        private fun prefs(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context)

        private fun markShown(context: Context) {
            prefs(context).edit { putBoolean(PREF_SHOWN, true) }
        }

        fun wasShown(context: Context): Boolean =
            prefs(context).getBoolean(PREF_SHOWN, false)

        private fun isOemConfirmed(context: Context): Boolean =
            prefs(context).getBoolean(PREF_OEM_CONFIRMED, false)

        private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun currentState(context: Context, settings: SettingsHelper): KeepAliveGuideState =
            KeepAliveGuideState(
                batteryExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isIgnoringBatteryOptimizations(context)
                } else {
                    null
                },
                autostart = settings.autostart,
                oemConfirmed = isOemConfirmed(context),
            )
    }
}
