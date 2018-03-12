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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.mail.EmailException;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.nexial.commons.logging.LogbackUtils;
import org.nexial.commons.utils.DateUtility;
import org.nexial.commons.utils.EnvUtils;
import org.nexial.commons.utils.FileUtil;
import org.nexial.commons.utils.RegexUtils;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.aws.NexialS3Helper;
import org.nexial.core.excel.Excel;
import org.nexial.core.excel.Excel.Worksheet;
import org.nexial.core.model.ExecutionDefinition;
import org.nexial.core.model.ExecutionSummary;
import org.nexial.core.model.TestProject;
import org.nexial.core.reports.MailNotifier;
import org.nexial.core.reports.ExecutionNotifier;
import org.nexial.core.service.ServiceLauncher;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.ExecUtil;
import org.nexial.core.utils.InputFileUtils;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static java.io.File.separator;
import static org.apache.commons.lang3.SystemUtils.USER_NAME;
import static org.nexial.core.NexialConst.CLI.*;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Data.*;
import static org.nexial.core.NexialConst.ExitStatus.*;
import static org.nexial.core.NexialConst.Project.*;
import static org.nexial.core.excel.ExcelConfig.*;
import static org.nexial.core.model.ExecutionSummary.ExecutionLevel.EXECUTION;

/**
 * Main class to run nexial from command line:
 * <ol>
 * <li>
 * This is the simplest case, which will run all the test scenarios in the given script with all the
 * data specified in the data file by the same name in {@code data} directory.  For example,
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx
 * </pre>
 * <li>
 * This example is the same as the above (but more verbose):
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx -data mytest.xlsx
 * </pre>
 * Note that the {@code ${PROJECT_HOME}} is determined via the specified location of the test script (see below
 * for more).  The {@code script} directory and {@code data} directory are assumed as sibling directories to
 * each other, and the {@code output} is assumed to be parallel to the parent directory of the {@code script}
 * directory.
 * </li>
 * <li>
 * This example would be functionally equivalent to the one above:
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx -data ../data/mytest.xlsx -output ../../output
 * </pre>
 * </li>
 * <li>
 * This variation allows user to specify a data file and output directory that is outside of the
 * default/preferred project structure.  For example,
 * <pre>
 * java org.nexial.core.Nexial -script C:\projects\PROJECT_X\artifact\script\mytest.xlsx -data C:\my_private_stash\myowndata.xlsx -output C:\temp\dir1
 * </pre>
 * Note that only the {@code -script} commandline argument is required.  When other arguments are omitted, their
 * equivalent default is assumed.
 * </li>
 * <li>
 * This example allow user to specify the test scenarios to execute within the specified test script:
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx -scenarios Scenario1,Scenario2
 * </pre>
 * This will execute only {@code Scenario1} and {@code Scenario2}, in that order.  Scenarios are essentially
 * individual worksheet of the specified Excel file.  If any of the specified scenarios is not found, Nexial
 * will report the error and promptly exit.<br/>
 * Note that if the scenario contains spaces, use double quotes around the entire argument like so:
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx -scenarios "Scenario 1,Scenario 2"
 * </pre>
 * The comma is the delimiter for the scenarios.
 * </li>
 * <li>
 * This example shows how one can restrict data use by specifying the data sheets in the specified data file:
 * <pre>
 * java org.nexial.core.Nexial -script mytest.xlsx -data mytest.xlsx -datasheets "My Data,Last Sprint's Data"
 * </pre>
 * </li>
 * <li>
 * This example shows how to use another Excel file (template: nexial-plan.xlsx) to coordinate a more
 * complex test execution involving multiple test scripts, test scenarios and data files:
 * <pre>
 * java org.nexial.core.Nexial -plan mytestplan.xlsx
 * </pre>
 * See <a href="https://confluence.ep.com/display/QA/2016-09-13+Sentry+Sprint+0+Meeting+notes+-+Excel+template+and+Desktop+automation+strategy">Confluence page</a>
 * for more details.
 * </li>
 * </ol>
 *
 * <p>
 * <b>TO BE CONTINUED...</b>
 * This class will do the following:
 * <ol>
 * <li>
 * invoke {@link ExecutionInputPrep} to scan for iterations
 * <ul>
 * <li>current implementation based on {@code @includeSet()} in {@code #data} worksheet</li>
 * <li>create output directory structure and expand the specified excel file into the iteration-specific ones:
 * <pre>
 * ${nexial.outBase}/
 * +-- ${timestamp}-${nexial.excel}/
 *     +-- ${nexial.excel}.xlsx
 *     +-- summary/
 *     +-- iteration1/
 *         +-- logs/
 *         +-- captures/
 *         +-- ${nexial.excel}_1.xlsx
 *     +-- iteration2/
 *         +-- logs/
 *         +-- captures/
 *         +-- ${nexial.excel}_2.xlsx
 *     +-- iteration3/
 *         +-- logs/
 *         +-- captures/
 *         +-- ${nexial.excel}_3.xlsx
 * </pre></li>
 * </ul>
 * </li>
 * <li>
 * iterate through all the derived iterations and invoke {@link ExecutionThread} accordingly<br/>
 * The test results will be saved to the {@code summary} directory, the logs and screencapture
 * saved to the respective {@code logs} and {@code captures} directories.
 * </li>
 * <li>
 * invoke {@link MailNotifier} to consolidate all the test results and email it to the specified
 * email addresses (based on {@code nexial.mailTo} variable).
 * </li>
 * </ol>
 */
