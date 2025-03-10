/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.geckoview.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.gecko.EventDispatcher
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import org.mozilla.geckoview.TranslationsController.Language
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.ALL
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.ALWAYS
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.DELETE
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.DOWNLOAD
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.LANGUAGE
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.ModelManagementOptions
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.NEVER
import org.mozilla.geckoview.TranslationsController.RuntimeTranslation.OFFER
import org.mozilla.geckoview.TranslationsController.SessionTranslation.Delegate
import org.mozilla.geckoview.TranslationsController.SessionTranslation.TranslationOptions
import org.mozilla.geckoview.TranslationsController.SessionTranslation.TranslationState
import org.mozilla.geckoview.TranslationsController.TranslationsException
import org.mozilla.geckoview.TranslationsController.TranslationsException.ERROR_COULD_NOT_TRANSLATE
import org.mozilla.geckoview.TranslationsController.TranslationsException.ERROR_MODEL_COULD_NOT_DELETE
import org.mozilla.geckoview.TranslationsController.TranslationsException.ERROR_MODEL_COULD_NOT_DOWNLOAD
import org.mozilla.geckoview.TranslationsController.TranslationsException.ERROR_MODEL_DOWNLOAD_REQUIRED
import org.mozilla.geckoview.TranslationsController.TranslationsException.ERROR_MODEL_LANGUAGE_REQUIRED
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.AssertCalled

@RunWith(AndroidJUnit4::class)
@MediumTest
class TranslationsTest : BaseSessionTest() {
    @Before
    fun setup() {
        sessionRule.setPrefsUntilTestEnd(
            mapOf(
                "browser.translations.enable" to true,
                "browser.translations.automaticallyPopup" to true,
                "intl.accept_languages" to "en",
                "browser.translations.geckoview.enableAllTestMocks" to true,
                "browser.translations.simulateUnsupportedEngine" to false,
            ),
        )
    }

    @After
    fun cleanup() {
        sessionRule.setPrefsUntilTestEnd(
            mapOf(
                "browser.translations.automaticallyPopup" to false,
                "browser.translations.geckoview.enableAllTestMocks" to false,
            ),
        )
    }

    private var mockedExpectedLanguages: List<TranslationsController.Language> = listOf(
        Language("en", "English"),
        Language("es", "Spanish"),
    )

