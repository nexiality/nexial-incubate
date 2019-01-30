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
 *
 */

package org.nexial.core.service

import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.RandomStringUtils.randomAlphanumeric
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.StringUtils.EMPTY
import org.apache.commons.lang3.SystemUtils.*
import org.apache.poi.xssf.usermodel.XSSFCell
import org.json.JSONObject
import org.nexial.commons.utils.DateUtility
import org.nexial.commons.utils.EnvUtils
import org.nexial.core.NexialConst.*
import org.nexial.core.NexialConst.Data.TEST_LOG_PATH
import org.nexial.core.NexialConst.Project.*
import org.nexial.core.excel.ExcelConfig.COL_IDX_PARAMS_END
import org.nexial.core.excel.ExcelConfig.COL_IDX_PARAMS_START
import org.nexial.core.model.ExecutionContext
import org.nexial.core.model.ExecutionDefinition
import org.nexial.core.model.ExecutionSummary.ExecutionLevel
import org.nexial.core.model.ExecutionSummary.ExecutionLevel.EXECUTION
import org.nexial.core.model.NestedScreenCapture
import org.nexial.core.model.TestStep
import org.nexial.core.utils.ConsoleUtils
import org.nexial.core.utils.ExecUtils
import org.springframework.context.support.ClassPathXmlApplicationContext
import java.io.File
import java.io.File.separator
import java.io.IOException
import java.sql.Timestamp
import java.util.*
import kotlin.collections.ArrayList

object SQLiteManager {
    private const val ID_LENGTH: Int = 16
    private const val SPRING_DATABASE_CONTEXT = "classpath:/nexial-database.xml"
    private val TEMP_DB_PATH = StringUtils.appendIfMissing(JAVA_IO_TMPDIR, separator) + "nexialSQLiteQuery"

    private val springContext = ClassPathXmlApplicationContext(SPRING_DATABASE_CONTEXT)
    private val sqliteConfig: SQLiteConfig = springContext.getBean("sqliteConfig", SQLiteConfig::class.java)

    private val fileQueue: Queue<File> = LinkedList<File>()
    private var insertionMode = true

    private fun generateTables() = sqliteConfig.sqlCreateStatements.forEach { sqliteConfig.execute(it) }

    private fun getInsertStatement(insertQuery: String) = sqliteConfig.sqlInsertStatements[insertQuery]

    @JvmStatic
    fun get() = randomAlphanumeric(ID_LENGTH)

    @JvmStatic
    fun main(args: Array<String>) {
        val url = StringUtils.appendIfMissing("template", "/") + DEF_DATABASE_NAME + DB_FILE_SUFFIX
        if (File(url).exists()) FileUtils.deleteQuietly(File(url))

        setupSQLite(url)
        generateTables()
    }

    fun setupSQLite(url: String) {
        sqliteConfig.dataSource.url = String.format(sqliteConfig.dataSource.url, url)
        sqliteConfig.dataSource.config.enforceForeignKeys(true)
    }

    fun copyDatabase(url: String) {
        if (File(url).exists()) {
            FileUtils.deleteQuietly(File(url))
        }
        val file = this.javaClass.classLoader.getResource(DEF_DATABASE_NAME + DB_FILE_SUFFIX).file
        FileUtils.copyFile(File(file), File(url))

    }

    fun setupDB(runId: String, outPath: String): String {
        val outPath = StringUtils.defaultIfBlank(outPath, System.getProperty(OPT_DEF_OUT_DIR))
        val outDbPath = StringUtils.defaultIfBlank(System.getProperty(OPT_DEF_DATABASE_DIR), outPath)
        val dbName = StringUtils.defaultIfBlank(System.getProperty(OPT_DEF_DATABASE_NAME), DEF_DATABASE_NAME)

        val url = StringUtils.appendIfMissing(outDbPath, separator) + runId + separator + dbName + DB_FILE_SUFFIX

        SQLiteManager.setupSQLite(url)
        SQLiteManager.copyDatabase(url)

        return url
    }

    @JvmStatic
    fun insertExecutionDetails(executionId: String, runId: String, dbLocation: String) {
        val logFile = System.getProperty(TEST_LOG_PATH) + separator + runId +
                      separator + "logs" + separator + "nexial-" + runId + ".log"
        val executionType = System.getProperty(NEXIAL_EXECUTION_TYPE)
        val prefix = StringUtils.defaultString(StringUtils.trim(System.getProperty(OPT_RUN_ID_PREFIX)))

        val list = Arrays.asList<Any>(executionId, runId, dbLocation, logFile, EMPTY, executionType, prefix)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_EXECUTION"), list)
    }

    fun insertExecutionEnvDetails(executionId: String) {
        val runtime = Runtime.getRuntime()
        val runHostOs = "$OS_ARCH $OS_NAME $OS_VERSION"
        val runHost = StringUtils.upperCase(EnvUtils.getHostName())

        val list = Arrays.asList(get(), runHost, USER_NAME, runHostOs, JAVA_VERSION,
                                 ExecUtils.deriveJarManifest(), runtime.freeMemory(),
                                 runtime.availableProcessors(), executionId)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_EXECUTION_ENVIRONMENT"), list)
    }