public class Nexial {
    private static final int THREAD_WAIT_LOG_INTERVAL = 60;

    private ClassPathXmlApplicationContext springContext;
    private TestProject project;
    private List<ExecutionDefinition> executions;
    private int threadWaitCounter;
    private int listenPort = -1;
    private String listenerHandshake;

    @SuppressWarnings("PMD.DoNotCallSystemExit")
    public static void main(String[] args) {

        Nexial main = null;
        try {
            main = new Nexial();
            main.init(args);
        } catch (Exception e) {
            if (!(e instanceof IllegalArgumentException)) { e.printStackTrace(); }
            System.err.println(e.getMessage());
            usage();
            System.exit(-1);
        }

        if (main.isListenMode()) {
            try {
                main.listen();
                ConsoleUtils.log("Nexial Services ready...");
            } catch (Throwable e) {
                e.printStackTrace();
            }
        } else {
            ExecutionSummary summary = null;
            try {
                MemManager.recordMemoryChanges("before execution");
                summary = main.execute();
                MemManager.recordMemoryChanges("after execution");
            } catch (Throwable e) {
                e.printStackTrace();
            }

            ConsoleUtils.log("Exiting Nexial...");
            System.exit(beforeShutdown(summary));
        }
    }

    protected void listen() throws Exception { ServiceLauncher.main(new String[]{}); }

    protected boolean isListenMode() { return listenPort > 0 && StringUtils.isNotBlank(listenerHandshake); }

    protected Options addMsaOptions(Options options) {
        options.addOption("listen", true, "start Nexial in listen mode");
        options.addOption("listenCode", true, "establish listener/receiver handshake");
        return options;
    }

    /** read from the commandline and derive the intended execution order. */
    protected void init(String[] args) throws IOException, ParseException {
        // first things first -- do we have all the required system properties?
        String errPrefix = "System property " + NEXIAL_HOME;
        String nexialHome = System.getProperty(NEXIAL_HOME);
        if (StringUtils.isBlank(nexialHome)) { throw new RuntimeException(errPrefix + " missing; unable to proceed"); }
        if (!FileUtil.isDirectoryReadable(nexialHome)) {
            throw new RuntimeException(errPrefix + " does not refer to a valid directory (" + nexialHome + "); " +
                                       "unable to proceed");
        }

        ConsoleUtils.log(ExecUtil.deriveJarManifest() + " starting up...");

        CommandLine cmd = new DefaultParser().parse(addMsaOptions(OPTIONS), args);

        // msa treatment
        if (cmd.hasOption("listen") && cmd.hasOption("listenCode")) {
            listenPort = NumberUtils.toInt(cmd.getOptionValue("listen"));
            listenerHandshake = cmd.getOptionValue("listenCode");
            return;
        }

        // force logs to be pushed into the specified output directory (and not taint other past/concurrent runs)
        if (cmd.hasOption(OUTPUT)) {
            String logPath = cmd.getOptionValue(OUTPUT);
            System.setProperty(TEST_LOG_PATH, logPath);
            if (StringUtils.isBlank(System.getProperty(THIRD_PARTY_LOG_PATH))) {
                System.setProperty(THIRD_PARTY_LOG_PATH, logPath);
            }
        }

        // plan or script?
        if (cmd.hasOption(PLAN)) {
            this.executions = parsePlanExecution(cmd);
        } else {
            if (!cmd.hasOption(SCRIPT)) { fail("test script is required but not specified"); }
            this.executions = parseScriptExecution(cmd);
        }

        ConsoleUtils.log("input files and output directory resolved...");
    }

