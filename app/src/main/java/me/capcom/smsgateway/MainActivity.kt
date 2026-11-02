package me.capcom.smsgateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayoutMediator
import me.capcom.smsgateway.databinding.ActivityMainBinding
import me.capcom.smsgateway.ui.HolderFragment
import me.capcom.smsgateway.ui.HomeFragment
import me.capcom.smsgateway.ui.SettingsFragment
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.LinkPayload
import me.capcom.smsgateway.ui.dialogs.FirstStartDialogFragment
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val gatewaySettings: GatewaySettings by inject()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = FragmentsAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            when (position) {
                0 -> tab.apply {
                    text = getString(R.string.tab_text_home)
                    setIcon(R.drawable.ic_home)
                }

                1 -> tab.apply {
                    text = getString(R.string.tab_text_messages)
                    setIcon(R.drawable.ic_sms)
                }

                2 -> tab.apply {
                    text = getString(R.string.tab_text_settings)
                    setIcon(R.drawable.ic_advanced)
                }
            }
        }.attach()

        // On recreation the intent was already handled (and a link dialog restored).
        if (savedInstanceState == null) {
            processIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        val tabIndex = intent.getIntExtra(EXTRA_TAB_INDEX, TAB_INDEX_HOME)

        binding.viewPager.currentItem = tabIndex

        processLinkIntent(intent)
    }

    // Mnaseb SIM: mnaseb-sim://link?... opens the first-run screen with that payload.
    private fun processLinkIntent(intent: Intent) {
        val data = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!LinkPayload.SCHEME.equals(data.scheme, ignoreCase = true)) return

        if (gatewaySettings.registrationInfo != null) {
            Toast.makeText(this, R.string.link_already_linked, Toast.LENGTH_LONG).show()
            return
        }

        (supportFragmentManager.findFragmentByTag(FirstStartDialogFragment.TAG) as? DialogFragment)
            ?.dismissAllowingStateLoss()
        FirstStartDialogFragment.newInstance(intent.dataString)
            .showNow(supportFragmentManager, FirstStartDialogFragment.TAG)
    }

    class FragmentsAdapter(activity: AppCompatActivity) :
        androidx.viewpager2.adapter.FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment.newInstance()
                1 -> HolderFragment.newInstance()
                else -> SettingsFragment.newInstance()
            }
        }

    }

    companion object {
        const val TAB_INDEX_HOME = 0
        const val TAB_INDEX_MESSAGES = 1
        const val TAB_INDEX_SETTINGS = 2

        private const val EXTRA_TAB_INDEX = "tabIndex"

        fun starter(context: Context, tabIndex: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TAB_INDEX, tabIndex)
            }
        }
    }
}