    private fun insertPlanDetails(planId: String, executionId: String, execDef: ExecutionDefinition): String {
        val planUrl = execDef.project.planPath + execDef.planFilename + ".xlsx"

        val list = Arrays.asList(planId, executionId, execDef.planName, execDef.planSequenceId, planUrl)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_PLAN"), list)
        return planId
    }

    private fun insertScriptDetails(jsonObject: JSONObject, planId: String?,
                                    scriptSeqId: Int, testScriptUrl: String) {

        val list: MutableList<Any> = Arrays.asList(jsonObject.getString("scriptId"),
                                                   jsonObject.getString("script"),
                                                   scriptSeqId, planId,
                                                   jsonObject.getString("executionId"), testScriptUrl)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_SCRIPT"), list)
    }

    fun insertIterationDetails(context: ExecutionContext, jsonObject: JSONObject) {

        val list = Arrays.asList(jsonObject.getString("iterationId"), jsonObject.getString("name"),
                                 jsonObject.getString("scriptId"), context.testScript.file.absolutePath,
                                 jsonObject.getInt("iterationSeqId"))
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_ITERATION"), list)
    }

    fun insertScenarioDetails(jsonObject: JSONObject) {
        // todo scenarioURL is to be added currently hard coded
        val list = Arrays.asList(jsonObject.getString("scenarioId"), jsonObject.getString("name"),
                                 jsonObject.getString("iterationId"), jsonObject.getInt("scenarioSeqId"),
                                 "scenarioURL")
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_SCENARIO"), list)
    }

    fun insertActivityDetails(jsonObject: JSONObject) {
        val list = Arrays.asList(jsonObject.getString("activityId"), jsonObject.getString("name"),
                                 jsonObject.getString("scenarioId"), jsonObject.getInt("activitySeqId"))
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_ACTIVITY"), list)
    }

    private fun insertStepDetails(testStep: TestStep) {
        val totalParams = 5
        val params = testStep.params
        val testStepRow = testStep.row

        val paramOutput = getParamOutputList(testStepRow, testStep.linkableParams)
        for (index in params.size until totalParams) {
            params.add(index, "")
        }

        val objects = Arrays.asList(testStep.stepId, testStep.activityId, testStepRow[1], testStepRow[2],
                                    testStepRow[3], params[0], params[1], params[2], params[3], params[4],
                                    paramOutput[0], paramOutput[1], paramOutput[2], paramOutput[3],
                                    paramOutput[4], testStepRow[9].toString(), testStepRow[13].toString(),
                                    testStepRow[14].toString(), testStep.rowIndex + 1, testStepRow[12].toString())
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_STEPS"), objects)
    }

    private fun insertStepLinks(stepId: String, label: String, message: String, link: String) {
        val list = Arrays.asList<Any>(get(), stepId, label, message, link)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_STEP_LINKS"), list)
    }

    private fun insertLogs(stepId: String, logInfo: String) {
        val list = Arrays.asList<Any>(get(), stepId, logInfo)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_LOGS"), list)
    }

    private fun insertTagInfo(executionId: String) {
        val list = Arrays.asList<Any>(get(), executionId, "tagName")
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_TAG_INFO"), list)
    }

    fun insertIterationData(iterationId: String, key: String, value: String) {
        val list = Arrays.asList<Any>(get(), key, value, iterationId)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_ITERATION_DATA"), list)
    }

    fun insertExecutionData(level: ExecutionLevel, jsonObject: JSONObject) {
        val startTime = DateUtility.format(jsonObject.getLong("startTime"))
        val endTime = DateUtility.format(jsonObject.getLong("endTime"))
        val list = Arrays.asList(get(), startTime, endTime, jsonObject.getInt("steps"),
                                 jsonObject.getInt("pass"), jsonObject.getInt("fail"),
                                 jsonObject.getInt("warnCount"), jsonObject.getInt("executed"),
                                 jsonObject.getBoolean("failedFast"), jsonObject.getString("levelId"), level)
        formatAndSaveQuery(getInsertStatement("SQL_INSERT_EXECUTION_DATA"), list)
    }

    fun insertExecutionMetaData(level: ExecutionLevel, scopeId: String, refData: JSONObject) {
        if (refData.keySet().size == 0) return

        for (key in refData.keySet()) {
            val creationTime = Timestamp(Date().time)

            val list = Arrays.asList(get(), key, refData.get(key), DateUtility.format(creationTime.time),
                                     scopeId, level)
            formatAndSaveQuery(getInsertStatement("SQL_INSERT_EXECUTION_META_DATA"), list)
        }
    }

