package me.capcom.smsgateway.ui.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.ui.dialogs.KeepAliveGuideDialogFragment
import org.koin.android.ext.android.inject

/**
 * After linking (F1-6, F1-7): the Numbers step, then the keep-alive guide once.
 * Both can be left with «لاحقاً»; the home screen shows what is still open.
 */
class NumbersActivity : AppCompatActivity() {

    private val client: AppClient by inject()
    private val settingsHelper: SettingsHelper by inject()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_numbers)
        supportFragmentManager.setFragmentResultListener(
            KeepAliveGuideDialogFragment.REQUEST_KEY, this
        ) { _, _ -> finishAfterKeepAlive() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.numbersContainer, NumbersFragment())
                .commit()
        }
    }

    /** From the Numbers screen: the keep-alive guide, shown once, then back to the home screen. */
    fun next() {
        if (KeepAliveGuideDialogFragment.wasShown(this) ||
            supportFragmentManager.findFragmentByTag(KeepAliveGuideDialogFragment.TAG) != null
        ) {
            finish()
            return
        }
        KeepAliveGuideDialogFragment.newInstance()
            .show(supportFragmentManager, KeepAliveGuideDialogFragment.TAG)
    }

    private fun finishAfterKeepAlive() {
        // Best effort, off the screen's critical path: the home screen reports again on resume.
        val app = applicationContext
        val state = KeepAliveGuideDialogFragment.currentState(app, settingsHelper)
        KeepAliveReport.sendInBackground(client, KeepAliveReport.checklist(state))
        finish()
    }

    companion object {
        fun starter(context: Context) = Intent(context, NumbersActivity::class.java)
    }
}