    protected List<ExecutionDefinition> parsePlanExecution(CommandLine cmd) throws IOException {
        // 1. based on plan location, determine script, data, output and artifact directory
        String testPlanPath = cmd.getOptionValue(PLAN);
        if (!InputFileUtils.isValidPlanFile(testPlanPath)) {
            fail("specified test plan (" + testPlanPath + ") is not readable or does not contain valid format.");
        }

        File testPlanFile = new File(testPlanPath);

        // resolve project directory structure based on the {@code testScriptFile} command line input
        project = TestProject.newInstance(testPlanFile, DEF_REL_LOC_TEST_PLAN);
        if (!project.isStandardStructure()) {
            ConsoleUtils.log("specified plan (" + testPlanFile + ") not following standard project " +
                             "structure, related directories would not be resolved from commandline arugments.");
        }

        deriveOutputDirectory(cmd, project);

        // 2. parse plan file to determine number of executions (1 row per execution)
        List<ExecutionDefinition> executions = new ArrayList<>();
        InputFileUtils.retrieveValidPlanSequence(new Excel(testPlanFile, DEF_OPEN_EXCEL_AS_DUP)).forEach(
            testPlan -> {
                int rowStartIndex = ADDR_PLAN_EXECUTION_START.getRowStartIndex();
                int lastExecutionRow = testPlan.findLastDataRow(ADDR_PLAN_EXECUTION_START);

                for (int i = rowStartIndex; i < lastExecutionRow; i++) {
                    XSSFRow row = testPlan.getSheet().getRow(i);

                    File testScript = deriveScriptFromPlan(row, project, testPlanPath);
                    List<String> scenarios = deriveScenarioFromPlan(row, testScript);
                    File dataFile = deriveDataFileFromPlan(row, project, testPlanFile, testScript);
                    List<String> dataSheets = deriveDataSheetsFromPlan(row, scenarios);

                    // create new definition instance, based on various input and derived values
                    ExecutionDefinition exec = new ExecutionDefinition();
                    exec.setPlanFilename(StringUtils.substringBeforeLast(testPlanFile.getName(), "."));
                    exec.setPlanName(testPlan.getName());
                    exec.setPlanSequnce((i - rowStartIndex + 1));
                    exec.setDescription(StringUtils.trim(readCellValue(row, COL_IDX_PLAN_DESCRIPTION)));
                    exec.setTestScript(testScript.getAbsolutePath());
                    exec.setScenarios(scenarios);
                    exec.setDataFile(dataFile.getAbsolutePath());
                    exec.setDataSheets(dataSheets);
                    exec.setProject(project);

                    // 2.1 mark option for parallel run and fail fast
                    exec.setFailFast(BooleanUtils.toBoolean(
                        StringUtils.defaultIfBlank(readCellValue(row, COL_IDX_PLAN_FAIL_FAST), DEF_PLAN_FAIL_FAST)));
                    exec.setSerialMode(BooleanUtils.toBoolean(
                        StringUtils.defaultIfBlank(readCellValue(row, COL_IDX_PLAN_WAIT), DEF_PLAN_SERIAL_MODE)));
                    exec.setLoadTestMode(BooleanUtils.toBoolean(readCellValue(row, COL_IDX_PLAN_LOAD_TEST)));
                    if (exec.isLoadTestMode()) { fail("Sorry... load testing mode not yet ready for use."); }

                    try {
                        // 3. for each row, parse script (and scenario) and data (and datasheet)
                        exec.parse();
                        executions.add(exec);
                    } catch (IOException e) {
                        fail("Unable to parse successfully for the test plan specified in ROW " +
                             (row.getRowNum() + 1) + " of " + testPlanFile);
                    }
                }
            });

        // 4. return a list of scripts mixin data
        return executions;
    }

    protected File deriveScriptFromPlan(XSSFRow row, TestProject project, String testPlan) {
        String testScriptPath = readCellValue(row, COL_IDX_PLAN_TEST_SCRIPT);
        if (StringUtils.isBlank(testScriptPath)) {
            fail("Invalid test script specified in ROW " + (row.getRowNum() + 1) + " of " + testPlan);
        }

        testScriptPath = StringUtils.appendIfMissing(testScriptPath, ".xlsx");

        // is the script specified as full path (local PC)?
        if (!RegexUtils.isExact(testScriptPath, "[A-Za-z]\\:\\\\.+") &&
            // is it on network drive (UNC)?
            !StringUtils.startsWith(testScriptPath, "\\\\") &&
            // is it on *NIX or Mac disk?
            !StringUtils.startsWith(testScriptPath, "/")) {

            // ok, not on local drive, not on network drive, not on *NIX or Mac disk
            // let's treat it as a project file
            testScriptPath = project.getScriptPath() + testScriptPath;
            ConsoleUtils.log("trying test script as " + testScriptPath);
            if (InputFileUtils.isValidScript(testScriptPath)) { return new File(testScriptPath); }

            // hmm.. maybe it's a relative path based on current plan file?
            testScriptPath = project.getPlanPath() + testScriptPath;
            ConsoleUtils.log("trying test script as " + testScriptPath);
        }

        if (!InputFileUtils.isValidScript(testScriptPath)) {
            // could be abs. path or relative path based on current project
            fail("Invalid/unreadable test script specified in ROW " + (row.getRowNum() + 1) + " of " + testPlan);
        }

        return new File(testScriptPath);
    }

