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
import me.capcom.smsgateway.modules.account.AccountSettings
import me.capcom.smsgateway.modules.account.AppEnrolled
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.LinkPayload
import me.capcom.smsgateway.ui.account.HomeV2Fragment
import me.capcom.smsgateway.ui.dialogs.FirstStartDialogFragment
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val gatewaySettings: GatewaySettings by inject()
    private val accountSettings: AccountSettings by inject()
    private lateinit var adapter: FragmentsAdapter

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FragmentsAdapter(this, AppEnrolled.check(accountSettings, gatewaySettings))
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

    override fun onResume() {
        super.onResume()
        // Mnaseb SIM v0.3.0: a phone that linked itself through its account gets the
        // account home screen; the QR-path (platform phone) install keeps the upstream one.
        val enrolled = AppEnrolled.check(accountSettings, gatewaySettings)
        if (enrolled != adapter.appHome) {
            adapter.appHome = enrolled
            adapter.notifyDataSetChanged()
        }
    }

    class FragmentsAdapter(activity: AppCompatActivity, var appHome: Boolean) :
        androidx.viewpager2.adapter.FragmentStateAdapter(activity) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> if (appHome) HomeV2Fragment() else HomeFragment.newInstance()
                1 -> HolderFragment.newInstance()
                else -> SettingsFragment.newInstance()
            }
        }

        // A different home screen is a different item, so the pager replaces the fragment.
        override fun getItemId(position: Int): Long =
            if (position == 0 && appHome) HOME_V2_ITEM_ID else position.toLong()

        override fun containsItem(itemId: Long): Boolean =
            itemId == 1L || itemId == 2L || itemId == if (appHome) HOME_V2_ITEM_ID else 0L
    }

    companion object {
        const val TAB_INDEX_HOME = 0
        const val TAB_INDEX_MESSAGES = 1
        const val TAB_INDEX_SETTINGS = 2

        const val HOME_V2_ITEM_ID = 100L

        private const val EXTRA_TAB_INDEX = "tabIndex"

        fun starter(context: Context, tabIndex: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_TAB_INDEX, tabIndex)
            }
        }
    }
}