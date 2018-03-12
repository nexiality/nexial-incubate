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

package org.nexial.core.plugins.desktop;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.winium.DesktopOptions;
import org.openqa.selenium.winium.WiniumDriver;
import org.openqa.selenium.winium.WiniumDriverService;

import org.nexial.commons.proc.ProcessInvoker;
import org.nexial.commons.proc.ProcessOutcome;
import org.nexial.commons.utils.FileUtil;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.ExecutionThread;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.utils.ConsoleUtils;
import com.google.common.collect.ImmutableList;

import static org.nexial.commons.proc.ProcessInvoker.WORKING_DIRECTORY;
import static org.nexial.core.NexialConst.*;
import static org.nexial.core.NexialConst.Data.*;
import static org.nexial.core.NexialConst.Project.NEXIAL_HOME;
import static org.nexial.core.NexialConst.Project.NEXIAL_WINDOWS_BIN_REL_PATH;
import static org.nexial.core.utils.WebDriverUtils.*;
import static java.io.File.separator;
import static java.lang.System.lineSeparator;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.openqa.selenium.winium.WiniumDriverService.*;

public final class WiniumUtils {
    private static String winiumPort;
    private static WiniumDriverService winiumDriverService;

    private WiniumUtils() { }

    public static WiniumDriverService getWiniumService() {
        if (winiumDriverService == null) { winiumDriverService = newWiniumService(); }
        return winiumDriverService;
    }

    public static WiniumDriver joinCurrentWiniumSession(int port) throws IOException {
        DesktopOptions options = new DesktopOptions();
        return new WiniumDriver(new URL("http://localhost:" + port), options);
    }

    public static WiniumDriver joinCurrentWiniumSession(int port, String exePath, String arguments) throws IOException {
        DesktopOptions options = resolveDesktopOptions(exePath, arguments);
        options.setDebugConnectToRunningApp(true);
        return new WiniumDriver(new URL("http://localhost:" + port), options);
    }

    public static WiniumDriver joinCurrentWiniumSession() throws IOException {
        DesktopOptions options = new DesktopOptions();
        //options.setKeyboardSimulator(BasedOnWindowsFormsSendKeysClass);
        return new WiniumDriver(new URL("http://localhost:" + winiumPort), options);
    }

    // currently only used by playground
    public static WiniumDriver newWiniumInstance(String autCmd) throws IOException {
        if (StringUtils.isBlank(autCmd)) { throw new IOException("EXE path for AUT is missing"); }
        MutablePair<String, String> exeAndArguments = toExeAndArgs(autCmd);
        return newWiniumInstance(exeAndArguments.getLeft(), exeAndArguments.getRight());
    }