    protected List<String> deriveScenarioFromPlan(XSSFRow row, File testScript) {
        String planScenarios = readCellValue(row, COL_IDX_PLAN_SCENARIOS);
        List<String> scenarios = TextUtils.toList(planScenarios, ",", true);
        if (CollectionUtils.isNotEmpty(scenarios)) { return scenarios; }

        try {
            List<Worksheet> validScenarios =
                InputFileUtils.retrieveValidTestScenarios(new Excel(testScript, DEF_OPEN_EXCEL_AS_DUP));
            if (CollectionUtils.isEmpty(validScenarios)) {
                fail("No valid scenario found in script " + testScript);
            } else {
                scenarios = new ArrayList<>();
                for (Worksheet scenario : validScenarios) { scenarios.add(scenario.getName()); }
            }
        } catch (IOException e) {
            fail("Unable to collect scenarios from " + testScript);
        }
        return scenarios;
    }

    protected File deriveDataFileFromPlan(XSSFRow row, TestProject project, File testPlan, File testScript) {
        String dataFilePath = readCellValue(row, COL_IDX_PLAN_TEST_DATA);
        String dataPath = project.getDataPath();

        // if data file not specified, derive it via the test script
        if (StringUtils.isBlank(dataFilePath)) {
            dataFilePath = dataPath + StringUtils.substringBeforeLast(testScript.getName(), ".") + ".data.xlsx";
        } else {
            dataFilePath = StringUtils.appendIfMissing(dataFilePath, ".xlsx");

            // is the data file specified as full path (local PC)?
            if (!RegexUtils.isExact(dataFilePath, "[A-Za-z]\\:\\\\.+") &&
                // is it on network drive (UNC)?
                !StringUtils.startsWith(dataFilePath, "\\\\") &&
                // is it on *NIX or Mac disk?
                !StringUtils.startsWith(dataFilePath, "/")) {

                // ok, not on local drive, not on network drive, not on *NIX or Mac disk
                // let's treat it as a project file
                dataFilePath = dataPath + dataFilePath;
                ConsoleUtils.log("trying data file as " + dataFilePath);
                if (InputFileUtils.isValidDataFile(dataFilePath)) { return new File(dataFilePath); }

                // hmm.. maybe it's a relative path based on current plan file?
                dataFilePath = project.getPlanPath() + dataFilePath;
                ConsoleUtils.log("trying data file as " + dataFilePath);
            }
        }

        if (!InputFileUtils.isValidDataFile(dataFilePath)) {
            fail("Invalid/unreadable data file specified in ROW " + (row.getRowNum() + 1) + " of " + testPlan);
        }

        return new File(dataFilePath);
    }

    protected List<String> deriveDataSheetsFromPlan(XSSFRow row, List<String> scenarios) {
        String planDataSheets = readCellValue(row, COL_IDX_PLAN_DATA_SHEETS);
        return StringUtils.isBlank(planDataSheets) ? scenarios : TextUtils.toList(planDataSheets, ",", true);
    }

