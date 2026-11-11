package me.capcom.smsgateway.ui.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.account.EnrolmentState
import java.util.Locale

/**
 * Mnaseb SIM v0.3.0 first run: welcome, permissions, create account / sign in,
 * code, linking. It returns [RESULT_OK] to the home screen once the phone is
 * linked, and [RESULT_USE_LINK_CODE] when the keeper chose "I have a link code
 * from the workspace" (the QR and typed-code dialog stays as it was).
 */
class AccountActivity : AppCompatActivity() {

    private lateinit var viewModel: AccountViewModel

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        viewModel = ViewModelProvider(this)[AccountViewModel::class.java]
        viewModel.screen.observe(this) { screen -> if (screen != null) show(screen) }
        viewModel.enrolment.observe(this) { state ->
            if (state is EnrolmentState.Linked) setResult(RESULT_OK)
        }

        viewModel.resignedIn.observe(this) { done ->
            if (done == true) {
                setResult(RESULT_SIGNED_IN)
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.back()) finish()
            }
        })
    }

    private fun show(screen: AccountScreen) {
        val tag = screen.name
        val current = supportFragmentManager.findFragmentById(R.id.accountContainer)
        if (current != null && current.tag == tag) return
        val fragment: Fragment = when (screen) {
            AccountScreen.WELCOME -> WelcomeFragment()
            AccountScreen.PERMISSIONS -> PermissionsFragment()
            AccountScreen.CREATE -> CreateAccountFragment()
            AccountScreen.SIGN_IN -> SignInFragment()
            AccountScreen.CODE -> CodeFragment()
            AccountScreen.LINKING -> LinkingFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.accountContainer, fragment, tag)
            .commit()
    }

    /** The third button of the welcome screen: back to the home screen's QR and code dialog. */
    fun useLinkCode() {
        setResult(RESULT_USE_LINK_CODE)
        finish()
    }

    /** The phone is linked: the home screen takes over. */
    fun finishLinked() {
        setResult(RESULT_OK)
        finish()
    }

    /** Arabic is the default; this switches between Arabic and English and redraws the screen. */
    fun toggleLanguage() {
        val target = if (Locale.getDefault().language == "ar") "en" else "ar"
        LocaleHelper.setLocale(applicationContext, target)
        recreate()
    }

    companion object {
        const val RESULT_USE_LINK_CODE = Activity.RESULT_FIRST_USER

        /** A linked phone signed in again: its app session is back, its link was never touched. */
        const val RESULT_SIGNED_IN = Activity.RESULT_FIRST_USER + 1

        fun starter(context: Context) = Intent(context, AccountActivity::class.java)
    }
}
