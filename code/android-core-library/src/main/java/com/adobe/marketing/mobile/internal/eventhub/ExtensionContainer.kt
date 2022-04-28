/*
  Copyright 2022 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.internal.eventhub

import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.Extension
import com.adobe.marketing.mobile.ExtensionApi
import com.adobe.marketing.mobile.ExtensionError
import com.adobe.marketing.mobile.ExtensionErrorCallback
import com.adobe.marketing.mobile.ExtensionListener
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.MobileCore
import java.util.concurrent.ExecutorService
import kotlin.Exception

internal class ExtensionRuntime() : ExtensionApi() {

    companion object {
        const val LOG_TAG = "ExtensionApi"
    }

    var extension: Extension? = null
        set(value) {
            field = value
            extensionName = value?.name
            extensionFriendlyName = value?.friendlyName
            extensionVersion = value?.version
        }

    // Fetch these values on initialization
    var extensionName: String? = null
        private set
    var extensionFriendlyName: String? = null
        private set
    var extensionVersion: String? = null
        private set

    override fun <T : ExtensionListener?> registerEventListener(
        eventType: String?,
        eventSource: String?,
        extensionListenerClass: Class<T>?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {
        return false
    }

    override fun <T : ExtensionListener?> registerWildcardListener(
        extensionListenerClass: Class<T>?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {
        return false
    }

    override fun setSharedEventState(
        state: MutableMap<String, Any?>?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {
        try {
            return EventHub.shared.setSharedState(SharedStateType.XDM, extensionName, state, event, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(),
                    String.format("Failed to set shared state at EventID: %s. %s", event?.uniqueIdentifier, ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }

        return false
    }

    override fun setXDMSharedEventState(
        state: MutableMap<String, Any?>?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Boolean {
        try {
            return EventHub.shared.setSharedState(SharedStateType.XDM, extensionName, state, event, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(),
                    String.format("Failed to set XDM shared state at EventID: %s. %s", event?.uniqueIdentifier, ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }

        return false
    }

    override fun clearSharedEventStates(errorCallback: ExtensionErrorCallback<ExtensionError>?): Boolean {
        try {
            EventHub.shared.clearSharedState(SharedStateType.STANDARD, extensionName, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(), String.format("Failed to clear shared state. %s", ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }

        return false
    }

    override fun clearXDMSharedEventStates(errorCallback: ExtensionErrorCallback<ExtensionError>?): Boolean {
        try {
            EventHub.shared.clearSharedState(SharedStateType.XDM, extensionName, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(), String.format("Failed to clear XDM shared state. %s", ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }

        return false
    }

    override fun getSharedEventState(
        stateName: String?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Map<String, Any?>? {
        try {
            return EventHub.shared.getSharedState(SharedStateType.STANDARD, stateName, event, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(),
                    String.format("Failed to get shared state at EventID: %s. %s", event?.uniqueIdentifier, ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }
        return null
    }

    override fun getXDMSharedEventState(
        stateName: String?,
        event: Event?,
        errorCallback: ExtensionErrorCallback<ExtensionError>?
    ): Map<String, Any?>? {
        try {
            return EventHub.shared.getSharedState(SharedStateType.STANDARD, stateName, event, errorCallback)
        } catch (ex: Exception) {
            MobileCore.log(LoggingMode.ERROR, getTag(),
                    String.format("Failed to get XDM shared state at EventID: %s. %s", event?.uniqueIdentifier, ex))
            errorCallback?.error(ExtensionError.UNEXPECTED_ERROR)
        }

        return null
    }

    override fun unregisterExtension() {
        if (extension == null) {
            return
        }
        EventHub.shared.unregisterExtension(extension?.javaClass) {}
    }

    private fun getTag(): String {
        if (extension == null) {
            return LOG_TAG
        }
        return String.format("%s-%s", extensionName, extensionVersion)
    }
}

internal class ExtensionContainer constructor(
    private val extensionClass: Class<out Extension>,
    private val extensionRuntime: ExtensionRuntime,
    private val sharedStateManagers: Map<SharedStateType, SharedStateManager>,
    private val taskExecutor: ExecutorService,
    callback: (EventHubError) -> Unit
) {

    val sharedStateName: String?
        get() = extensionRuntime.extensionName

    val friendlyName: String?
        get() = extensionRuntime.extensionFriendlyName

    val version: String?
        get() = extensionRuntime.extensionVersion

    init {
        taskExecutor.submit {
            val extension = extensionClass.initWith(extensionRuntime)
            if (extension == null) {
                callback(EventHubError.ExtensionInitializationFailure)
                return@submit
            }

            if (extension.name == null) {
                callback(EventHubError.InvalidExtensionName)
                return@submit
            }

            // We set this circular reference because ExtensionApi exposes an API to get the underlying extension.
            extensionRuntime.extension = extension
            callback(EventHubError.None)
        }
    }

    fun shutdown() {
        taskExecutor.run {
            extensionRuntime.extension?.onUnregistered()
        }
        taskExecutor.shutdown()
    }

    /**
     * Sets the shared state for the extension at [version] as [data] and type [sharedStateType]
     * If [data] is null, the shared state being set is regarded as pending.
     * If a pending shared state at [version] already exists, an attempt will be made to update it.
     *
     * @param sharedStateType the type of the shared state that need to be set
     * @param data the content that the shared state needs to be populated with
     * @param version the version of the shared state to be set
     * @return true - if a new shared state has been created or updated at [version], false otherwise
     */
    fun setSharedState(
        sharedStateType: SharedStateType,
        data: MutableMap<String, Any?>?,
        version: Int
    ): Boolean {
        val stateManager: SharedStateManager = sharedStateManagers[sharedStateType] ?: return false
        val isPending = (data == null)

        // Attempt to create the state first and then attempt to update it if creation fails.
        return stateManager.createSharedState(data, version, isPending) ||
                stateManager.updateSharedState(data, version, isPending)
    }

    /**
     * Clears the shares states of type [sharedStateType] for this extension.
     *
     * @return true always unless an exception occurs clearing the state
     */
    fun clearSharedState(sharedStateType: SharedStateType): Boolean {
        sharedStateManagers[sharedStateType]?.clearSharedState()
        return true
    }

    /**
     * Gets the shared state of type [sharedStateType] at [version] or the most recent one before [version]
     * if it is unavailable.
     *
     * @param sharedStateType the type of the shared state that need to be retrieved
     * @param version the version of the pending shared state to be retrieved
     * @return shared state at [version] if it exists or the most recent shared state before [version].
     *         null If no state at or before [version] is found, or if the state fetched above is pending.
     */
    fun getSharedState(
        sharedStateType: SharedStateType,
        version: Int
    ): Map<String, Any?>? {

        return sharedStateManagers[sharedStateType]?.getSharedState(version)
    }
}
