/*
  Copyright 2024 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import com.adobe.marketing.mobile.internal.CoreConstants
import com.adobe.marketing.mobile.internal.LifecycleTracker
import com.adobe.marketing.mobile.internal.configuration.ConfigurationExtension
import com.adobe.marketing.mobile.internal.eventhub.EventHub
import com.adobe.marketing.mobile.internal.migration.V4Migrator
import com.adobe.marketing.mobile.services.Log
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.services.internal.context.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object MobileCoreInitializer {

    private const val LOG_TAG: String = "MobileCoreInitializer"
    val initializeCalled = AtomicBoolean(false)
    var setApplicationCalled = AtomicBoolean(false)
    private var lifecycleTracker: LifecycleTracker? = null

    fun initialize(
        application: Application,
        initOptions: InitOptions,
        completionCallback: AdobeCallback<*>?
    ) {
        if (initializeCalled.getAndSet(true)) {
            Log.debug(CoreConstants.LOG_TAG, LOG_TAG, "initializeSDK failed - ignoring as it was already called.")
            return
        }

        setApplication(application)

        when (val config = initOptions.config) {
            is ConfigType.AppID -> MobileCore.configureWithAppID(config.appID)
            is ConfigType.FileInPath -> MobileCore.configureWithFileInPath(config.filePath)
            is ConfigType.FileInAssets -> MobileCore.configureWithFileInAssets(config.filePath)
        }

        // Enable automatic lifecycle tracking if specified
        if (initOptions.automaticLifecycleTrackingEnabled) {
            lifecycleTracker = LifecycleTracker(application, initOptions.lifecycleAdditionalContextData)
        }

        // Run extension registration in a background coroutine
        CoroutineScope(Dispatchers.IO).launch {
            val extensions = ExtensionDiscovery.getExtensions(application)
            MobileCore.registerExtensions(extensions, completionCallback)
        }
    }

    fun setApplication(application: Application) {
        // Direct boot mode is supported on Android N and above
        if (Build.VERSION.SDK_INT >= VERSION_CODES.N) {
            if (UserManagerCompat.isUserUnlocked(application)) {
                Log.debug(
                    CoreConstants.LOG_TAG,
                    LOG_TAG,
                    "setApplication - device is unlocked and not in direct boot mode," +
                        " initializing the SDK."
                )
            } else {
                Log.error(
                    CoreConstants.LOG_TAG,
                    LOG_TAG,
                    "setApplication failed - device is in direct boot mode, SDK will not be" +
                        " initialized."
                )
                return
            }
        }

        if (setApplicationCalled.getAndSet(true)) {
            Log.debug(
                CoreConstants.LOG_TAG,
                LOG_TAG,
                "setApplication failed - ignoring as setApplication was already called."
            )
            return
        }

        if (Build.VERSION.SDK_INT == VERSION_CODES.O || Build.VERSION.SDK_INT == VERSION_CODES.O_MR1) {
            // AMSDK-8502
            // Workaround to prevent a crash happening on Android 8.0/8.1 related to
            // TimeZoneNamesImpl
            // https://issuetracker.google.com/issues/110848122
            try {
                Date().toString()
            } catch (e: AssertionError) {
                // Workaround for a bug in Android that can cause crashes on Android 8.0 and 8.1
            } catch (e: Exception) {
                // Workaround for a bug in Android that can cause crashes on Android 8.0 and 8.1
            }
        }

        ServiceProvider.getInstance().appContextService.setApplication(application)
        App.registerActivityResumedListener { activity: Activity? ->
            MobileCore.collectLaunchInfo(activity)
        }

        // Migration and EventHistory operations must complete in a background thread before any
        // extensions are registered.
        // To ensure these tasks are completed before any registerExtension calls are made,
        // reuse the eventHubExecutor instead of using a separate executor instance.
        EventHub.shared.executeInEventHubExecutor {
            try {
                val migrator = V4Migrator()
                migrator.migrate()
            } catch (e: Exception) {
                Log.error(CoreConstants.LOG_TAG, LOG_TAG, "Migration from V4 SDK failed with error - ${e.localizedMessage}")
            }
            // Initialize event history
            EventHub.shared.initializeEventHistory()
        }
    }

    fun registerExtensions(extensions: List<Class<out Extension>>, completionCallback: AdobeCallback<*>?) {
        if (!setApplicationCalled.get()) {
            Log.error(
                CoreConstants.LOG_TAG,
                LOG_TAG,
                "Failed to registerExtensions - setApplication not called"
            )
            return
        }

        val extensionsToRegister: MutableList<Class<out Extension?>> = ArrayList()
        extensionsToRegister.add(ConfigurationExtension::class.java)
        if (extensions != null) {
            for (extension in extensions) {
                if (extension != null) {
                    extensionsToRegister.add(extension)
                }
            }
        }

        val registeredExtensions = AtomicInteger(0)
        for (extension in extensionsToRegister) {
            EventHub.shared.registerExtension(extension) {
                if (registeredExtensions.incrementAndGet()
                    == extensionsToRegister.size
                ) {
                    EventHub.shared.start()
                    try {
                        completionCallback?.call(null)
                    } catch (ex: Exception) { }
                }
            }
        }
    }

    @VisibleForTesting
    fun reset() {
        initializeCalled.set(false)
        setApplicationCalled.set(false)

        lifecycleTracker?.let {
            it.reset()
        }
    }
}