    @JvmStatic
    fun updateExecutionLogs(logs: Map<String, String>, isOutputToCloud: Boolean) {
        var executionLogUrl = ""
        var logFiles = StringUtils.removeStart(StringUtils.removeEnd(logs.values.toString(), "]"), "[")
        val executionId = System.getProperty(NEXIAL_EXECUTION_ID)
        if (isOutputToCloud) {
            executionLogUrl = logFiles
            logFiles = ""
        }

        val sqlQuery = "UPDATE Execution SET ExecutionLogUrl = \"" + executionLogUrl + "\", " +
                       "LogFile = \"" + logFiles + "\" WHERE Id = \"" + executionId + "\";"
        writeQueryToFile(sqlQuery)
    }

    fun addPlanAndScriptDetails(jsonObject: JSONObject, execDef: ExecutionDefinition) {
        val executionId: String = jsonObject.getString("executionId");
        val planId = execDef.planId
        var scriptSeq = 1
        if (StringUtils.equals(System.getProperty(NEXIAL_EXECUTION_TYPE), NEXIAL_EXECUTION_TYPE_PLAN)) {
            scriptSeq = execDef.planSequence
            if (scriptSeq == 1) {
                insertPlanDetails(planId, executionId, execDef)
            }
        }
        insertScriptDetails(jsonObject, planId, scriptSeq, execDef.testScript)
    }

    fun addExecutionDetails(json: JSONObject) {
        val executionId = json.getString("levelId")
        insertExecutionData(EXECUTION, json)
        insertExecutionMetaData(EXECUTION, executionId, json.getJSONObject("refData"))
        // todo to be implemented; contains raw data for now
        insertTagInfo(executionId)
        insertionMode = false
    }

    @JvmStatic
    fun updateDatabase(testStep: TestStep, context: ExecutionContext) {
        val stepId = testStep.stepId
        insertStepDetails(testStep)

        // for repeat until command
        if (testStep.isCommandRepeater) {
            val commandRepeater = testStep.commandRepeater
            for (index in 0 until commandRepeater.stepCount) {
                val testStep1 = commandRepeater.steps[index]
                testStep1.activityId = testStep.activityId
                insertStepDetails(testStep1)
            }
        }

        // logs and step links
        testStep.nestedTestResults.forEach { nestedMessage ->
            if (nestedMessage is NestedScreenCapture) {
                insertStepLinks(stepId, nestedMessage.label, nestedMessage.message, nestedMessage.link)
            } else {
                insertLogs(stepId, nestedMessage.message)
            }
        }

        val linkableParams = testStep.linkableParams
        for (i in COL_IDX_PARAMS_START until COL_IDX_PARAMS_END) {
            val paramIdx = i - COL_IDX_PARAMS_START
            if (CollectionUtils.size(linkableParams) <= paramIdx) {
                break
            }
            val link = if (CollectionUtils.size(linkableParams) > paramIdx) linkableParams[paramIdx] else null
            if (link != null && StringUtils.isNotBlank(link)) {
                insertStepLinks(stepId, context.replaceTokens(testStep.params[paramIdx]), "", link)
            }
        }
    }

    private fun getParamOutputList(row: List<XSSFCell>, linkableParams: List<String>): List<String> {
        val list = ArrayList<String>()
        var num = 0;
        for (index in 0..5) {
            val value = row[4 + index].toString()
            if (!StringUtils.startsWith(value, "HYPERLINK")) {
                list.add(value)
            } else {
                list.add(linkableParams[num++])
            }
        }
        return list
    }

    private fun formatAndSaveQuery(sqliteQuery: String?, params: List<Any>) {
        var query = StringUtils.substringBeforeLast(sqliteQuery, "(") + "("
        var sqliteParams = ""
        for (i in params.indices) {
            var obj: Any? = params[i]

            if (obj is String) {
                obj = obj.replace("\"", "'")
            } else if (obj == null) {
                obj = ""
            }

            sqliteParams = "$sqliteParams\"$obj\""
            if (i != params.size - 1) sqliteParams += ","
        }
        query = "$query$sqliteParams);"
        writeQueryToFile(query)
    }

    @Synchronized
    private fun writeQueryToFile(sql_query: String) {
        val runId = System.getProperty(OPT_RUN_ID)
        val path = TEMP_DB_PATH + separator + runId + separator + System.currentTimeMillis() + ".sql"

        try {
            val file = File(path)
            FileUtils.write(file, sql_query, DEF_FILE_ENCODING)
            fileQueue.add(file)
        } catch (e: IOException) {
            ConsoleUtils.log("Unable to write into file due to ${e.message}")
        }
    }

    @JvmStatic
    fun executeSQLs() {
        while (true) {
            if (fileQueue.isEmpty()) {
                if (!insertionMode) break
                Thread.sleep(1000)
                continue
            }
            val file = fileQueue.poll()
            var sql: String? = null
            try {
                sql = FileUtils.readFileToString(file, DEF_FILE_ENCODING)
                sqliteConfig.execute(sql)
            } catch (e: Exception) {
                ConsoleUtils.log("Unable to execute query $sql due to ${e.message}")
                continue
            } finally {
                FileUtils.deleteQuietly(file)
            }
        }
    }
}

class WatcherThread : Thread() {
    override fun run() = SQLiteManager.executeSQLs()
}