    @Test
    fun onExpectedTranslateDelegateTest() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        val handled = GeckoResult<Void>()
        sessionRule.delegateUntilTestEnd(object : Delegate {
            @AssertCalled(count = 1)
            override fun onExpectedTranslate(session: GeckoSession) {
                handled.complete(null)
            }
        })
        val expectedTranslateEvent = JSONObject(
            """
            {
            "actor":{
                "languageState":{
                    "detectedLanguages": {
                      "userLangTag": "en",
                      "isDocLangTagSupported": true,
                      "docLangTag": "es"
                    },
                    "requestedLanguagePair": null,
                    "hasVisibleChange": false,
                    "error": null,
                    "isEngineReady": false
                    }
                }
            }
            """.trimIndent(),
        )
        mainSession.triggerLanguageStateChange(expectedTranslateEvent)
        sessionRule.waitForResult(handled)
    }

    @Test
    fun onOfferTranslateDelegateTest() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        val handled = GeckoResult<Void>()
        sessionRule.delegateUntilTestEnd(object : Delegate {
            @AssertCalled(count = 1)
            override fun onOfferTranslate(session: GeckoSession) {
                handled.complete(null)
            }
        })

        mainSession.triggerTranslationsOffer()
        sessionRule.waitForResult(handled)
    }

    @Test
    fun onTranslationStateChangeDelegateTest() {
        if (sessionRule.env.isAutomation) {
            sessionRule.delegateDuringNextWait(object : Delegate {
                @AssertCalled(count = 1)
                override fun onTranslationStateChange(
                    session: GeckoSession,
                    translationState: TranslationState?,
                ) {
                }
            })
        } else {
            // For use when running from Android Studio
            sessionRule.delegateDuringNextWait(object : Delegate {
                @AssertCalled(count = 2)
                override fun onTranslationStateChange(
                    session: GeckoSession,
                    translationState: TranslationState?,
                ) {
                }
            })
        }
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()
    }

    // Simpler translation test that doesn't test delegate state.
    // Tests en -> es
    @Test
    fun simpleTranslateTest() {
        mainSession.loadTestPath(TRANSLATIONS_EN)
        mainSession.waitForPageStop()
        // No options specified should just perform default expectations
        val translate = sessionRule.session.sessionTranslation!!.translate("en", "es", null)
        try {
            sessionRule.waitForResult(translate)
            // When testing from AS, this path is possible.
            if (!sessionRule.env.isAutomation) {
                assertTrue("Translate should complete.", true)
            }
        } catch (e: Exception) {
            if (sessionRule.env.isAutomation) {
                assertTrue("Expect an exception while translating in automation.", true)
            }
        }

        // Options should work as expected
        val options = TranslationOptions.Builder().downloadModel(true).build()
        val translateOptions = sessionRule.session.sessionTranslation!!.translate("en", "es", options)
        try {
            sessionRule.waitForResult(translateOptions)
            // When testing from AS, this path is possible.
            if (!sessionRule.env.isAutomation) {
                assertTrue("Translate should complete with options.", true)
            }
        } catch (e: Exception) {
            if (sessionRule.env.isAutomation) {
                assertTrue("Expect an exception while translating in automation.", true)
            }
        }

        // Language tags should be fault tolerant of minor variations
        val longLanguageTag = sessionRule.session.sessionTranslation!!.translate("EN", "ES", null)
        try {
            sessionRule.waitForResult(longLanguageTag)
            // When testing from AS, this path is possible.
            if (!sessionRule.env.isAutomation) {
                assertTrue("Translate should complete with longer language tag.", true)
            }
        } catch (e: Exception) {
            if (sessionRule.env.isAutomation) {
                assertTrue("Expect an exception while translating in automation.", true)
            }
        }
    }

    @Test
    fun simpleTranslateFailureTest() {
        // Note: Test endpoint is using a mocked response for checking sizes in CI
        mainSession.loadTestPath(TRANSLATIONS_EN)
        mainSession.waitForPageStop()

        // In Android Studio tests, it is checking for real models, so delete to ensure clear test framework.
        if (!sessionRule.env.isAutomation) {
            val allDeleteAttempt = ModelManagementOptions.Builder()
                .operation(DELETE)
                .operationLevel(ALL)
                .build()
            sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(allDeleteAttempt))
        }

        var options = TranslationOptions.Builder().downloadModel(false).build()
        val translate = sessionRule.session.sessionTranslation!!.translate("en", "es", options)
        try {
            sessionRule.waitForResult(translate)
            assertTrue("Translate should not complete", false)
        } catch (e: RuntimeException) {
            // Wait call causes a runtime exception too.
            val te = e.cause as TranslationsException
            assertTrue(
                "Correctly rejected performing a download for a translation.",
                te.code == ERROR_MODEL_DOWNLOAD_REQUIRED,
            )
        }
    }

    @Test
    fun testInvalidLanguageCode() {
        sessionRule.setPrefsUntilTestEnd(
            mapOf(
                "browser.translations.geckoview.enableAllTestMocks" to false,
            ),
        )
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        val invalidCode = "xyz-not-a-language"

        // Translate
        val translate = sessionRule.session.sessionTranslation!!.translate("es", invalidCode, null)
        try {
            sessionRule.waitForResult(translate)
            fail("Should not complete requests on an translate invalid code.")
        } catch (e: RuntimeException) {
            // Wait call causes a runtime exception too.
            val te = e.cause as TranslationsException
            assertEquals(
                "Correctly could not translate.",
                ERROR_COULD_NOT_TRANSLATE,
                te.code,
            )
        }

        // Set Language Settings
        try {
            sessionRule.waitForResult(RuntimeTranslation.setLanguageSettings(invalidCode, NEVER))
            fail("Should not complete requests on an invalid language setting code.")
        } catch (e: RuntimeException) {
            // Wait call causes a runtime exception too.
            val qe = e.cause as EventDispatcher.QueryException
            assertEquals(
                "Correctly could not set language setting.",
                "The language tag $invalidCode is not valid.",
                qe.data.toString(),
            )
        }

        // Size Check
        try {
            sessionRule.waitForResult(RuntimeTranslation.checkPairDownloadSize("es", invalidCode))
            fail("Should not complete requests on an invalid download size check code.")
        } catch (e: RuntimeException) {
            // Wait call causes a runtime exception too.
            val qe = e.cause as EventDispatcher.QueryException
            assertEquals(
                "Correctly could not complete a size check.",
                "The language tag es or $invalidCode is not valid.",
                qe.data.toString(),
            )
        }
    }

    // More comprehensive translation test that also tests delegate state.
    // Tests es -> en
    @Test
    fun translateTest() {
        var delegateCalled = 0
        sessionRule.delegateUntilTestEnd(object : Delegate {
            @AssertCalled(count = 3)
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationState?,
            ) {
                delegateCalled++
                // Before page load
                if (delegateCalled == 1) {
                    assertTrue(
                        "Translations correctly does not have a requested pair.",
                        translationState?.requestedTranslationPair == null,
                    )
                }
                // Page load
                if (delegateCalled == 2) {
                    assertTrue("Translations correctly has detected a page language. ", translationState?.detectedLanguages?.docLangTag == "es")
                }

                // Translate
                if (delegateCalled == 3) {
                    assertTrue("Translations correctly has set a translation pair from language. ", translationState?.requestedTranslationPair?.fromLanguage == "es")
                    assertTrue("Translations correctly has set a translation pair to language. ", translationState?.requestedTranslationPair?.toLanguage == "en")
                }
            }
        })
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()
        val translate = sessionRule.session.sessionTranslation!!.translate("es", "en", null)
        try {
            sessionRule.waitForResult(translate)
            // When testing from AS, this path is possible.
            if (!sessionRule.env.isAutomation) {
                assertTrue("Should be able to translate.", true)
            }
        } catch (e: Exception) {
            if (sessionRule.env.isAutomation) {
                assertTrue("Expect an exception while translating in automation.", true)
            }
        }
    }

    @Test
    fun checkPairDownloadSizeTest() {
        // Note: Test endpoint is using a mocked response when checking sizes in CI
        val size = RuntimeTranslation.checkPairDownloadSize("es", "en")
        try {
            val result = sessionRule.waitForResult(size)
            assertTrue("Should return a download size.", true)
            if (sessionRule.env.isAutomation) {
                assertTrue("Received mocked value of 1234567.", result == 1234567L)
            }
        } catch (e: Exception) {
            assertTrue("Should not have an exception.", false)
        }
    }

    @Test
    fun restoreOriginalPageLanguageTest() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()
        val restore = sessionRule.session.sessionTranslation!!.restoreOriginalPage()
        try {
            sessionRule.waitForResult(restore)
            assertTrue("Should be able to restore.", true)
        } catch (e: Exception) {
            assertTrue("Should not have an exception.", false)
        }
    }

    @Test
    fun testTranslationOptions() {
        var options = TranslationOptions.Builder().downloadModel(true).build()
        assertTrue("TranslationOptions builder options work as expected.", options.downloadModel)
    }

    @Test
    fun testIsTranslationsEngineSupported() {
        sessionRule.setPrefsUntilTestEnd(mapOf("browser.translations.simulateUnsupportedEngine" to false))
        val isSupportedResult = TranslationsController.RuntimeTranslation.isTranslationsEngineSupported()
        assertTrue(
            "The translations engine is correctly reporting as supported.",
            sessionRule.waitForResult(isSupportedResult),
        )
    }

    @Test
    fun testIsTranslationsEngineNotSupported() {
        sessionRule.setPrefsUntilTestEnd(mapOf("browser.translations.simulateUnsupportedEngine" to true))
        val isSupportedResult = TranslationsController.RuntimeTranslation.isTranslationsEngineSupported()
        assertTrue(
            "The translations engine is correctly reporting as not supported.",
            sessionRule.waitForResult(isSupportedResult) == false,
        )
    }

    @Test
    fun testGetPreferredLanguage() {
        sessionRule.setPrefsUntilTestEnd(mapOf("intl.accept_languages" to "fr-CA, it, de"))
        val preferredLanguages = TranslationsController.RuntimeTranslation.preferredLanguages()
        sessionRule.waitForResult(preferredLanguages).let { languages ->
            assertEquals(
                "French is the first language preference.",
                "fr-CA",
                languages[0],
            )
            assertEquals(
                "Italian is the second language preference.",
                "it",
                languages[1],
            )
            assertEquals(
                "German is the third language preference.",
                "de",
                languages[2],
            )
            // "en" is likely the 4th preference via system language;
            // however, this is difficult to guarantee/set in automation.
        }
    }

    @Test
    fun testManageLanguageModel() {
        val options = ModelManagementOptions.Builder()
            .languageToManage("en")
            .operation(DOWNLOAD)
            .build()

        assertTrue(
            "ModelManagementOptions builder options work as expected.",
            options.language == "en" && options.operation == DOWNLOAD,
        )

        val nonNormalizedOptions = ModelManagementOptions.Builder()
            .languageToManage("EN")
            .operation("DoWnLoAd")
            .build()

        assertTrue(
            "ModelManagementOptions builder options work as expected on non-normalized options.",
            nonNormalizedOptions.language == "en" && nonNormalizedOptions.operation == DOWNLOAD,
        )
    }

    @Test
    fun testListSupportedLanguages() {
        // Note: Test endpoint is using a mocked response
        val translationDropdowns = TranslationsController.RuntimeTranslation.listSupportedLanguages()
        try {
            sessionRule.waitForResult(translationDropdowns)
            assertTrue("Should be able to list supported languages.", true)
        } catch (e: Exception) {
            assertTrue("Should not have an exception.", false)
        }
        var fromPresent = true
        var toPresent = true
        sessionRule.waitForResult(translationDropdowns).let { dropdowns ->
            // Test is checking for minimum options are present based on mocked expectations.
            for (expected in mockedExpectedLanguages) {
                if (!dropdowns.fromLanguages!!.contains(expected)) {
                    assertTrue("Language $expected was not in from list.", false)
                    fromPresent = false
                }
                if (!dropdowns.toLanguages!!.contains(expected)) {
                    assertTrue("Language $expected was not in to list.", false)
                    toPresent = false
                }
            }
        }
        assertTrue(
            "All primary from languages are present.",
            fromPresent,
        )
        assertTrue(
            "All primary to languages are present.",
            toPresent,
        )
    }

    @Test
    fun testListModelDownloadStates() {
        // Note: Test endpoint is using a mocked response
        val modelStatesResult = TranslationsController.RuntimeTranslation.listModelDownloadStates()
        try {
            sessionRule.waitForResult(modelStatesResult)
            assertTrue("Should not be able to list models.", true)
        } catch (e: Exception) {
            assertTrue("Should not have an exception.", false)
        }

        sessionRule.waitForResult(modelStatesResult).let { models ->
            assertTrue(
                "Received information on the state of the models.",
                models.size >= mockedExpectedLanguages.size - 1,
            )
            assertTrue(
                "Received information on the size in bytes of the first returned model.",
                models[0].size > 0,
            )
            assertTrue(
                "Received information on the language of the first returned model.",
                models[0].language != null,
            )
            assertTrue(
                "Received information on the download state of the first returned model",
                !models[0].isDownloaded,
            )
        }
    }

    @Test
    fun testSetLanguageSettings() {
        // Not a valid language tag
        try {
            sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("EN_US", NEVER))
        } catch (e: Exception) {
            assertTrue("Should have an exception, this isn't a valid tag.", true)
        }

        // Capital BG is non-canonical BCP 47
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("BG", ALWAYS))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("fr", OFFER))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("de", NEVER))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("zh-Hans", NEVER))

        // Query corresponding prefs
        val alwaysTranslate = (sessionRule.getPrefs("browser.translations.alwaysTranslateLanguages").get(0) as String).split(",")
        val neverTranslate = (sessionRule.getPrefs("browser.translations.neverTranslateLanguages").get(0) as String).split(",")

        // Test setting
        assertTrue("BG was correctly set to ALWAYS", alwaysTranslate.contains("BG"))
        assertTrue("FR was correctly set to OFFER", !alwaysTranslate.contains("fr") && !neverTranslate.contains("fr"))
        assertTrue("DE was correctly set to NEVER", neverTranslate.contains("de"))
        assertTrue("zh-Hans was correctly set to NEVER", neverTranslate.contains("zh-Hans"))

        // Reset back to offer
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("BG", OFFER))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("fr", OFFER))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("de", OFFER))
        sessionRule.waitForResult(TranslationsController.RuntimeTranslation.setLanguageSettings("zh-Hans", OFFER))

        // Query corresponding prefs
        val alwaysTranslateReset = (sessionRule.getPrefs("browser.translations.alwaysTranslateLanguages").get(0) as String).split(",")
        val neverTranslateReset = (sessionRule.getPrefs("browser.translations.neverTranslateLanguages").get(0) as String).split(",")

        // Test offer reset
        assertTrue("BG was correctly set back to OFFER", !alwaysTranslateReset.contains("bg") && !neverTranslateReset.contains("bg"))
        assertTrue("FR was correctly set back to OFFER", !alwaysTranslateReset.contains("fr") && !neverTranslateReset.contains("fr"))
        assertTrue("DE was correctly set back to OFFER", !alwaysTranslateReset.contains("de") && !neverTranslateReset.contains("de"))
        assertTrue("zh-Hans was correctly set back to OFFER", !alwaysTranslateReset.contains("zh-Hans") && !neverTranslateReset.contains("zh-Hans"))
    }

    @Test
    fun testGetLanguageSettings() {
        // Note: Test endpoint is using a mocked response and doesn't reflect actual prefs
        val languageSettings: Map<String, String> =
            sessionRule.waitForResult(TranslationsController.RuntimeTranslation.getLanguageSettings())

        val frLanguageSetting = sessionRule.waitForResult(TranslationsController.RuntimeTranslation.getLanguageSetting("fr"))

        if (sessionRule.env.isAutomation) {
            assertTrue("FR was correctly set to ALWAYS via full query.", languageSettings["fr"] == ALWAYS)
            assertTrue("FR was correctly set to ALWAYS via individual query.", frLanguageSetting == ALWAYS)
            assertTrue("DE was correctly set to OFFER via full query.", languageSettings["de"] == OFFER)
            assertTrue("ES was correctly set to NEVER via full query.", languageSettings["es"] == NEVER)
        } else {
            // For use when running from Android Studio
            assertTrue("Correctly queried language settings.", languageSettings.isNotEmpty())
            assertTrue("Correctly queried FR language setting.", frLanguageSetting.isNotEmpty())
        }
    }

    @Test
    fun testOfferPopup() {
        assertTrue("Translation offer popups are enabled, as expected.", sessionRule.runtime.settings.translationsOfferPopup)
        sessionRule.runtime.settings.translationsOfferPopup = false
        assertTrue("Translation offer popups are disabled, as expected.", !sessionRule.runtime.settings.translationsOfferPopup)
        val finalPrefCheck = (sessionRule.getPrefs("browser.translations.automaticallyPopup").get(0)) as Boolean
        assertTrue("Translation offer popups are disabled, as expected and match test harness reported value.", finalPrefCheck == sessionRule.runtime.settings.translationsOfferPopup)
    }

    @Test
    fun testNeverTranslateSite() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        var neverTranslateSetting = sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.neverTranslateSiteSetting)
        assertTrue("Expect never translate to be false on a new page.", !neverTranslateSetting)

        sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.setNeverTranslateSiteSetting(true))
        neverTranslateSetting = sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.neverTranslateSiteSetting)
        assertTrue("Expect never translate to be true after setting.", neverTranslateSetting)

        sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.setNeverTranslateSiteSetting(false))
    }

    @Test
    fun testNeverTranslateSpecificSite() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        // Get never translate list using Runtime API (if any) and clear never translate settings
        var listOfSitesNeverToTranslate = sessionRule.waitForResult(RuntimeTranslation.getNeverTranslateSiteList())
        for (site in listOfSitesNeverToTranslate) {
            sessionRule.waitForResult(RuntimeTranslation.setNeverTranslateSpecifiedSite(false, site))
        }

        // Get never translate list using Runtime API
        listOfSitesNeverToTranslate = sessionRule.waitForResult(RuntimeTranslation.getNeverTranslateSiteList())
        assertTrue("Expect there to be no never translate sites set.", listOfSitesNeverToTranslate.isEmpty())

        // Set site using Session API and confirm set
        sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.setNeverTranslateSiteSetting(true))
        var sessionNeverTranslateSetting = sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.neverTranslateSiteSetting)
        assertTrue("Expect never translate to be true after setting using session API.", sessionNeverTranslateSetting)

        // Get list again using Runtime API
        listOfSitesNeverToTranslate = sessionRule.waitForResult(RuntimeTranslation.getNeverTranslateSiteList())
        assertTrue("Expect there to be one site in the list after setting.", listOfSitesNeverToTranslate.size == 1)

        // Unset using Runtime API
        sessionRule.waitForResult(RuntimeTranslation.setNeverTranslateSpecifiedSite(false, listOfSitesNeverToTranslate[0]))

        // Check unset again using Session API
        sessionNeverTranslateSetting = sessionRule.waitForResult(sessionRule.session.sessionTranslation!!.neverTranslateSiteSetting)
        assertTrue("Expect never translate to be false after unsetting using runtime API.", !sessionNeverTranslateSetting)
    }

    @Test
    fun testBCP47PrefSetting() {
        // Only test when running locally in Android Studio (not ./mach geckoview-junit)
        // Remote settings and translations behaves the same as production when ran from Android Studio.
        if (!sessionRule.env.isAutomation) {
            // Check that nothing has been set between test runs
            val activeTranslationPrefs = (
                sessionRule.getPrefs("browser.translations.alwaysTranslateLanguages")
                    .get(0) as String
                )
            assertTrue(
                "There should be no active preferences for always translate set. Preferences: $activeTranslationPrefs",
                activeTranslationPrefs == "",
            )

            // Set to always translate
            sessionRule.waitForResult(
                TranslationsController.RuntimeTranslation.setLanguageSettings(
                    "ES",
                    ALWAYS,
                ),
            )

            var translateCompleted = GeckoResult<Void>()
            sessionRule.delegateUntilTestEnd(object : Delegate {
                @AssertCalled(count = 4)
                override fun onTranslationStateChange(
                    session: GeckoSession,
                    translationState: TranslationState?,
                ) {
                    if (translationState?.isEngineReady == true) {
                        assertTrue("Auto requested the from language as Spanish on the page.", translationState.requestedTranslationPair?.fromLanguage == "es")
                        translateCompleted.complete(null)
                    }
                }
            })

            mainSession.loadTestPath(TRANSLATIONS_ES)
            mainSession.waitForPageStop()
            sessionRule.waitForResult(translateCompleted)

            // Reset back to offer
            sessionRule.waitForResult(
                TranslationsController.RuntimeTranslation.setLanguageSettings(
                    "ES",
                    OFFER,
                ),
            )
        }
    }

    @Test
    fun testManageLanguageModelErrors() {
        val missingLanguage = ModelManagementOptions.Builder()
            .operation(DOWNLOAD)
            .operationLevel(LANGUAGE)
            .build()
        try {
            sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(missingLanguage))
            fail("Should not complete requests on an incompatible state.")
        } catch (e: RuntimeException) {
            // Wait call causes a runtime exception too.
            val te = e.cause as TranslationsException
            assertTrue(
                "Correctly rejected an incompatible state with missing language.",
                te.code == ERROR_MODEL_LANGUAGE_REQUIRED,
            )
        }

        // In the Android Studio test runner, these should be skipped because Remote Settings is
        // active. However, in CI, these will fail as expected because no download service is available.
        if (sessionRule.env.isAutomation) {
            val allDownloadAttempt = ModelManagementOptions.Builder()
                .operation(DOWNLOAD)
                .operationLevel(ALL)
                .build()
            try {
                sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(allDownloadAttempt))
                fail("Should not complete downloads in automation.")
            } catch (e: RuntimeException) {
                // Wait call causes a runtime exception too.
                val te = e.cause as TranslationsException
                assertTrue(
                    "Correctly could not download on automated test harness.",
                    te.code == ERROR_MODEL_COULD_NOT_DOWNLOAD,
                )
            }

            val allDeleteAttempt = ModelManagementOptions.Builder()
                .operation(DELETE)
                .operationLevel(ALL)
                .build()
            try {
                sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(allDeleteAttempt))
                fail("Should not complete deletes in automation.")
            } catch (e: RuntimeException) {
                // Wait call causes a runtime exception too.
                val te = e.cause as TranslationsException
                assertTrue(
                    "Correctly could not delete on automated test harness.",
                    te.code == ERROR_MODEL_COULD_NOT_DELETE,
                )
            }

            val malformedRequest = ModelManagementOptions.Builder()
                .operation("not-a-function")
                .operationLevel("not-an-operation")
                .build()
            try {
                sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(malformedRequest))
                fail("Should not complete malformed requests in automation.")
            } catch (e: RuntimeException) {
                // Wait call causes a runtime exception too.
                val te = e.cause as TranslationsException
                assertTrue(
                    "Correctly could not submit a malformed request.",
                    te.code == TranslationsException.ERROR_UNKNOWN,
                )
            }

            val malformedCacheDownloadingRequest = ModelManagementOptions.Builder()
                .operation(DOWNLOAD)
                .operationLevel(RuntimeTranslation.CACHE)
                .build()
            try {
                sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(malformedCacheDownloadingRequest))
                fail("Should not complete an invalid request.")
            } catch (e: RuntimeException) {
                // Wait call causes a runtime exception too.
                val te = e.cause as TranslationsException
                assertTrue(
                    "Correctly could not download the cache.",
                    te.code == ERROR_MODEL_COULD_NOT_DOWNLOAD,
                )
            }
        }
    }

    @Test
    fun testCacheClearing() {
        // Test portion for Android Studio, where Remote Settings will be active
        if (!sessionRule.env.isAutomation) {
            mainSession.loadTestPath(TRANSLATIONS_EN)
            mainSession.waitForPageStop()
            // Will cause a download
            val translate = sessionRule.session.sessionTranslation!!.translate("en", "es", null)
            sessionRule.waitForResult(translate)

            // Try to clear the download
            val clearDownloadCache = ModelManagementOptions.Builder()
                .operation(DELETE)
                .operationLevel(RuntimeTranslation.CACHE)
                .build()

            sessionRule.waitForResult(RuntimeTranslation.manageLanguageModel(clearDownloadCache))
            assertTrue("Successfully translated and cleared the downloaded file", true)
        }
    }

    @Test
    fun hasVisibleChangeTest() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        val handled = GeckoResult<Void>()
        var delegateCalled = 0
        sessionRule.delegateUntilTestEnd(object : Delegate {
            @AssertCalled(count = 2)
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationState?,
            ) {
                delegateCalled++

                if (delegateCalled == 1) {
                    assertFalse("Initially not visibly changed.", translationState!!.hasVisibleChange)
                }

                if (delegateCalled == 2) {
                    assertTrue("After a translation, the DOM should be visibly changed.", translationState!!.hasVisibleChange)
                    handled.complete(null)
                }
            }
        })
        val notTranslated = JSONObject(
            """
            {
            "actor":{
                "languageState":{
                    "detectedLanguages": {
                      "userLangTag": "en",
                      "isDocLangTagSupported": true,
                      "docLangTag": "es"
                    },
                    "requestedLanguagePair": null,
                    "hasVisibleChange": false,
                    "error": null,
                    "isEngineReady": false
                    }
                }
            }
            """.trimIndent(),
        )
        mainSession.triggerLanguageStateChange(notTranslated)

        val translated = JSONObject(
            """
            {
            "actor":{
                "languageState":{
                    "detectedLanguages": {
                      "userLangTag": "en",
                      "isDocLangTagSupported": true,
                      "docLangTag": "es"
                    },
                    "requestedLanguagePair": {"sourceLanguage" : "es" , "targetLanguage" : "en"},
                    "hasVisibleChange": true,
                    "error": null,
                    "isEngineReady": true
                    }
                }
            }
            """.trimIndent(),
        )
        mainSession.triggerLanguageStateChange(translated)
        sessionRule.waitForResult(handled)
    }

    @Test
    fun checkStateDeserialization() {
        mainSession.loadTestPath(TRANSLATIONS_ES)
        mainSession.waitForPageStop()

        val handled = GeckoResult<Void>()
        sessionRule.delegateUntilTestEnd(object : Delegate {
            @AssertCalled(count = 1)
            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationState?,
            ) {
                assertEquals(
                    "userLangTag is as expected.",
                    "en",
                    translationState!!.detectedLanguages!!.userLangTag,
                )
                assertEquals(
                    "isDocLangTagSupported is as expected.",
                    true,
                    translationState.detectedLanguages!!.isDocLangTagSupported,
                )
                assertEquals(
                    "docLangTag is as expected.",
                    "es",
                    translationState.detectedLanguages!!.docLangTag,
                )
                // Full rename to match JS and Java names is bug 1943444, right now it deserializes to different Java names.
                assertEquals(
                    "sourceLanguage is as expected.",
                    "es",
                    translationState.requestedTranslationPair!!.fromLanguage,
                )
                assertEquals(
                    "targetLanguage is as expected.",
                    "en",
                    translationState.requestedTranslationPair!!.toLanguage,
                )
                assertEquals(
                    "hasVisibleChange is as expected.",
                    true,
                    translationState.hasVisibleChange,
                )
                assertNull("error is as expected.", translationState.error)
                assertEquals(
                    "isEngineReady is as expected.",
                    true,
                    translationState.isEngineReady,
                )
                handled.complete(null)
            }
        })

        val translated = JSONObject(
            """
            {
            "actor":{
                "languageState":{
                    "detectedLanguages": {
                      "userLangTag": "en",
                      "isDocLangTagSupported": true,
                      "docLangTag": "es"
                    },
                    "requestedLanguagePair": {"sourceLanguage" : "es" , "targetLanguage" : "en"},
                    "hasVisibleChange": true,
                    "error": null,
                    "isEngineReady": true
                    }
                }
            }
            """.trimIndent(),
        )
        mainSession.triggerLanguageStateChange(translated)
        sessionRule.waitForResult(handled)
    }
}