    public static WiniumDriver newWiniumInstance(Aut aut) throws IOException {
        if (aut == null) { throw new IOException("No AUT defined"); }
        if (StringUtils.isBlank(aut.getPath())) { throw new IOException("EXE path for AUT is missing"); }
        if (StringUtils.isBlank(aut.getExe())) { throw new IOException("EXE name for AUT is missing"); }

        String autCmd = StringUtils.appendIfMissing(aut.getPath(), separator) + aut.getExe();
        String args = aut.getArgs();

        if (joinExistingWiniumSession()) {return joinCurrentWiniumSession(NumberUtils.toInt(winiumPort), autCmd, args);}

        // terminate existing instances, if so configured.
        // we shouldn't have multiple instance of AUT running at the same time.. could get into unexpected result
        if (aut.isTerminateExisting()) { terminateRunningInstance(aut.getExe()); }

        List<Integer> runningInstances = WiniumUtils.findRunningInstances(aut.getExe());
        if (CollectionUtils.isEmpty(runningInstances)) {
            // nope.. can't find AUT instance.  Let's start it now
            ConsoleUtils.log("starting new AUT instance via " + autCmd);

            String exePath;
            List<String> exeArgs = new ArrayList<>();

            Map<String, String> autEnv = new HashMap<>();
            boolean hasValidWorkingDirectory = false;
            if (FileUtil.isDirectoryReadable(aut.getWorkingDirectory())) {
                hasValidWorkingDirectory = true;
                autEnv.put(WORKING_DIRECTORY, aut.getWorkingDirectory());
            }

            if (aut.isRunFromWorkingDirectory() && hasValidWorkingDirectory) {
                ConsoleUtils.log("Executing AUT from working directory " + aut.getWorkingDirectory());

                // this flag means to first CD into the working directory and then execute program
                exePath = WIN32_CMD;
                exeArgs.add("/C");
                exeArgs.add("cd " + aut.getWorkingDirectory());
                exeArgs.add(" & ");

                if (StringUtils.equals(aut.getPath(), aut.getWorkingDirectory())) {
                    exeArgs.add(aut.getExe());
                } else {
                    exeArgs.add(autCmd);
                }
                // } else {
                // 	exePath = autCmd;
                // 	ConsoleUtils.log("Executing AUT from via " + exePath);
                // }

                // program cmdline args always come last
                if (StringUtils.isNotBlank(aut.getArgs())) {
                    exeArgs.addAll(TextUtils.toList(aut.getArgs(), " ", true));
                }

                try {
                    ProcessInvoker.invokeNoWait(exePath, exeArgs, autEnv);
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new IOException("Unable to start new app: " + e.getMessage(), e);
                }

                runningInstances = WiniumUtils.findRunningInstances(aut.getExe());
                ConsoleUtils.log("Found running instance of " + aut.getExe() + ": " + runningInstances);
            }
        }

        // now, we need to determine if AUT has been instantiated (such as first instantiation of the driver), or
        // if AUT instance needs to be instantiated yet (such as switching between multiple AUT).
        // if (!aut.isTerminateExisting()) {  }

        // if there are running instances of AUT now, then we should join to such instance (the first one)
        // and use it as _THE_ AUT instance
        if (CollectionUtils.isNotEmpty(runningInstances)) {
            // but we don't know if winium is still running... let's make sure
            List<Integer> runningWiniums = WiniumUtils.findRunningInstances(WINIUM_EXE);
            if (CollectionUtils.isNotEmpty(runningWiniums) && StringUtils.isNotBlank(winiumPort)) {
                ConsoleUtils.log("joining existing Winium session over port " + winiumPort);
                return joinCurrentWiniumSession(NumberUtils.toInt(winiumPort), autCmd, args);
            }

            ConsoleUtils.log("create new Winium session, destroying instance " + runningWiniums);
            DesktopOptions options = new DesktopOptions();
            options.setDebugConnectToRunningApp(true);
            return new WiniumDriver(getWiniumService(), options);
        }

        ConsoleUtils.log("create new Winium session");
        return new WiniumDriver(getWiniumService(), resolveDesktopOptions(autCmd, args));
    }

    public static WiniumDriver joinRunningApp() {
        DesktopOptions options = new DesktopOptions();
        options.setDebugConnectToRunningApp(true);
        return new WiniumDriver(getWiniumService(), options);
    }

    public static WiniumDriver newWiniumInstance(String exePath, String arguments) throws IOException {
        // assert StringUtils.isNotBlank(exePath);

        if (joinExistingWiniumSession()) {
            return joinCurrentWiniumSession(NumberUtils.toInt(winiumPort), exePath, arguments);
        }

        // we shouldn't have multiple instance of AUT running at the same time.. could get into unexpected result
        if (StringUtils.isNotBlank(exePath)) {
            terminateRunningInstance(StringUtils.substringAfterLast(exePath, separator));
        }

        return new WiniumDriver(getWiniumService(), resolveDesktopOptions(exePath, arguments));
    }

