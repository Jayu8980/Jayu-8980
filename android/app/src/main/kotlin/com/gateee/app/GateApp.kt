package com.gateee.app

import android.app.Application
import com.gateee.app.data.AppDatabase
import com.gateee.app.data.BackendClient
import com.gateee.app.data.Repository
import com.gateee.app.data.SettingsStore
import com.gateee.app.reminders.ReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container; one instance per process. */
class AppContainer(app: Application) {
    val settings = SettingsStore(app)
    val db = AppDatabase.build(app)
    val backend = BackendClient(settings)
    val repo = Repository(app, db, backend)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class GateApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            container.repo.seedIfNeeded()
            val s = container.settings.current()
            ReminderWorker.schedule(this@GateApp, s.reminderHour, s.reminderMinute, s.remindersOn)
        }
    }
}
