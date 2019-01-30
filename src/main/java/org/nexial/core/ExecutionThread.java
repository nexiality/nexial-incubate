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

package org.nexial.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.nexial.commons.logging.LogbackUtils;
import org.nexial.commons.utils.FileUtil;
import org.nexial.core.aws.NexialS3Helper;
import org.nexial.core.excel.Excel;
import org.nexial.core.model.*;
import org.nexial.core.plugins.web.CloudWebTestingPlatform;
import org.nexial.core.reports.ExecutionMailConfig;
import org.nexial.core.service.EventTracker;
import org.nexial.core.service.SQLiteManager;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.ExecutionLogger;

import static org.nexial.core.NexialConst.Data.*;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Project.appendLog;
import static org.nexial.core.model.ExecutionEvent.*;
import static org.nexial.core.model.ExecutionSummary.ExecutionLevel.ITERATION;
import static org.nexial.core.model.ExecutionSummary.ExecutionLevel.SCRIPT;

/**
 * thread-bound test execution to support synchronous and asynchronous test executions.  The main driving force
 * behind this class is to allow {@link Nexial} the flexibility to execute tests either in succession or in
 * parallel, or both.  {@link Nexial} generates a series of {@link ExecutionDefinition} instances from commandline
 * arguments, and these {@link ExecutionDefinition} in turn represents one or more test execution, which may be
 * performed serially or in parallel.  This class runs a set of tests - meaning 1 or more {@link TestScenario}s,
 * with 1 or more {@link TestData} sheets over 1 or more iterations -- perpetually as a separate thread.  However
 * {@link Nexial} as the initiator has the option to wait on thread complete or to launch another set of
 * tests in parallel.  This class will track all the test artifacts such as test scenarios, test steps, test data
 * and test results within its own thread context.  No sharing of such data between parallel test executions.
 * However test results will be consolidated at the end of the entire run.
 *
 * @see Nexial
 * @see ExecutionDefinition
 */
public final class ExecutionThread extends Thread {
    private static final ThreadLocal<ExecutionContext> THREAD_LOCAL = new ThreadLocal<>();

    private ExecutionDefinition execDef;
    private ExecutionSummary executionSummary = new ExecutionSummary();
    private List<File> completedTests = new ArrayList<>();
    private boolean firstUse;
    private boolean lastUse;

    // capture the data after an execution run (all iteration, all scenarios within 1 file)
    private Map<String, Object> intraExecutionData = new HashMap<>();

    public static ExecutionContext get() { return THREAD_LOCAL.get(); }

    public static void set(ExecutionContext context) { THREAD_LOCAL.set(context); }

    public static void unset() {
        ExecutionContext context = THREAD_LOCAL.get();
        if (context != null) {
            context.endScript();
            THREAD_LOCAL.remove();
        }
    }

    public static ExecutionThread newInstance(ExecutionDefinition execDef) {
        ExecutionThread self = new ExecutionThread();
        self.execDef = execDef;
        return self;
    }

    public void setFirstUse(boolean firstUse) { this.firstUse = firstUse;}

    public void setLastUse(boolean lastUse) { this.lastUse = lastUse;}

