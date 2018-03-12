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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import org.nexial.commons.logging.LogbackUtils;
import org.nexial.core.aws.NexialS3Helper;
import org.nexial.core.excel.Excel;
import org.nexial.core.model.*;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.ExecutionLogger;

import static org.nexial.core.NexialConst.Data.*;
import static org.nexial.core.NexialConst.OPT_LAST_OUTCOME;
import static org.nexial.core.NexialConst.Project.appendLog;
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
 * However test reuslts will be consolidated at the end of the entire run.
 *
 * @see Nexial
 * @see ExecutionDefinition
 */
public final class ExecutionThread extends Thread {
    private static final ThreadLocal<ExecutionContext> THREAD_LOCAL = new ThreadLocal<>();

    private ExecutionDefinition execDef;
    private ExecutionSummary executionSummary = new ExecutionSummary();
    private List<File> completedTests = new ArrayList<>();

    // capture the data after an execution run (all iteration, all scenarios within 1 file)
    private Map<String, Object> intraExecutionData = new HashMap<>();

    public static ExecutionContext get() { return THREAD_LOCAL.get(); }

    public static void set(ExecutionContext context) { THREAD_LOCAL.set(context); }

    public static void unset() { THREAD_LOCAL.remove(); }

    public static ExecutionThread newInstance(ExecutionDefinition execDef) {
        ExecutionThread self = new ExecutionThread();
        self.execDef = execDef;
        return self;
    }

    @Override
    public void run() {
        if (execDef == null) { throw new RuntimeException("No ExecutionContext instance in current thread context"); }

        String runId = execDef.getRunId();
        LogbackUtils.registerLogDirectory(appendLog(execDef));

        StopWatch ticktock = new StopWatch();
        ticktock.start();

        IterationManager iterationManager = execDef.getTestData().getIterationManager();
        String testScriptLocation = execDef.getTestScript();

        ExecutionContext context = MapUtils.isNotEmpty(intraExecutionData) ?
                                   new ExecutionContext(execDef, intraExecutionData) : new ExecutionContext(execDef);

        // in case there were fail-immediate condition from previous script..
        if (context.isFailImmediate()) {
            ConsoleUtils.error("previous test scenario execution failed, and fail-immediate in effect. " +
                               "Hence all subsequent test scenarios will be skipped");
            collectIntraExecutionData(context, 0);
            return;
        }

        if (execDef.isFailFast() && !context.getBooleanData(OPT_LAST_OUTCOME)) {
            if (context.getBooleanData(RESET_FAIL_FAST, DEF_RESET_FAIL_FAST)) {
                // reset and pretend nothin's wrong.  Current script will be executed..
                context.setData(OPT_LAST_OUTCOME, true);
            } else {
                ConsoleUtils.error("previous test scenario execution failed, and current test script is set to " +
                                   "fail-fast.  Hence all subsequent test scenarios will be skipped");
                collectIntraExecutionData(context, 0);
                return;
            }
        }

        ConsoleUtils.log(runId, "executing " + testScriptLocation + ". " + iterationManager);

        ExecutionThread.set(context);

        int totalIterations = iterationManager.getIterationCount();

        String scriptName =
            StringUtils.substringBeforeLast(
                StringUtils.substringAfterLast(
                    StringUtils.replace(testScriptLocation, "\\", "/"), "/"), ".") +
            " (" + totalIterations + ")";
        executionSummary.setName(scriptName);
        executionSummary.setExecutionLevel(SCRIPT);
        executionSummary.setStartTime(System.currentTimeMillis());

        for (int currIteration = 1; currIteration <= totalIterations; currIteration++) {
            // SINGLE THREAD EXECUTION WITHIN FOR LOOP!

            int iteration = iterationManager.getIterationRef(currIteration - 1);
            File testScript = null;
            boolean allPass = true;

            // we need to infuse "between" #default and whatever data sheets is assigned for this test script
            execDef.infuseIntraExecutionData(intraExecutionData);

            ExecutionSummary iterSummary = new ExecutionSummary();
            iterSummary.setName(currIteration + " of " + totalIterations);
            iterSummary.setExecutionLevel(ITERATION);
            iterSummary.setStartTime(System.currentTimeMillis());

            try {
                testScript = ExecutionInputPrep.prep(runId, execDef, iteration, currIteration);
                iterSummary.setTestScript(testScript);
                context.useTestScript(testScript);
                context.setData(CURR_ITERATION, currIteration);

                ExecutionLogger logger = context.getLogger();
                logger.log(context, "executing iteration #" + currIteration +
                                    "; Iteration #" + iteration + " of " + totalIterations);
                allPass = context.execute();

                onIterationComplete(context, iterSummary, currIteration);
                if (shouldStopNow(context, allPass)) { break; }
            } catch (Exception e) {
                onIterationException(context, iterSummary, currIteration, e);
                if (shouldStopNow(context, allPass)) { break; }
            } finally {
                // now the execution for this iteration is done. We'll add new execution summary page to its output.
                iterSummary.setFailedFast(context.isFailFast());
                iterSummary.setEndTime(System.currentTimeMillis());
                iterSummary.aggregatedNestedExecutions(context);
                iterSummary.generateExcelReport(testScript);
                executionSummary.addNestSummary(iterSummary);

                if (testScript != null) {
                    if (isAutoOpenResult()) { Excel.openExcel(testScript); }
                    completedTests.add(testScript);
                }

                collectIntraExecutionData(context, currIteration);
                context.endIteration();

                if (testScript != null) { MemManager.recordMemoryChanges(testScript.getName() + " completed"); }
            }
        }

        ExecutionThread.unset();
        onScriptComplete(context, executionSummary, iterationManager, ticktock);
        MemManager.recordMemoryChanges(scriptName + " completed");
    }

