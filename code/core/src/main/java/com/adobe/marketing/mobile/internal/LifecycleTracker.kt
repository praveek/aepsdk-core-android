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

package com.adobe.marketing.mobile.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.adobe.marketing.mobile.MobileCore

internal class LifecycleTracker(private val application: Application, private val lifecycleAdditionalContextData: Map<String, String>? = null) : Application.ActivityLifecycleCallbacks {

    var activityCount = 0

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun reset() {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    }

    override fun onActivityStarted(activity: Activity) {
    }

    override fun onActivityResumed(activity: Activity) {
        activityCount++
        if (activityCount == 1) {
            MobileCore.lifecycleStart(lifecycleAdditionalContextData)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        activityCount--
        if (activityCount == 0) {
            MobileCore.lifecyclePause()
        }
    }

    override fun onActivityStopped(activity: Activity) {
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }
}