    public static void sendKey(WiniumDriver driver, WebElement elem, String keystrokes) {
        if (driver == null) { return; }
        if (StringUtils.isEmpty(keystrokes)) { return; }

        Actions actions = new Actions(driver);
        Stack<Keys> controlKeys = new Stack<>();

        while (StringUtils.isNotEmpty(keystrokes)) {
            String nextKeyStroke = TextUtils.substringBetweenFirstPair(keystrokes, CTRL_KEY_START, CTRL_KEY_END);
            if (StringUtils.isBlank(nextKeyStroke)) {
                // 2. if none (or no more) {..} found, gather remaining string and create sendKey() action
                actions = addReleaseControlKeys(actions, elem, controlKeys);
                actions.perform();
                actions = new Actions(driver);

                if (elem == null) {
                    String[] keys = TextUtils.toOneCharArray(keystrokes);
                    actions.sendKeys(keys);
                } else {
                    elem.sendKeys(keystrokes);
                }
                break;
            }

            String keystrokeId = CTRL_KEY_START + nextKeyStroke + CTRL_KEY_END;

            // 3. if {..} found, let's push all the keystrokes before the found {..} to action
            String text = StringUtils.substringBefore(keystrokes, keystrokeId);
            if (StringUtils.isNotEmpty(text)) {
                actions = addReleaseControlKeys(actions, elem, controlKeys);
                actions.perform();
                actions = new Actions(driver);

                if (elem == null) {
                    String[] keys = TextUtils.toOneCharArray(text);
                    actions.sendKeys(keys);
                } else {
                    elem.sendKeys(text);
                }
            }

            // 4. keystrokes now contain the rest of the key strokes after the found {..}
            keystrokes = StringUtils.substringAfter(keystrokes, keystrokeId);

            // 5. if the found {..} is a single key, just add it as such (i.e. {CONTROL}{C})
            if (StringUtils.length(nextKeyStroke) == 1 && StringUtils.isAlphanumeric(nextKeyStroke)) {
                actions = elem == null ? actions.sendKeys(nextKeyStroke) : actions.sendKeys(elem, nextKeyStroke);
                actions = addReleaseControlKeys(actions, elem, controlKeys);
                actions.perform();
                actions = new Actions(driver);
            } else if (CONTROL_KEY_MAPPING.containsKey(keystrokeId)) {
                // 6. is the found {..} one of the control keys (CTRL, SHIFT, ALT)?
                Keys control = CONTROL_KEY_MAPPING.get(keystrokeId);
                controlKeys.push(control);
                actions = elem == null ? actions.keyDown(control) : actions.keyDown(elem, control);
            } else {
                // 7. if not, then it must one of the non-printable character
                Keys keystroke = KEY_MAPPING.get(keystrokeId);
                if (keystroke == null) { throw new RuntimeException("Unsupported/unknown key " + keystrokeId); }

                actions = elem == null ? actions.sendKeys(keystroke) : actions.sendKeys(elem, keystroke);
                actions = addReleaseControlKeys(actions, elem, controlKeys);
                actions.perform();
                actions = new Actions(driver);
            }

            // 8. loop back
        }

        // 9. just in case user put a control character at the end (not sure why though)
        actions = addReleaseControlKeys(actions, elem, controlKeys);

        // 10. finally, all done!
        actions.perform();
    }

    public static void shutdownWinium(WiniumDriverService service, WiniumDriver driver) {
        ConsoleUtils.log("shutdown down Winium...");
        if (driver != null) {
            try {
                driver.close();
            } catch (Throwable e) {
                ConsoleUtils.log("Unable to close desktop application: \n" + e.getMessage() +
                                 "\nContinue on quit Winium Driver");
            }

            try {
                driver.quit();
            } catch (Throwable e) {
                ConsoleUtils.log("Unable to close Winium Driver: \n" + e.getMessage() +
                                 "\nContinue on shut down Winium Driver Service");
            } finally {
                driver = null;
            }
        }

        if (service != null) {
            if (winiumDriverService != null) {
                if (service == winiumDriverService) {
                    shutdownDriverService(winiumDriverService);
                } else {
                    shutdownDriverService(service);
                }
                winiumDriverService = null;
            } else {
                shutdownDriverService(service);
            }
            service = null;
        } else if (winiumDriverService != null) {
            shutdownDriverService(winiumDriverService);
            winiumDriverService = null;
        }
    }