    protected List<ExecutionDefinition> parseScriptExecution(CommandLine cmd) throws IOException {
        // command line option - script
        String testScriptPath = cmd.getOptionValue(SCRIPT);
        if (!InputFileUtils.isValidScript(testScriptPath)) {
            fail("specified test script (" + testScriptPath + ") is not readable or does not contain valid format.");
        }

        // resolve the standard project structure based on test script input
        File testScriptFile = new File(testScriptPath);

        // resolve project directory structure based on the {@code testScriptFile} command line input
        project = TestProject.newInstance(testScriptFile, DEF_REL_LOC_TEST_SCRIPT);
        if (!project.isStandardStructure()) {
            ConsoleUtils.log("specified test script (" + testScriptFile + ") not following standard project " +
                             "structure, related directories would not be resolved from commandline arugments.");
        }
        String artifactPath = project.isStandardStructure() ?
                              StringUtils.appendIfMissing(project.getArtifactPath(), separator) : null;

        // command line option - scenario
        List<String> targetScenarios = new ArrayList<>();
        if (cmd.hasOption(SCENARIO)) {
            List<String> scenarios = TextUtils.toList(cmd.getOptionValue(SCENARIO), ",", true);
            if (CollectionUtils.isEmpty(scenarios)) {
                fail("Unable to derive any valid test script to run.");
            } else {
                targetScenarios.addAll(scenarios);
            }
        }

        // resolve scenario
        if (CollectionUtils.isEmpty(targetScenarios)) {
            Excel excel = new Excel(testScriptFile, DEF_OPEN_EXCEL_AS_DUP);
            List<Worksheet> allTestScripts = InputFileUtils.retrieveValidTestScenarios(excel);
            if (CollectionUtils.isNotEmpty(allTestScripts)) {
                allTestScripts.forEach(sheet -> targetScenarios.add(sheet.getName()));
            } else {
                fail("Unable to derive any valid test script from " + testScriptPath);
            }
        }

        // command line option - data. could be fully qualified or relative to script
        String dataFilePath = cmd.hasOption(DATA) ? cmd.getOptionValue(DATA) : null;
        if (StringUtils.isNotBlank(dataFilePath)) {
            // could be fully qualified or relative to script
            if (!FileUtil.isFileReadable(dataFilePath)) {
                // try again by relative path
                String dataFile1 = artifactPath + dataFilePath;
                if (!FileUtil.isFileReadable(dataFile1)) {
                    fail("data file (" + dataFilePath + ") is not readable via absolute or relative path.");
                } else {
                    dataFilePath = dataFile1;
                }
            } // else dataFile is specified as a fully qualified path
        } else {
            dataFilePath = artifactPath +
                           StringUtils.appendIfMissing(DEF_LOC_TEST_DATA, separator) +
                           StringUtils.substringBeforeLast(testScriptFile.getName(), ".") + ".data.xlsx";
        }

        if (!InputFileUtils.isValidDataFile(dataFilePath)) {
            fail("data file (" + dataFilePath + ") does not contain valid data file format.");
            return null;
        }

        File dataFile = new File(dataFilePath);
        if (!project.isStandardStructure()) { project.setDataPath(dataFile.getParentFile().getAbsolutePath()); }
        ConsoleUtils.log("data file resolved as " + dataFile.getAbsolutePath());

        // command line option - datasheets
        List<String> dataSheets = new ArrayList<>();
        if (cmd.hasOption(DATASHEETS)) {
            List<String> dataSets = TextUtils.toList(cmd.getOptionValue(DATASHEETS), ",", true);
            if (CollectionUtils.isEmpty(dataSets)) {
                fail("Unable to derive any valid data sheet to use.");
            } else {
                dataSheets.addAll(dataSets);
            }
        }
        // datasheet names are the same as scenario if none is specifically specified
        if (CollectionUtils.isEmpty(dataSheets)) { dataSheets = targetScenarios; }

        deriveOutputDirectory(cmd, project);

        // create new definition instance, based on various input and derived values
        ExecutionDefinition exec = new ExecutionDefinition();
        exec.setDescription("Started via commandline inputs on " + DateUtility.getCurrentDateTime() + " from " +
                            EnvUtils.getHostName() + " via user " + USER_NAME);
        exec.setTestScript(testScriptPath);
        exec.setScenarios(targetScenarios);
        exec.setDataFile(dataFile.getAbsolutePath());
        exec.setDataSheets(dataSheets);
        exec.setProject(project);
        exec.parse();

        List<ExecutionDefinition> executions = new ArrayList<>();
        executions.add(exec);
        return executions;
    }

    protected void deriveOutputDirectory(CommandLine cmd, TestProject project) throws IOException {
        // command line option - output
        String outputPath = cmd.hasOption(OUTPUT) ? cmd.getOptionValue(OUTPUT) : null;
        if (StringUtils.isNotBlank(outputPath)) { project.setOutPath(outputPath); }

        String outPath = project.getOutPath();
        if (StringUtils.isBlank(outPath)) { fail("output location cannot be resolved."); }
        if (FileUtil.isFileReadable(outPath)) {
            fail("output location (" + outPath + ") cannot be accessed as a directory.");
        }
        if (!FileUtil.isDirectoryReadable(outPath)) { FileUtils.forceMkdir(new File(outPath)); }

        System.setProperty(TEST_LOG_PATH, outPath);
        if (StringUtils.isBlank(System.getProperty(THIRD_PARTY_LOG_PATH))) {
            System.setProperty(THIRD_PARTY_LOG_PATH, outPath);
        }
    }

