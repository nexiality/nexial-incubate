/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nexial.core.service

import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.BooleanUtils
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.json.JSONObject
import org.nexial.core.ExecutionThread
import org.nexial.core.NexialConst.DEF_FILE_ENCODING
import org.nexial.core.NexialConst.Data.*
import org.nexial.core.NexialConst.OPT_RUN_ID
import org.nexial.core.model.*
import org.nexial.core.model.ExecutionEvent.*
import org.nexial.core.model.ExecutionSummary.ExecutionLevel
import org.nexial.core.model.ExecutionSummary.ExecutionLevel.*
import org.nexial.core.service.EventUtils.postfix
import org.nexial.core.service.EventUtils.storageLocation
import org.nexial.core.utils.TrackTimeLogs
import java.io.File
import java.io.File.separator
import java.text.SimpleDateFormat
import java.util.*

object EventTracker {
    private val eventFileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS")
    var testData: SortedMap<String, String> = TreeMap<String, String>()

    private val enableUniversalTracking =
        BooleanUtils.toBoolean(System.getProperty("nexial.universalTracking", "false"))

    fun getStorageLocation() = EventUtils.storageLocation

    fun getExtension() = EventUtils.postfix

    fun track(event: NexialEvent) {
        if (event is NexialCompleteEvent) {
            write(event.eventName, formatJson(event.json()))
        }

        trackEvents(event)
    }

    fun track(env: NexialEnv) = write("env", env.json())

    private fun formatJson(json: String): String {
        val jsonObject = JSONObject(json)

        jsonObject.remove("levelId")
        jsonObject.remove("warnCount")
        jsonObject.remove("failedFast")
        jsonObject.remove("refData")
        return jsonObject.toString()
    }

    private fun write(type: String, content: String) {
        if (enableUniversalTracking) {
            val file = File(storageLocation +
                            RandomStringUtils.randomAlphabetic(10) + "." +
                            eventFileDateFormat.format(Date()) + "." +
                            type + postfix)
            FileUtils.forceMkdirParent(file)
            FileUtils.write(file, content, DEF_FILE_ENCODING)
        }
    }

    private fun trackEvents(event: NexialEvent) {
        val context = ExecutionThread.get()
        val eventName = event.eventName
        val startTime = event.startTime
        val endTime = event.endTime
        val trackTimeLogs = context?.trackTimeLogs ?: TrackTimeLogs()
        val jsonObject = JSONObject(event.json())

        when (eventName) {
            ExecutionComplete.eventName -> {
                if (trackEvent(TRACK_EXECUTION)) {
                    trackTimeLogs.trackExecutionLevels(System.getProperty(OPT_RUN_ID), startTime, endTime, "Execution")
                }
                SQLiteManager.addExecutionDetails(jsonObject)
            }

            ScriptComplete.eventName    -> {
                val scriptEvent = event as NexialScriptCompleteEvent
                val scriptId = scriptEvent.levelId

                if (trackEvent(context, TRACK_SCRIPT)) {
                    val label: String = asLabel(scriptEvent.script)
                    trackTimeLogs.trackExecutionLevels(label, startTime, endTime, "Script")
                }
                SQLiteManager.insertExecutionData(SCRIPT, jsonObject)
                SQLiteManager.insertExecutionMetaData(SCRIPT, scriptId, jsonObject.getJSONObject("refData"))
            }

            IterationComplete.eventName -> {
                trackTimeLogs.forcefullyEndTracking()
                val iterEvent = event as NexialIterationCompleteEvent
                val iteration = "" + iterEvent.iteration
                val iterationId = iterEvent.levelId

                if (trackEvent(context, TRACK_ITERATION)) {
                    val label: String = asLabel(iterEvent.script) + "-" +
                                        StringUtils.rightPad("0", 3 - iteration.length) + iteration
                    trackTimeLogs.trackExecutionLevels(label, startTime, endTime, "Iteration")
                }

                SQLiteManager.insertExecutionData(ExecutionLevel.ITERATION, jsonObject)
                SQLiteManager.insertExecutionMetaData(ExecutionLevel.ITERATION, iterationId,
                                                      jsonObject.getJSONObject("refData"))

                testData.forEach { (key, value) -> SQLiteManager.insertIterationData(iterationId, key, value) }
            }

            ScenarioComplete.eventName  -> {
                val scenarioEvent = event as NexialScenarioCompleteEvent
                if (trackEvent(context, TRACK_SCENARIO)) {
                    trackTimeLogs.trackExecutionLevels(scenarioEvent.scenario,
                                                       startTime, endTime, "Scenario")
                }

                SQLiteManager.insertExecutionData(SCENARIO, jsonObject)
                SQLiteManager.insertExecutionMetaData(SCENARIO, scenarioEvent.levelId,
                                                      jsonObject.getJSONObject("refData"))

            }

            ActivityComplete.eventName  -> {
                SQLiteManager.insertExecutionData(ACTIVITY, jsonObject)
            }

            ExecutionStart.eventName    -> {
                val runId = System.getProperty(OPT_RUN_ID)
                var executionId = (event as NexialExecutionStartEvent).executionId

                //setting sqlite configurations
                val url = SQLiteManager.setupDB(runId, event.outPath)

                SQLiteManager.insertExecutionDetails(executionId, runId, url)
                SQLiteManager.insertExecutionEnvDetails(executionId)

            }
            ScriptStart.eventName       -> {
                SQLiteManager.addPlanAndScriptDetails(jsonObject, context.execDef)
            }

            IterationStart.eventName    -> {
                SQLiteManager.insertIterationDetails(context, jsonObject)
            }
            ScenarioStart.eventName     -> {
                SQLiteManager.insertScenarioDetails(jsonObject)
            }
            ActivityStart.eventName     -> {
                SQLiteManager.insertActivityDetails(jsonObject)
            }
        }
    }

    private fun asLabel(script: String) =
        StringUtils.substringBeforeLast(StringUtils.substringAfterLast(StringUtils.replace(script, "\\", "/"), "/"),
                                        ".")

    private fun trackEvent(context: ExecutionContext, trackId: String) =
        BooleanUtils.toBoolean(context.getStringData(trackId))

    private fun trackEvent(trackId: String) = BooleanUtils.toBoolean(System.getProperty(trackId))
}

object EventUtils {
    internal val storageLocation = StringUtils.appendIfMissing(SystemUtils.getJavaIoTmpDir().absolutePath, separator) +
                                   Hex.encodeHexString("Nexial_Event".toByteArray()) + separator
    internal const val postfix = ".json"

    init {
    }
}