    @Override
    public void run() {
        if (execDef == null) { throw new RuntimeException("No ExecutionContext instance in current thread context"); }

        String runId = execDef.getRunId();
        LogbackUtils.registerLogDirectory(appendLog(execDef));

        StopWatch ticktock = new StopWatch();
        ticktock.start();

        IterationManager iterationManager = execDef.getTestData().getIterationManager();
        String scriptLocation = execDef.getTestScript();

        ExecutionContext context = MapUtils.isNotEmpty(intraExecutionData) ?
                                   new ExecutionContext(execDef, intraExecutionData) : new ExecutionContext(execDef);
        context.setCurrentActivity(null);

        // in case there were fail-immediate condition from previous script..
        if (context.isFailImmediate()) {
            ConsoleUtils.error("previous test scenario execution failed, and fail-immediate in effect. " +
                               "Hence all subsequent test scenarios will be skipped");
            collectIntraExecutionData(context, 0);
            return;
        }

        if (execDef.isFailFast() && !context.getBooleanData(OPT_LAST_OUTCOME)) {
            if (context.getBooleanData(RESET_FAIL_FAST, DEF_RESET_FAIL_FAST)) {
                // reset and pretend nothing's wrong.  Current script will be executed..
                context.setData(OPT_LAST_OUTCOME, true);
            } else {
                ConsoleUtils.error("previous test scenario execution failed, and current test script is set to " +
                                   "fail-fast.  Hence all subsequent test scenarios will be skipped");
                collectIntraExecutionData(context, 0);
                return;
            }
        }

        int totalIterations = iterationManager.getIterationCount();
        ConsoleUtils.log(runId, "executing " + scriptLocation + " with " + totalIterations + " iteration(s)");

        if (StringUtils.isNotBlank(execDef.getPlanFile())) {
            context.setData(OPT_INPUT_PLAN_FILE, execDef.getPlanFile());
        }

        ExecutionThread.set(context);

        String scriptName =
            StringUtils.substringBeforeLast(
                StringUtils.substringAfterLast(
                    StringUtils.replace(scriptLocation, "\\", "/"), "/"), ".") +
            " (" + totalIterations + ")";
        executionSummary.setName(scriptName);
        executionSummary.setExecutionLevel(SCRIPT);
        executionSummary.setStartTime(System.currentTimeMillis());
        executionSummary.setScriptFile(scriptLocation);
        executionSummary.setDataFile(execDef.getDataFile().getAbsolutePath());
        executionSummary.setIterationTotal(totalIterations);
        executionSummary.setPlanSequence(execDef.getPlanSequence());
        executionSummary.setPlanName(execDef.getPlanName());
        executionSummary.setPlanFile(execDef.getPlanFile());

        final String scriptId = SQLiteManager.get();
        executionSummary.setId(scriptId);
        EventTracker.INSTANCE.track(new NexialScriptStartEvent(scriptId, scriptName));

        for (int currIteration = 1; currIteration <= totalIterations; currIteration++) {
            // SINGLE THREAD EXECUTION WITHIN FOR LOOP!

            int iteration = iterationManager.getIterationRef(currIteration - 1);
            Excel testScript = null;
            boolean allPass = true;

            // we need to infuse "between" #default and whatever data sheets is assigned for this test script
            execDef.infuseIntraExecutionData(intraExecutionData);

            ExecutionSummary iterSummary = new ExecutionSummary();
            iterSummary.setName(currIteration + " of " + totalIterations);
            iterSummary.setExecutionLevel(ITERATION);
            iterSummary.setStartTime(System.currentTimeMillis());
            iterSummary.setScriptFile(scriptLocation);
            iterSummary.setIterationIndex(currIteration);
            iterSummary.setIterationTotal(totalIterations);

            try {
                testScript = ExecutionInputPrep.prep(runId, execDef, iteration, currIteration);
                iterSummary.setTestScript(testScript.getOriginalFile());
                context.useTestScript(testScript);

                context.startIteration(currIteration, firstUse);
                final String iterationId = SQLiteManager.get();
                context.setIterationId(iterationId);
                iterSummary.setId(iterationId);

                EventTracker.INSTANCE.track(new NexialIterationStartEvent(iterationId, iterSummary.getName(),
                                                                          scriptId, currIteration));

                ExecutionLogger logger = context.getLogger();
                logger.log(context, "executing iteration #" + currIteration + " of " + totalIterations +
                                    "; Iteration Index " + iteration);
                allPass = context.execute();

                onIterationComplete(context, iterSummary, currIteration);
                if (shouldStopNow(context, allPass)) { break; }
            } catch (Throwable e) {
                onIterationException(context, iterSummary, currIteration, e);
                if (shouldStopNow(context, allPass)) { break; }
            } finally {
                context.setData(ITERATION_ENDED, true);
                context.setCurrentActivity(null);

                File testScriptFile = null;
                if (testScript == null) {
                    // possibly the script prep/parsing routine failed (ie ExecutionInputPrep.prep()), but the output
                    // file might already generated. If so then we should use the generated output file and generate
                    // output (as much as possible).
                    String scriptOutputFullPath = context.getStringData(OPT_INPUT_EXCEL_FILE);
                    if (StringUtils.isNotBlank(scriptOutputFullPath)) {testScriptFile = new File(scriptOutputFullPath);}
                } else {
                    testScriptFile = testScript.getFile();
                    // sync #data sheet with context
                    ExecutionResultHelper.updateOutputDataSheet(context, testScript);
                }

                String testScriptFileName = "UNKNOWN TEST SCRIPT";

                if (FileUtil.isFileReadable(testScriptFile)) {
                    testScriptFileName = testScriptFile.getName();

                    // now the execution for this iteration is done. We'll add new execution summary page to its output.
                    iterSummary.setFailedFast(context.isFailFast());
                    iterSummary.setEndTime(System.currentTimeMillis());
                    iterSummary.aggregatedNestedExecutions(context);

                    if (testScript != null) {
                        iterSummary.generateExcelReport(testScript);
                    } else {
                        iterSummary.generateExcelReport(testScriptFile);
                    }

                    EventTracker.INSTANCE.track(
                        new NexialIterationCompleteEvent(scriptLocation, currIteration, iterSummary));
                    executionSummary.addNestSummary(iterSummary);

                    // report status at iteration level
                    CloudWebTestingPlatform.reportCloudBrowserStatus(context, iterSummary, IterationComplete);

                    // try {
                    // save it before use it
                    // testScript.save();
                    // testScript = new Excel(testScriptFile, false, true);
                    // } catch (IOException e) {
                    //     ConsoleUtils.error("Error saving execution output: " + e.getMessage());
                    // }

                    if (isAutoOpenResult()) {
                        String spreadsheetExe = context.getStringData(SPREADSHEET_PROGRAM, DEF_SPREADSHEET);
                        System.setProperty(SPREADSHEET_PROGRAM, spreadsheetExe);

                        if (StringUtils.equals(spreadsheetExe, SPREADSHEET_PROGRAM_WPS)) {
                            if (!context.hasData(WPS_EXE_LOCATION)) {
                                // lightweight: resolve now to save time later
                                context.setData(WPS_EXE_LOCATION, Excel.resolveWpsExecutablePath());
                            }
                            if (context.hasData(WPS_EXE_LOCATION)) {
                                System.setProperty(WPS_EXE_LOCATION, context.getStringData(WPS_EXE_LOCATION));
                            }
                        }

                        Excel.openExcel(testScriptFile);
                    }

                    completedTests.add(testScriptFile);
                }

                collectIntraExecutionData(context, currIteration);
                ExecutionMailConfig.configure(context);

                context.endIteration();

                MemManager.recordMemoryChanges(testScriptFileName + " completed");

                context.setData(ITERATION_ENDED, false);
            }
        }

        onScriptComplete(context, executionSummary, iterationManager, ticktock);

        // handling onExecutionComplete
        if (lastUse) {
            CloudWebTestingPlatform.reportCloudBrowserStatus(context, executionSummary, ExecutionComplete);
            context.getExecutionEventListener().onExecutionComplete();
        }

        ExecutionThread.unset();
        MemManager.recordMemoryChanges(scriptName + " completed");
    }