    // public static Keys findKeys(String key) {
    // 	return KEY_MAPPING.get(key);
    // }
    //
    // public static WebElement findElement(WebElement startElement, String... locators) {
    // 	if (startElement == null) { return null; }
    // 	if (ArrayUtils.isEmpty(locators)) { return null; }
    //
    // 	WebElement elem = startElement;
    // 	for (String locator : locators) {
    // 		if (StringUtils.isBlank(locator)) { return null; }
    //
    // 		By by = DesktopCommand.findBy(locator);
    // 		if (by == null) {
    // 			ConsoleUtils.log("Unable to resolve locator '" + locator + "'");
    // 			return null;
    // 		}
    //
    // 		elem = elem.findElement(by);
    // 		if (elem == null) { return null; }
    // 	}
    //
    // 	return elem;
    // }

    protected static boolean joinExistingWiniumSession() {
        ExecutionContext context = ExecutionThread.get();
        boolean joinExisting = ExecutionContext.getSystemThenContextBooleanData(WINIUM_JOIN, context, false);
        if (!joinExisting) { return false; }

        deriveWiniumPort(context);
        return true;
    }

    protected static boolean isSoloMode() {
        return ExecutionContext.getSystemThenContextBooleanData(WINIUM_SOLO_MODE,
                                                                ExecutionThread.get(),
                                                                DEF_WINIUM_SOLO_MODE);
    }

    protected static boolean terminateRunningInstance(int processId) {
        try {
            ConsoleUtils.log("terminating process with process id " + processId);
            ProcessInvoker.invokeNoWait(WIN32_CMD,
                                        Arrays.asList("/C", "start", "\"\"",
                                                      "taskkill", "/pid", processId + "", "/T", "/F"),
                                        null);
            return true;
        } catch (IOException e) {
            ConsoleUtils.error("Unable to terminate process with process id " + processId + ": " + e.getMessage());
            return false;
        }
    }

    protected static boolean terminateRunningInstance(String exeName) {
        if (!IS_OS_WINDOWS || !isSoloMode()) {
            ConsoleUtils.log("not terminating " + exeName + " since " +
                             "!IS_OS_WINDOWS=" + (!IS_OS_WINDOWS) + " || !isSoloMode()=" + (!isSoloMode()));
            return false;
        }

        try {
            ConsoleUtils.log("terminating any leftover instance of " + exeName);
            ProcessOutcome outcome = ProcessInvoker.invoke(
                WIN32_CMD, Arrays.asList("/C", "taskkill", "/IM", exeName + "*", "/T", "/F"), null);
            ConsoleUtils.log(outcome.getStdout());
            try { Thread.sleep(2000); } catch (InterruptedException e) { }
            return true;
        } catch (IOException | InterruptedException e) {
            ConsoleUtils.error("Unable to terminate any running " + exeName + ": " + e.getMessage());
            return false;
        }
    }

    protected static List<Integer> findRunningInstances(String exeName) {
        try {
            // ConsoleUtils.log("finding existing instances of " + exeName);
            ProcessOutcome outcome = ProcessInvoker.invoke(
                WIN32_CMD,
                Arrays.asList("/C", "tasklist", "/FO", "CSV", "/FI", "\"imagename eq " + exeName + "\"", "/NH"),
                null);
            String output = outcome.getStdout();
            if (StringUtils.isBlank(output)) {
                ConsoleUtils.log("No running process found for " + exeName);
                return null;
            }

            String[] lines = StringUtils.split(StringUtils.trim(output), lineSeparator());
            if (ArrayUtils.isEmpty(lines)) {
                ConsoleUtils.log("No running process found for " + exeName + " (can't split lines)");
                return null;
            }

            // proc id is ALWAYS the second field of this CSV output
            List<Integer> runningProcs = new ArrayList<>();
            for (String line : lines) {
                String[] fields = StringUtils.split(line, ",");
                if (ArrayUtils.isEmpty(fields)) {
                    ConsoleUtils.log("No running process found for " + exeName + " (likely invalid output)");
                    return null;
                }

                if (fields.length < 2) {
                    ConsoleUtils.log("No running process found for " + exeName + " (output without process id)");
                    return null;
                }

                runningProcs.add(NumberUtils.toInt(StringUtils.substringBetween(fields[1], "\"", "\"")));
            }

            ConsoleUtils.log("Found the following running instances of " + exeName + ": " + runningProcs);
            return runningProcs;
        } catch (IOException | InterruptedException e) {
            ConsoleUtils.error("Error when finding running instances of " + exeName + ": " + e.getMessage());
            return null;
        }
    }