    protected ExecutionSummary execute() {
        initSpringContext();

        // start of test suite (one per test plan in execution)
        String runId = ExecUtil.deriveRunId();

        String notificationList = null;
        ExecutionSummary summary = new ExecutionSummary();
        summary.setName(runId);
        summary.setExecutionLevel(EXECUTION);
        summary.setStartTime(System.currentTimeMillis());

        List<ExecutionThread> executionThreads = new ArrayList<>();
        Map<String, Object> intraExecution = null;

        try {
            for (ExecutionDefinition exec : executions) {
                exec.setRunId(runId);
                LogbackUtils.registerLogDirectory(appendLog(exec));

                String msgPrefix = "[" + exec.getTestScript() + "] ";
                ConsoleUtils.log(runId, msgPrefix + "resolve RUN ID as " + runId);

                notificationList = exec.getTestData().getMailTo();

                ExecutionThread launcherThread = ExecutionThread.newInstance(exec);
                if (MapUtils.isNotEmpty(intraExecution)) { launcherThread.setIntraExecutionData(intraExecution); }
                executionThreads.add(launcherThread);

                ConsoleUtils.log(runId, msgPrefix + "new thread started");
                launcherThread.start();

                if (exec.isSerialMode()) {
                    while (true) {
                        if (launcherThread.isAlive()) {
                            debugThread(runId, msgPrefix + "awaits execution thread to complete...");
                            Thread.sleep(LAUNCHER_THREAD_COMPLETION_WAIT_MS);
                        } else {
                            ConsoleUtils.log(runId, msgPrefix + "now completed");
                            // pass the post-execution state of data to the next execution
                            intraExecution = launcherThread.getIntraExecutionData();
                            executionThreads.remove(launcherThread);
                            summary.addNestSummary(launcherThread.getExecutionSummary());
                            launcherThread = null;
                            break;
                        }
                    }
                } else {
                    ConsoleUtils.log(runId, msgPrefix + "in progress, progressing to next execution");
                }
            }

            while (true) {
                final boolean[] stillRunning = {false};
                executionThreads.forEach(t -> {
                    if (t != null) {
                        if (t.isAlive()) {
                            stillRunning[0] = true;
                        } else {
                            summary.addNestSummary(t.getExecutionSummary());
                            t = null;
                        }
                    }
                });

                if (!stillRunning[0]) { break; }

                debugThread(runId, "waiting for execution thread(s) to complete...");
                Thread.yield();
                Thread.sleep(LAUNCHER_THREAD_COMPLETION_WAIT_MS);
            }

            ConsoleUtils.log(runId, "all execution thread(s) have terminated");
        } catch (Throwable e) {
            ConsoleUtils.error(e.toString());
            e.printStackTrace();
            summary.setError(e);
        } finally {
            onExecutionComplete(runId, summary, notificationList);
        }

        return summary;
    }