    public ExecutionSummary getExecutionSummary() { return executionSummary; }

    public List<File> getCompletedTests() { return completedTests; }

    public ExecutionDefinition getExecDef() { return execDef; }

    protected Map<String, Object> getIntraExecutionData() { return intraExecutionData; }

    protected void setIntraExecutionData(Map<String, Object> intraExecutionData) {
        this.intraExecutionData = intraExecutionData;
    }

    protected void collectIntraExecutionData(ExecutionContext context, int completeIteration) {
        if (context == null) { return; }

        context.fillIntraExecutionData(intraExecutionData);
        // override, if found, previous "last completed iteration count"
        intraExecutionData.put(LAST_ITERATION, completeIteration);
    }

    protected boolean shouldStopNow(ExecutionContext context, boolean allPass) {
        if (context == null) { return true; }

        ExecutionLogger logger = context.getLogger();
        if (!allPass && context.isFailFast()) {
            logger.log(context, "failure found, fail-fast in effect - test execution will stop now.");
            return true;
        }

        if (context.isFailImmediate()) {
            logger.log(context, "fail-immediate in effect - test execution will stop now.");
            return true;
        }

        if (context.isEndImmediate()) {
            logger.log(context, "test execution ending due to EndIf() flow control activated.");
            return true;
        }

        return false;
    }

    protected void onIterationException(ExecutionContext context,
                                        ExecutionSummary iterationSummary,
                                        int iteration,
                                        Throwable e) {
        if (context == null) { context = ExecutionThread.get(); }

        if (e != null) {
            iterationSummary.setError(e);
            context.setFailImmediate(true);
        }

        String testScript = null;
        if (context != null) {
            if (context.getTestScript() != null) {
                testScript = context.getTestScript().getFile().getAbsolutePath();
            } else {
                String testScriptFullpath = context.getStringData(OPT_INPUT_EXCEL_FILE);
                if (FileUtil.isFileReadable(testScriptFullpath)) { testScript = testScriptFullpath; }
            }
        }

        if (StringUtils.isBlank(testScript)) { testScript = execDef.getTestScript() + " (unparseable?)"; }

        String runId;
        if (context == null) {
            runId = "UNKNOWN";
        } else {
            runId = context.getRunId();
            if (CollectionUtils.isNotEmpty(context.getTestScenarios())) {
                context.getTestScenarios().forEach(testScenario -> {
                    if (testScenario != null && testScenario.getExecutionSummary() != null) {
                        iterationSummary.addNestSummary(testScenario.getExecutionSummary());
                    }
                });
            }
        }

        ConsoleUtils.error(runId,
                           "\n" +
                           "/-TEST FAILED!!-----------------------------------------------------------------\n" +
                           "| Test Output:    " + testScript + "\n" +
                           "| Iteration:      " + iteration + "\n" +
                           "\\-------------------------------------------------------------------------------\n" +
                           (e != null ? "» Error:          " + e.getMessage() : ""),
                           (e instanceof AssertionError) ? null : e);
    }