    public ExecutionSummary getExecutionSummary() { return executionSummary; }

    public List<File> getCompletedTests() { return completedTests; }

    protected Map<String, Object> getIntraExecutionData() { return intraExecutionData; }

    protected void setIntraExecutionData(Map<String, Object> intraExecutionData) {
        this.intraExecutionData = intraExecutionData;
    }

    protected void collectIntraExecutionData(ExecutionContext context, int completeIteration) {
        if (context == null) { return; }
        // override, if found, previous "last completed iteration count"
        intraExecutionData.put(LAST_ITERATION, completeIteration);
        context.fillIntraExecutionData(intraExecutionData);
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
                                        Exception e) {
        if (context == null) { context = ExecutionThread.get(); }

        if (e != null) {
            iterationSummary.setError(e);
            context.setFailImmediate(true);
        }

        File testScript = context == null ? null : context.getTestScript();
        String testScriptName = testScript == null ? execDef.getTestScript() + " (unparseable?)" : testScript.getName();

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
                           "| Test Output:    " + testScriptName + "\n" +
                           "| Iteration:      " + iteration + "\n" +
                           "\\-------------------------------------------------------------------------------\n" +
                           (e != null ? "» Error:          " + e.getMessage() : ""),
                           e);
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
                         "| Test Output:    " + context.getTestScript() + "\n" +
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

        StringBuilder cloudOutputBuffer = new StringBuilder();
        if (context.isOutputToCloud()) {
            NexialS3Helper s3Helper = context.getS3Helper();
            // when saving test output to cloud, we might want to remove it locally - esp. when assistant-mode is on
            boolean removeLocal = !isAutoOpenResult();

            summary.getNestedExecutions().forEach(nested -> {
                File testScript = nested.getTestScript();
                try {
                    String testScriptUrl = s3Helper.importFile(testScript, removeLocal);
                    nested.setTestScriptLink(testScriptUrl);
                    cloudOutputBuffer.append("» Iteration ").append(nested.getName()).append(": ").append(testScriptUrl)
                                     .append("\n");
                } catch (IOException e) {
                    ConsoleUtils.error("Unable to save " + testScript + " to cloud storage due to " + e.getMessage());
                }
            });
        }
        String cloudOutput = cloudOutputBuffer.toString();

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
                         (StringUtils.isNotBlank(cloudOutput) ? cloudOutput : "") +
                         "\n\n");
        MemManager.gc(execDef);
    }

    protected void throwTerminalException(Result result) {
        if (result == null || result.getFailureCount() < 1) { return; }

        for (Failure f : result.getFailures()) {
            Throwable e = f.getException();
            if (e == null) { continue; }
            if (e instanceof InvocationTargetException) { e = ((InvocationTargetException) e).getTargetException(); }
            if (e instanceof RuntimeException) { throw (RuntimeException) e; }
            if (e instanceof Error) { throw (Error) e; }
        }
    }

}
