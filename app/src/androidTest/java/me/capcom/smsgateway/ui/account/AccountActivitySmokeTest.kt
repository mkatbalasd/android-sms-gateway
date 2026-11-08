package me.capcom.smsgateway.ui.account

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.capcom.smsgateway.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test (API 25, 30, 35): the app opens its new first screen, which offers
 * the three ways in, without a crash from the encrypted store or the Koin wiring.
 */
@RunWith(AndroidJUnit4::class)
class AccountActivitySmokeTest {

    @Test
    fun theFirstScreenOffersTheThreeWaysIn() {
        ActivityScenario.launch(AccountActivity::class.java).use {
            onView(withId(R.id.buttonCreateAccount))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.buttonSignIn))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            onView(withId(R.id.buttonHaveLinkCode))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        }
    }
}