    protected void onIterationComplete(ExecutionContext context, ExecutionSummary iterationSummary, int iteration) {
        List<TestScenario> testScenarios = context.getTestScenarios();
        if (CollectionUtils.isEmpty(testScenarios)) { return; }

        context.removeData(BREAK_CURRENT_ITERATION);

        final int[] total = {0};
        final int[] failCount = {0};

        testScenarios.forEach(testScenario -> {
            ExecutionSummary executionSummary = testScenario.getExecutionSummary();
            iterationSummary.addNestSummary(executionSummary);
            total[0] += executionSummary.getTotalSteps();
            failCount[0] += executionSummary.getFailCount();
        });

        ConsoleUtils.log(context.getRunId(),
                         "\n" +
                         "/-TEST COMPLETE-----------------------------------------------------------------\n" +
                         "| Test Output:    " + context.getTestScript().getFile() + "\n" +
                         "| Iteration:      " + iteration + "\n" +
                         "\\-------------------------------------------------------------------------------\n" +
                         "» Execution Time: " + (context.getEndTimestamp() - context.getStartTimestamp()) + " ms\n" +
                         "» Test Steps:     " + total[0] + "\n" +
                         "» Error(s):       " + failCount[0] + "\n\n\n");
        MemManager.gc(this);
    }

    protected void onScriptComplete(ExecutionContext context,
                                    ExecutionSummary summary,
                                    IterationManager iterationManager,
                                    StopWatch ticktock) {
        ticktock.stop();
        summary.setEndTime(System.currentTimeMillis());
        summary.aggregatedNestedExecutions(context);
        EventTracker.INSTANCE.track(new NexialScriptCompleteEvent(summary.getScriptFile(), summary));

        CloudWebTestingPlatform.reportCloudBrowserStatus(context, summary, ScriptComplete);

        StringBuilder cloudOutputBuffer = new StringBuilder();

        if (summary.getTestScript() != null) { handleTestScript(context, summary);}

        summary.getNestedExecutions().forEach(nested -> {
            handleTestScript(context, nested);
            cloudOutputBuffer.append("» Iteration ").append(nested.getName()).append(": ")
                             .append(nested.getTestScriptLink()).append("\n");
        });

        ConsoleUtils.log(context.getRunId(),
                         "\n" +
                         "/-TEST COMPLETE-----------------------------------------------------------------\n" +
                         "| Test Script:    " + execDef.getTestScript() + "\n" +
                         "\\-------------------------------------------------------------------------------\n" +
                         "» Execution Time: " + (ticktock.getTime()) + " ms\n" +
                         "» Iterations:     " + iterationManager + "\n" +
                         "» Test Steps:     " + summary.getTotalSteps() + "\n" +
                         "» Passed:         " + summary.getPassCount() + "\n" +
                         "» Error(s):       " + summary.getFailCount() + "\n" +
                         //"» Warnings:       " + summary.getWarnCount() + "\n" +
                         StringUtils.defaultIfBlank(cloudOutputBuffer.toString(), "") + "\n\n");

        context.getExecutionEventListener().onScriptComplete();

        if (context.hasData(LAST_PLAN_STEP)) {
            System.setProperty(LAST_PLAN_STEP, context.getStringData(LAST_PLAN_STEP, DEF_LAST_PLAN_STEP));
        }

        if (MapUtils.isNotEmpty(intraExecutionData)) { intraExecutionData.remove(LAST_ITERATION); }

        // we don't want the reference data from this script to taint the next
        context.clearScenarioRefData();
        context.clearScriptRefData();

        MemManager.gc(execDef);
    }

    private static void handleTestScript(ExecutionContext context, ExecutionSummary execution) {
        // already done?
        if (StringUtils.isNotBlank(execution.getTestScriptLink()) || execution.getTestScript() == null) { return; }

        File testScript = execution.getTestScript();
        if (context.isOutputToCloud()) {
            try {
                NexialS3Helper otc = context.getOtc();
                // when saving test output to cloud, we might NOT want to remove it locally - esp. when open-result is on
                String testScriptUrl = otc.importFile(testScript, !isAutoOpenResult());
                execution.setTestScriptLink(testScriptUrl);
            } catch (IOException e) {
                ConsoleUtils.error(toCloudIntegrationNotReadyMessage(testScript.toString()) + ": " + e.getMessage());
            }
        } else {
            execution.setTestScriptLink(testScript.getAbsolutePath());
        }
    }
}