    /**
     * this represents the end of an entire Nexial run, including all iterations and plan steps.
     */
    protected void onExecutionComplete(String runId, ExecutionSummary summary, String notificationList) {
        // todo: THINK!!! WE MIGHT NEED A GLOBAL TEARDOWN() ROUTINE TO MATCH WHAT WAS DONE IN GLOBAL INIT().
        //NexialLauncher.tearDown();

        // end of test suite (one per test plan in execution)
        long startTimeMs = NumberUtils.toLong(System.getProperty(TEST_START_TS));
        long stopTimeMs = System.currentTimeMillis();
        long testSuiteElapsedTimeMs = stopTimeMs - startTimeMs;
        ConsoleUtils.log(runId, "test run completed in about " + (testSuiteElapsedTimeMs / 1000) + " seconds");

        summary.setEndTime(stopTimeMs);
        summary.aggregatedNestedExecutions(null);

        boolean outputToCloud = BooleanUtils.toBoolean(System.getProperty(OUTPUT_TO_CLOUD, DEF_OUTPUT_TO_CLOUD + ""));
        boolean generateReport =
            outputToCloud ||
            BooleanUtils.toBoolean(System.getProperty(GENERATE_EXEC_REPORT, DEF_GENERATE_EXEC_REPORT + ""));

        if (generateReport) {
            String reportPath = StringUtils.appendIfMissing(System.getProperty(OPT_OUT_DIR, project.getOutPath()),
                                                            separator);
            if (!StringUtils.contains(reportPath, runId)) { reportPath += runId + separator; }
            String jsonDetailedReport = reportPath + "execution-detail.json";
            String jsonSummaryReport = reportPath + "execution-summary.json";
            // String htmlReport = reportPath + "execution-summary.html";

            try {
                summary.generateDetailedJson(jsonDetailedReport);
                summary.generateSummaryJson(jsonSummaryReport);
                // summary.generateHtmlReport(htmlReport);

                if (outputToCloud) {
                    NexialS3Helper s3Helper = springContext.getBean("nexialS3Helper", NexialS3Helper.class);
                    // can't use s3Helper.resolveOutputDir() since we are out of context at this point in time
                    String outputDir =
                        System.getProperty(OPT_CLOUD_OUTPUT_BASE) + "/" + project.getName() + "/" + runId;

                    try {
                        s3Helper.importToS3(new File(jsonSummaryReport), outputDir, true);
                        s3Helper.importToS3(new File(jsonDetailedReport), outputDir, true);
                        // s3Helper.importToS3(new File(htmlReport), outputDir, true);
                    } catch (IOException e) {
                        ConsoleUtils.error("Unable to save to cloud storage due to " + e.getMessage());
                    }
                    // } else {
                    // ConsoleUtils.log(runId, "execution summary JSON saved to " + jsonSummaryReport);
                    // ConsoleUtils.log(runId, "execution summary HTML saved to " + htmlReport);
                }
            } catch (IOException e) {
                ConsoleUtils.error(runId, "Unable to save execution summary due to " + e.getMessage(), e);
            }
        }

        if (StringUtils.isNotBlank(notificationList) && isEmailEnabled() && springContext != null) {
            ExecutionNotifier notifier = springContext.getBean("mailNotifier", ExecutionNotifier.class);
            notifyCompletion(notifier, notificationList, summary);
        }
    }

    protected void initSpringContext() {
        springContext = new ClassPathXmlApplicationContext("classpath:/nexial-main.xml");
    }

    /**
     * resolve data file and standard "data" directory based on either data-related commandline input or
     * {@code testScriptFile} commandline input.
     */
    protected File resolveDataFile(TestProject project, CommandLine cmd, File testScriptFile) {
        String artifactPath = null;
        if (project.isStandardStructure()) { artifactPath = project.getArtifactPath(); }

        String dataFile;
        if (cmd.hasOption(DATA)) {
            // could be fully qualified or relative to script
            dataFile = cmd.getOptionValue(DATA);
            if (!FileUtil.isFileReadable(dataFile)) {

                if (testScriptFile == null) {
                    fail("data file (" + cmd.getOptionValue(DATA) + ") cannot be resolved; test script not specified.");
                }

                // try again by relative path
                dataFile = artifactPath + dataFile;
                if (!FileUtil.isFileReadable(dataFile)) {
                    fail("data file (" + cmd.getOptionValue(DATA) + ") is not readable via absolute or relative path.");
                }
            } // else dataFile is specified as a fully qualified path
        } else {
            if (testScriptFile == null) {
                fail("data file cannot be resolved since test script is not specified");
                return null;
            }

            dataFile = appendData(artifactPath) + separator +
                       (StringUtils.substringBeforeLast(testScriptFile.getName(), ".") + ".data.xlsx");
        }

        if (!InputFileUtils.isValidDataFile(dataFile)) {
            fail("data file (" + dataFile + ") does not contain valid data file format.");
            return null;
        }

        File file = new File(dataFile);
        if (!project.isStandardStructure()) { project.setDataPath(file.getParentFile().getAbsolutePath()); }
        ConsoleUtils.log("data file resolved as " + file.getAbsolutePath());
        return file;
    }

    protected static void fail(String message) {
        ConsoleUtils.error("ERROR: " + message);
        throw new IllegalArgumentException("Required argument is missing or invalid.  Check usage details.");
    }

    @SuppressWarnings("PMD.SystemPrintln")
    protected static void usage() {
        System.out.println();
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(Nexial.class.getName(), OPTIONS, true);
        System.out.println();
    }

    protected static boolean mustTerminateForcefully() { return ShutdownAdvisor.mustForcefullyTerminate(); }