    protected static void runApp(String exePath, String exeName, List<String> args) throws IOException {
        runApp(exePath, exeName, args, new HashMap<>());
    }

    protected static void runApp(String exePath, String exeName, List<String> args, Map<String, String> env)
        throws IOException {
        if (StringUtils.isBlank(exePath)) { return; }
        if (StringUtils.isBlank(exeName)) { return; }

        String fullpath = exePath + separator + exeName;
        ProcessInvoker.invokeNoWait(fullpath, args, env);
    }

    protected static void shutdownDriverService(WiniumDriverService service) {
        try {
            service.stop();
        } catch (Throwable e) {
            ConsoleUtils.log("Unable to close Winium Driver Service: \n" + e.getMessage());
        } finally {
            service = null;
        }
    }

    protected static WiniumDriverService newWiniumService() {
        File nexialHomeDir = new File(System.getProperty(NEXIAL_HOME));
        String winiumExePath = StringUtils.appendIfMissing(nexialHomeDir.getAbsolutePath(), separator) +
                               NEXIAL_WINDOWS_BIN_REL_PATH + WINIUM_EXE;
        if (!FileUtil.isFileExecutable(winiumExePath)) {
            throw new RuntimeException(winiumExePath + " is not valid winium executable. Unable to proceed");
        }

        if (terminateRunningInstance(WINIUM_EXE)) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { }
        }

        ExecutionContext context = ExecutionThread.get();

        // log
        String winiumLogPath = System.getProperty(WINIUM_LOG_PATH,
                                                  context == null ? null : context.getStringData(WINIUM_LOG_PATH));
        File logFile = null;
        if (StringUtils.isNotBlank(winiumLogPath)) { logFile = new File(winiumLogPath); }

        // port
        deriveWiniumPort(context);
        ConsoleUtils.log("Assigning to Winium Driver Service port " + winiumPort);
        if (context != null) {
            try {
                File portFile = new File(StringUtils.appendIfMissing(context.getProject().getOutPath(), separator) +
                                         "winium-port.txt");
                FileUtils.write(portFile, winiumPort, DEF_CHARSET);
            } catch (IOException e) {
                ConsoleUtils.log("Unable to write 'winium-port.txt to output dir: " + e.getMessage());
            }
        }

        WiniumDriverService.Builder winiumBuilder = new WiniumDriverService.Builder() {
            /** workaround to honor another port not 9999 */
            @Override
            protected ImmutableList<String> createArgs() {
                if (getLogFile() == null) {
                    String logFilePath = System.getProperty(WINIUM_DRIVER_LOG_PATH_PROPERTY);
                    if (logFilePath != null) { withLogFile(new File(logFilePath)); }
                }

                ImmutableList.Builder<String> argsBuidler = new ImmutableList.Builder<>();
                if (Boolean.getBoolean(WINIUM_DRIVER_SILENT)) { argsBuidler.add("--silent"); }
                if (Boolean.getBoolean(WINIUM_DRIVER_VERBOSE_LOG)) { argsBuidler.add("--verbose"); }
                if (getLogFile() != null) {
                    argsBuidler.add(String.format("--log-path=%s", getLogFile().getAbsolutePath()));
                }
                argsBuidler.add("--port=" + winiumPort);

                return argsBuidler.build();
            }
        }.usingDriverExecutable(new File(winiumExePath))
         .usingPort(NumberUtils.toInt(winiumPort));

