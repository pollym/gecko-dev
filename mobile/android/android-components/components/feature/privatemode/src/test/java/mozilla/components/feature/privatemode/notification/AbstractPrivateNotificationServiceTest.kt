/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.privatemode.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import mozilla.components.browser.state.action.LocaleAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.privatemode.notification.AbstractPrivateNotificationService.Companion.ACTION_ERASE
import mozilla.components.feature.privatemode.notification.AbstractPrivateNotificationService.Companion.defaultIgnoreTaskActions
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.test.argumentCaptor
import mozilla.components.support.test.ext.joinBlocking
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.rule.runTestOnMain
import mozilla.components.support.test.whenever
import mozilla.components.support.utils.ext.stopForegroundCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import java.util.Locale

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AbstractPrivateNotificationServiceTest {

    @get:Rule
    val coroutinesTestRule = MainCoroutineRule()
    private val dispatcher = coroutinesTestRule.testDispatcher

    private lateinit var preferences: SharedPreferences
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        preferences = mock()
        notificationManager = mock()
        val editor = mock<SharedPreferences.Editor>()

        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putLong(anyString(), anyLong())).thenReturn(editor)
    }

    @Test
    fun `WHEN the service is created THEN start foreground is called`() = runTestOnMain {
        val service = spy(
            object : MockService(scope = this@runTestOnMain) {
                override fun NotificationCompat.Builder.buildNotification() {
                    setCategory(Notification.CATEGORY_STATUS)
                }
                override fun notifyLocaleChanged() {
                    // NOOP
                }
            },
        )
        attachContext(service)

        val notification = argumentCaptor<Notification>()
        service.onCreate()
        advanceUntilIdle()

        verify(service).startForeground(anyInt(), notification.capture())
        assertEquals(Notification.CATEGORY_STATUS, notification.value.category)
    }

    @Test
    fun `GIVEN an erase intent is received THEN remove all private tabs`() {
        val service = MockService()
        val result = service.onStartCommand(Intent(ACTION_ERASE), 0, 0)

        verify(service.store).dispatch(TabListAction.RemoveAllPrivateTabsAction)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `WHEN task is removed THEN all private tabs are removed`() {
        val service = spy(MockService())
        service.onTaskRemoved(mock())

        verify(service.store).dispatch(TabListAction.RemoveAllPrivateTabsAction)
        verify(service).stopForegroundCompat(true)
        verify(service).stopSelf()
    }

    @Test
    fun `WHEN task is removed with ignored intents THEN do nothing`() {
        val service = spy(MockService())

        val mockTaskActions = listOf("action1", "action2")
        whenever(service.ignoreTaskActions()).then { mockTaskActions }

        (mockTaskActions + defaultIgnoreTaskActions).forEach { it ->
            service.onTaskRemoved(Intent(it))

            verify(service.store, never()).dispatch(TabListAction.RemoveAllPrivateTabsAction)
            verify(service, never()).stopForegroundCompat(true)
            verify(service, never()).stopSelf()
        }

        val mockTaskCompoentClasses = listOf(
            "org.mozilla.fenix.IntentReceiverActivity",
            "org.mozilla.fenix.customtabs.ExternalAppBrowserActivity",
            "comp1",
            "comp2",
        )
        whenever(service.ignoreTaskComponentClasses()).then { mockTaskCompoentClasses }

        mockTaskCompoentClasses.forEach { it ->
            service.onTaskRemoved(Intent().setComponent(ComponentName(testContext, it)))

            verify(service.store, never()).dispatch(TabListAction.RemoveAllPrivateTabsAction)
            verify(service, never()).stopForegroundCompat(true)
            verify(service, never()).stopSelf()
        }
    }

    @Test
    fun `WHEN a locale change is made in the browser store THEN the service should notify`() {
        val service = spy(MockServiceWithStore())
        attachContext(service)
        service.onCreate()

        val mockLocale = Locale.forLanguageTag("English")
        service.store.dispatch(LocaleAction.UpdateLocaleAction(mockLocale)).joinBlocking()
        dispatcher.scheduler.advanceUntilIdle()

        verify(service).notifyLocaleChanged()
    }

    private open class MockService(scope: CoroutineScope = TestScope()) :
        AbstractPrivateNotificationService(scope) {
        override val store: BrowserStore = spy(BrowserStore())
        override val notificationsDelegate: NotificationsDelegate = mock()

        override fun NotificationCompat.Builder.buildNotification() = Unit
        override fun notifyLocaleChanged() {
            // NOOP
        }

        override fun ignoreTaskActions(): List<String> = mock()
        override fun ignoreTaskComponentClasses(): List<String> = mock()
    }

    private open class MockServiceWithStore : AbstractPrivateNotificationService() {
        override val store = BrowserStore(
            BrowserState(
                locale = null,
            ),
        )
        override val notificationsDelegate: NotificationsDelegate = mock()

        override fun NotificationCompat.Builder.buildNotification() = Unit
        override fun notifyLocaleChanged() {
            // NOOP
        }

        override fun ignoreTaskActions(): List<String> = mock()
        override fun ignoreTaskComponentClasses(): List<String> = mock()
    }

    private fun attachContext(service: Service) {
        Mockito.doReturn(preferences).`when`(service).getSharedPreferences(anyString(), anyInt())
        Mockito.doReturn(notificationManager).`when`(service).getSystemService<NotificationManager>()
        Mockito.doReturn("").`when`(service).getString(anyInt())
        Mockito.doReturn("").`when`(service).packageName
        Mockito.doReturn(testContext.resources).`when`(service).resources
        Mockito.doReturn(testContext.applicationInfo).`when`(service).applicationInfo
    }
}