    private static int beforeShutdown(ExecutionSummary summary) {
        // need to kill JVM forcefully if awt was used during runtime
        // -- haven't found a way to do this more gracefully yet...
        if (ShutdownAdvisor.mustForcefullyTerminate()) { ShutdownAdvisor.forcefullyTerminate(); }

        int exitStatus;
        if (summary == null) {
            System.err.println("Unable to cleanly execute tests; execution summary missing!");
            exitStatus = RC_EXECUTION_SUMMARY_MISSING;
        } else {
            double minExecSuccessRate = NumberUtils.toDouble(
                System.getProperty(MIN_EXEC_SUCCESS_RATE, DEF_MIN_EXEC_SUCCESS_RATE + ""));
            if (minExecSuccessRate < 0 || minExecSuccessRate > 100) { minExecSuccessRate = DEF_MIN_EXEC_SUCCESS_RATE; }
            minExecSuccessRate = minExecSuccessRate / 100;
            String minSuccessRateString = MessageFormat.format(RATE_FORMAT, minExecSuccessRate);

            double successRate = summary.getSuccessRate();
            String successRateString = MessageFormat.format(RATE_FORMAT, successRate);

            System.out.println();
            System.out.println("/-END OF EXECUTION--------------------------------------------------------------");
            System.out.println("| » Execution Time: " + (summary.getElapsedTime() / 1000) + " sec.");
            System.out.println("| » Test Steps:     " + summary.getExecuted());
            System.out.println("| » Passed:         " + summary.getPassCount());
            System.out.println("| » Failed:         " + summary.getFailCount());
            System.out.println("| » Success Rate:   " + successRateString);
            System.out.println("\\---------------------------------------------------------------" +
                               StringUtils.leftPad(ExecUtil.deriveJarManifest(), 15, "-") + "-");

            if (successRate != 1) {
                if (successRate >= minExecSuccessRate) {
                    System.out.println("PASSED - success rate greater than specified minimium success rate " +
                                       "(" + successRateString + " >= " + minSuccessRateString + ")");
                    exitStatus = 0;
                } else {
                    System.err.println("failure found; success rate is " + successRateString);
                    exitStatus = RC_NOT_PERFECT_SUCCESS_RATE;
                }
            } else {
                int failCount = summary.getFailCount();
                if (failCount > 0) {
                    System.err.println(failCount + " failure(s) found, although success rate is 100%");
                    exitStatus = RC_FAILURE_FOUND;
                } else {
                    int warnCount = summary.getWarnCount();
                    if (warnCount > 0) {
                        System.err.println(warnCount + " warning(s) found, although success rate is 100%");
                        exitStatus = RC_WARNING_FOUND;
                    } else {
                        System.out.println("ALL PASSED!");
                        exitStatus = 0;
                    }
                }
            }
        }

        beforeShutdownMemUsage();

        return exitStatus;
    }

    private static void beforeShutdownMemUsage() {
        MemManager.gc((Object) Nexial.class);
        MemManager.recordMemoryChanges("just before exit");
        String memUsage = MemManager.showUsage("| » ");
        if (StringUtils.isNotBlank(memUsage)) {
            System.out.println("\n");
            System.out.println("/-MEMORY-USAGE------------------------------------------------------------------");
            System.out.println(memUsage);
            System.out.println("\\-------------------------------------------------------------------------------");
        }
    }

    private static String readCellValue(XSSFRow row, int columnIndex) {
        if (row == null) { return ""; }
        XSSFCell cell = row.getCell(columnIndex);
        return cell == null ? "" : StringUtils.trim(cell.getStringCellValue());
    }

    private void notifyCompletion(ExecutionNotifier notifier, String recipients, ExecutionSummary summary) {
        if (notifier == null) {
            ConsoleUtils.log("No email to send since email notification is disabled or not configured.");
            return;
        }
        if (recipients == null) {
            ConsoleUtils.log("No email to send since no recipient is specified.");
            return;
        }

        // todo this is ok until we start impl. plan.  With plan we need to use ONLY override email notification
        String[] recipientList = StringUtils.split(StringUtils.replace(recipients, ";", ","), ",");

        try {
            notifier.notify(recipientList, summary);
        } catch (IOException | EmailException | MessagingException e) {
            ConsoleUtils.error("Unable to send out notification email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * log {@code msg} to console if the System property {@code nexial.devLogging} is {@code "true"}.
     */
    private void debugThread(String runId, String msg) {
        boolean debugOn = BooleanUtils.toBoolean(System.getProperty(OPT_DEVMODE_LOGGING, "false"));
        if (!debugOn) { return; }

        if (threadWaitCounter < THREAD_WAIT_LOG_INTERVAL) {
            threadWaitCounter++;
        } else if (threadWaitCounter == 60) {
            ConsoleUtils.log(runId, msg);
            threadWaitCounter++;
        } else {
            threadWaitCounter = 0;
        }
    }
}