        if (logFile != null) { winiumBuilder = winiumBuilder.withLogFile(logFile); }

        WiniumDriverService winiumService = winiumBuilder.buildDesktopService();
        startWiniumService(winiumService);

        if (context != null) { context.setData(WINIUM_SERVICE_RUNNING, true); }

        return winiumService;
    }

    protected static void deriveWiniumPort(ExecutionContext context) {
        winiumPort = ExecutionContext.getSystemThenContextStringData(WINIUM_PORT, context, "");
        if (!NumberUtils.isDigits(winiumPort)) { winiumPort = PortProber.findFreePort() + ""; }
    }

    protected static void startWiniumService(WiniumDriverService winiumService) {
        if (!winiumService.isRunning()) {
            String msgPrefix = "Winium Driver Service ";

            //try { Thread.sleep(10000); } catch (InterruptedException e) { }
            ConsoleUtils.log(msgPrefix + "starting...");

            try {
                winiumService.start();
            } catch (IOException e) {
                String msg = msgPrefix + "failed to start due to " + e.getMessage();
                ConsoleUtils.error(msg);
                throw new RuntimeException(msg, e);
            }

            ConsoleUtils.log(msgPrefix + "started.");
            //			try { Thread.sleep(1500); } catch (InterruptedException e) { }
        }
    }

    protected static DesktopOptions resolveDesktopOptions(String exePath, String arguments) throws IOException {
        // assert StringUtils.isNotBlank(exePath);

        DesktopOptions options = new DesktopOptions();

        if (StringUtils.isNotBlank(exePath)) {
            if (!FileUtil.isFileExecutable(exePath)) { throw new IOException("AUT EXE is not executable: " + exePath); }

            ConsoleUtils.log("determined that AUT exe is found at " + exePath);
            options.setApplicationPath(exePath);

            if (StringUtils.isNotBlank(arguments)) {
                ConsoleUtils.log("command line arguments = " + arguments);
                options.setArguments(arguments);
            }
        }

        //options.setKeyboardSimulator(BasedOnWindowsFormsSendKeysClass);
        ExecutionContext context = ExecutionThread.get();
        boolean joinExisting = ExecutionContext.getSystemThenContextBooleanData(WINIUM_JOIN, context, false);
        options.setDebugConnectToRunningApp(joinExisting);

        return options;
    }

    protected static MutablePair<String, String> toExeAndArgs(String autCmd) throws IOException {
        MutablePair<String, String> exeAndArgument = new MutablePair<>();

        // aut command might contain arguments?
        autCmd = StringUtils.trim(autCmd);
        if (StringUtils.startsWith(autCmd, "\"")) {
            int indexNextDoubleQuote = StringUtils.indexOf(autCmd, "\"", 3);
            if (indexNextDoubleQuote == -1) { throw new IOException("Invalid EXE path AUT: " + autCmd); }

            // aut command might be appended with cmdline arguments (i.e. "C:\blah\yada\My Program.exe" -t 1 -d true)
            String exePath = StringUtils.trim(StringUtils.substring(autCmd, 1, indexNextDoubleQuote));
            String arguments = StringUtils.trim(StringUtils.substringAfter(autCmd, exePath));
            arguments = StringUtils.trim(StringUtils.removeStart(arguments, "\""));

            exeAndArgument.setLeft(exePath);
            exeAndArgument.setRight(arguments);
        } else {
            // no double quote, then exe and arguments are separate by space
            String exePath = StringUtils.trim(StringUtils.substringBefore(autCmd, " "));
            String arguments = StringUtils.trim(StringUtils.substringAfter(autCmd, exePath));
            exeAndArgument.setLeft(exePath);
            exeAndArgument.setRight(arguments);
        }
        return exeAndArgument;
    }
}