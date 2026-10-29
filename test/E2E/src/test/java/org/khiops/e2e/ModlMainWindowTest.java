// Copyright (c) 2023-2026 Orange. All rights reserved.
// This software is distributed under the BSD 3-Clause-clear License, the text of which is available
// at https://spdx.org/licenses/BSD-3-Clause-Clear.html or see the "LICENSE" file for more details.

package org.khiops.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.assertj.swing.image.ScreenshotTaker;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ModlMainWindowTest
{
        // Default scenario, loaded from the test classpath (override with -Dkhiops.scenario=<file>)
        private static final String SCENARIO_RESOURCE = "/modl-scenario.txt";

        // Matches ${variableName} placeholders inside scenario arguments
        private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

        @Test void launchesModlAndCapturesMainScreen() throws Exception
        {
                Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                                       "MODL UI test relies on the Win32 API");

                Path executable = Paths.get(System.getProperty("khiops.executable")).toAbsolutePath().normalize();
                Path jarDirectory = Paths.get(System.getProperty("khiops.jar.dir")).toAbsolutePath().normalize();
                Path outputDirectory = Paths.get("target", "screenshots").toAbsolutePath().normalize();
                Path logFile = Paths.get("target", "modl-e2e.log").toAbsolutePath().normalize();
                Path irisDataDirectory =
                  Paths.get("..", "LearningTest", "datasets", "Iris").toAbsolutePath().normalize();
                Path dictionaryFile = irisDataDirectory.resolve("Iris.kdic");
                Path databaseFile = irisDataDirectory.resolve("Iris.txt");

                assertThat(executable).as("MODL executable").isRegularFile();
                assertThat(jarDirectory.resolve("norm.jar")).as("norm.jar").isRegularFile();
                assertThat(jarDirectory.resolve("khiops.jar")).as("khiops.jar").isRegularFile();
                assertThat(dictionaryFile).as("Iris dictionary").isRegularFile();
                assertThat(databaseFile).as("Iris database").isRegularFile();

                // Read the scenario first so that a missing or unreadable file fails before MODL is started
                List<String> scenario = readScenario();

                Files.createDirectories(outputDirectory);
                Files.createDirectories(logFile.getParent());

                ProcessBuilder processBuilder = new ProcessBuilder(executable.toString());
                processBuilder.directory(executable.getParent().toFile());
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(logFile.toFile());

                Map<String, String> environment = processBuilder.environment();
                environment.put("CLASSPATH", buildClasspath(jarDirectory));
                prependRuntimePaths(environment, executable.getParent());

                // The scenario overwrites the system clipboard, so keep its content to restore it afterwards
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                Transferable savedClipboard = readClipboard(clipboard);

                Process modl = processBuilder.start();
                try {
                        long processId = modl.pid();
                        WindowInfo mainWindow =
                          awaitMainWindow(modl, Long.getLong("khiops.startup.wait.millis", 30000L), logFile);
                        User32.INSTANCE.ShowWindow(mainWindow.handle, WinUser.SW_RESTORE);
                        User32.INSTANCE.SetForegroundWindow(mainWindow.handle);
                        TimeUnit.MILLISECONDS.sleep(500);

                        Robot robot = new Robot();
                        robot.setAutoDelay(100);
                        robot.setAutoWaitForIdle(true);

                        ScenarioContext context = new ScenarioContext(robot, processId, mainWindow, outputDirectory,
                                                                      logFile);
                        context.variables.put("dictionaryFile", dictionaryFile.toString());
                        context.variables.put("databaseFile", databaseFile.toString());

                        runScenario(scenario, context);
                } finally {
                        stopModl(modl);
                        restoreClipboard(clipboard, savedClipboard);
                }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Scenario interpreter
        // ---------------------------------------------------------------------------------------------------------

        private static List<String> readScenario() throws IOException
        {
                String overridePath = System.getProperty("khiops.scenario");
                if (overridePath != null) {
                        return Files.readAllLines(Paths.get(overridePath), StandardCharsets.UTF_8);
                }
                try (InputStream stream = ModlMainWindowTest.class.getResourceAsStream(SCENARIO_RESOURCE)) {
                        assertThat(stream).as("scenario resource %s", SCENARIO_RESOURCE).isNotNull();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                        return reader.lines().collect(Collectors.toList());
                }
        }

        // One line = one command; blank lines and '#' comments are ignored
        private static void runScenario(List<String> scenario, ScenarioContext context) throws InterruptedException
        {
                for (int index = 0; index < scenario.size(); index++) {
                        String line = scenario.get(index).strip();
                        if (line.isEmpty() || line.startsWith("#")) {
                                continue;
                        }
                        String[] parts = line.split("\\s+", 2);
                        try {
                                String argument = parts.length > 1 ? context.expand(parts[1]) : "";
                                executeCommand(context, parts[0], argument);
                        } catch (InterruptedException e) {
                                throw e;
                        } catch (Exception | AssertionError e) {
                                // Report the scenario line so the faulty step is immediately visible
                                throw new AssertionError(
                                  "Scenario line " + (index + 1) + " failed: '" + line + "' (" + e.getMessage() + ")",
                                  e);
                        }
                }
        }

        private static void executeCommand(ScenarioContext context, String command, String argument) throws Exception
        {
                Robot robot = context.robot;
                switch (command) {
                // tab [count]: press TAB count times (default 1)
                case "tab":
                        int count = argument.isEmpty() ? 1 : Integer.parseInt(argument);
                        for (int i = 0; i < count; i++) {
                                pressTab(robot);
                        }
                        break;
                // key <NAME>: press and release a single key (ENTER, ESCAPE, RIGHT, F2...)
                case "key":
                        pressKey(robot, resolveKey(argument));
                        break;
                // chord <MODIFIER>+<KEY>: for instance ALT+D or CTRL+O
                case "chord":
                        String[] keys = argument.split("\\+");
                        if (keys.length != 2) {
                                throw new IllegalArgumentException("a chord needs exactly two keys: " + argument);
                        }
                        pressChord(robot, resolveKey(keys[0]), resolveKey(keys[1]));
                        break;
                // mnemonic <LETTER>: select a tab through its Alt mnemonic
                case "mnemonic":
                        selectTabByMnemonic(robot, resolveKey(argument));
                        break;
                case "next-tab":
                        selectNextTab(robot);
                        break;
                // paste <text>: replace the content of the focused field
                case "paste":
                        pasteFocusedValue(robot, argument);
                        break;
                // combo <text>: set the value of the focused combo box and validate it
                case "combo":
                        setFocusedComboValue(robot, argument);
                        break;
                case "wait":
                        TimeUnit.MILLISECONDS.sleep(Long.parseLong(argument));
                        break;
                case "focus":
                        User32.INSTANCE.SetForegroundWindow(context.mainWindow.handle);
                        break;
                // dialog <title> <file>: pick a file in a file chooser dialog
                case "dialog":
                        String[] dialogArguments = argument.split("\\s+", 2);
                        if (dialogArguments.length != 2) {
                                throw new IllegalArgumentException("expected '<title> <file>' but got: " + argument);
                        }
                        chooseFileInDialog(robot, context.processId, dialogArguments[0],
                                           Paths.get(dialogArguments[1]), context.logFile);
                        break;
                // shot <file.png>: capture the main window
                case "shot":
                        captureWindowScreenshot(robot, context.mainWindow.handle,
                                                context.outputDirectory.resolve(argument));
                        break;
                // mark: take a reference capture (call it before opening the menu)
                case "mark": {
                        robot.mouseMove(0, 0);
                        robot.delay(500);
                        Rectangle windowBounds = getWindowBounds(context.mainWindow.handle);
                        assertThat(windowBounds).as("MODL visible frame bounds").isNotNull();
                        // Extra height so that a popup extending below the window is also covered
                        context.baselineRegion = new Rectangle(
                          windowBounds.x, windowBounds.y, windowBounds.width, windowBounds.height + 400);
                        context.baseline = robot.createScreenCapture(context.baselineRegion);
                        break;
                }
                // shot-menu <file.png>: capture the menu bar together with the opened menu popup
                case "shot-menu":
                        // With a 'mark' reference, crop on the changed area (popup width, anything wider is cut)
                        if (context.baseline != null) {
                                captureChangedArea(robot, context, context.outputDirectory.resolve(argument));
                                break;
                        }
                        captureDataDictionaryMenu(robot, context.processId, context.mainWindow.handle,
                                                  context.outputDirectory.resolve(argument));
                        break;
                default:
                        throw new IllegalArgumentException("unknown command '" + command + "'");
                }
        }

        // Accepts CTRL, ALT, SHIFT, a single letter or digit, or any KeyEvent.VK_ suffix (ENTER, TAB, F2...)
        private static int resolveKey(String name)
        {
                String upperName = name.strip().toUpperCase();
                switch (upperName) {
                case "CTRL":
                        return KeyEvent.VK_CONTROL;
                case "ALT":
                        return KeyEvent.VK_ALT;
                case "SHIFT":
                        return KeyEvent.VK_SHIFT;
                default:
                        break;
                }
                if (upperName.length() == 1) {
                        return KeyEvent.getExtendedKeyCodeForChar(upperName.charAt(0));
                }
                try {
                        return KeyEvent.class.getField("VK_" + upperName).getInt(null);
                } catch (ReflectiveOperationException e) {
                        throw new IllegalArgumentException("unknown key '" + name + "'", e);
                }
        }

        private static final class ScenarioContext
        {
                private final Robot robot;
                private final long processId;
                private final WindowInfo mainWindow;
                private final Path outputDirectory;
                private final Path logFile;
                private final Map<String, String> variables = new HashMap<>();

                // Reference screen capture taken by the 'mark' command, used to crop on what changed afterwards
                private BufferedImage baseline;
                private Rectangle baselineRegion;

                private ScenarioContext(Robot robot, long processId, WindowInfo mainWindow, Path outputDirectory,
                                        Path logFile)
                {
                        this.robot = robot;
                        this.processId = processId;
                        this.mainWindow = mainWindow;
                        this.outputDirectory = outputDirectory;
                        this.logFile = logFile;
                }

                // Replaces every ${name} placeholder, failing on unknown variables
                private String expand(String text)
                {
                        Matcher matcher = VARIABLE_PATTERN.matcher(text);
                        StringBuffer result = new StringBuffer();
                        while (matcher.find()) {
                                String value = variables.get(matcher.group(1));
                                if (value == null) {
                                        throw new IllegalArgumentException("unknown variable '" + matcher.group(1) +
                                                                           "'");
                                }
                                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
                        }
                        matcher.appendTail(result);
                        return result.toString();
                }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Clipboard handling
        // ---------------------------------------------------------------------------------------------------------

        private static Transferable readClipboard(Clipboard clipboard)
        {
                try {
                        return clipboard.getContents(null);
                } catch (IllegalStateException e) {
                        // The clipboard may be locked by another application, nothing to restore in that case
                        return null;
                }
        }

        private static void restoreClipboard(Clipboard clipboard, Transferable savedContent)
        {
                if (savedContent == null) {
                        return;
                }
                try {
                        clipboard.setContents(savedContent, null);
                } catch (IllegalStateException e) {
                        // Best effort only
                }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Screenshots
        // ---------------------------------------------------------------------------------------------------------

        private static void captureWindowScreenshot(Robot robot, HWND window, Path screenshotFile) throws IOException
        {
                robot.mouseMove(0, 0);
                robot.delay(1000);
                Rectangle bounds = getWindowBounds(window);
                assertThat(bounds).as("MODL visible frame bounds").isNotNull();
                BufferedImage screenshot = robot.createScreenCapture(bounds);
                assertThat(screenshot.getWidth()).as("MODL window screenshot width").isPositive();
                assertThat(screenshot.getHeight()).as("MODL window screenshot height").isPositive();

                saveScreenshot(screenshot, screenshotFile);
        }

        private static void captureDataDictionaryMenu(Robot robot, long processId, HWND mainWindow, Path screenshotFile)
          throws IOException
        {
                Rectangle mainBounds = getWindowBounds(mainWindow);
                assertThat(mainBounds).as("MODL visible frame bounds").isNotNull();
                Rectangle menuBounds = findOwnedMenuBounds(processId, mainWindow);
                Rectangle screenshotBounds = mainBounds;
                if (menuBounds != null) {
                        int bottom = menuBounds.y + menuBounds.height;
                        screenshotBounds =
                          new Rectangle(menuBounds.x, mainBounds.y, menuBounds.width, bottom - mainBounds.y);
                }
                BufferedImage screenshot = robot.createScreenCapture(screenshotBounds);
                saveScreenshot(screenshot, screenshotFile);
        }

        // Crops the screenshot on the bounding box of the pixels that differ from the 'mark' reference capture
        private static void captureChangedArea(Robot robot, ScenarioContext context, Path screenshotFile)
          throws IOException
        {
                BufferedImage reference = context.baseline;
                BufferedImage current = robot.createScreenCapture(context.baselineRegion);
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = -1;
                int maxY = -1;
                for (int y = 0; y < current.getHeight(); y++) {
                        for (int x = 0; x < current.getWidth(); x++) {
                                if (current.getRGB(x, y) != reference.getRGB(x, y)) {
                                        minX = Math.min(minX, x);
                                        minY = Math.min(minY, y);
                                        maxX = Math.max(maxX, x);
                                        maxY = Math.max(maxY, y);
                                }
                        }
                }
                assertThat(maxX).as("changed pixels (the menu did not open?)").isGreaterThanOrEqualTo(0);
                saveScreenshot(current.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1), screenshotFile);
        }

        private static Rectangle findOwnedMenuBounds(long processId, HWND mainWindow)
        {
                AtomicReference<Rectangle> popupBounds = new AtomicReference<>();
                User32.INSTANCE.EnumWindows(new WNDENUMPROC() {
                        @Override public boolean callback(HWND window, Pointer data)
                        {
                                IntByReference ownerProcessId = new IntByReference();
                                User32.INSTANCE.GetWindowThreadProcessId(window, ownerProcessId);
                                HWND ownerWindow = User32.INSTANCE.GetWindow(window, new DWORD(WinUser.GW_OWNER));
                                if (ownerProcessId.getValue() != processId ||
                                    !User32.INSTANCE.IsWindowVisible(window) || ownerWindow == null ||
                                    Pointer.nativeValue(ownerWindow.getPointer()) !=
                                      Pointer.nativeValue(mainWindow.getPointer())) {
                                        return true;
                                }

                                Rectangle bounds = getWindowBounds(window);
                                if (bounds != null && bounds.width > 0 && bounds.width <= 400 && bounds.height > 0 &&
                                    bounds.height <= 320) {
                                        Rectangle currentBounds = popupBounds.get();
                                        if (currentBounds == null ||
                                            bounds.width * bounds.height > currentBounds.width * currentBounds.height) {
                                                popupBounds.set(bounds);
                                        }
                                }
                                return true;
                        }
                }, null);
                return popupBounds.get();
        }

        private static void saveScreenshot(BufferedImage screenshot, Path screenshotFile) throws IOException
        {
                assertThat(screenshot.getWidth()).as("screenshot width").isPositive();
                assertThat(screenshot.getHeight()).as("screenshot height").isPositive();
                Files.deleteIfExists(screenshotFile);
                new ScreenshotTaker().saveImage(screenshot, screenshotFile.toString());
                assertThat(Files.isRegularFile(screenshotFile)).as("screenshot at %s", screenshotFile).isTrue();
                assertThat(ImageIO.read(screenshotFile.toFile())).as("saved screenshot").isNotNull();
        }

        // ---------------------------------------------------------------------------------------------------------
        // Keyboard helpers
        // ---------------------------------------------------------------------------------------------------------

        private static void pasteFocusedValue(Robot robot, String value)
        {
                pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_A);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
                pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
        }

        private static void setFocusedComboValue(Robot robot, String value)
        {
                pasteFocusedValue(robot, value);
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
        }

        private static void pressTab(Robot robot) { pressKey(robot, KeyEvent.VK_TAB); }

        private static void pressKey(Robot robot, int key)
        {
                robot.keyPress(key);
                robot.keyRelease(key);
        }

        private static void selectTabByMnemonic(Robot robot, int mnemonic) throws InterruptedException
        {
                pressChord(robot, KeyEvent.VK_ALT, mnemonic);
                TimeUnit.MILLISECONDS.sleep(300);
        }

        private static void selectNextTab(Robot robot) throws InterruptedException
        {
                pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_TAB);
                TimeUnit.MILLISECONDS.sleep(300);
        }

        private static void chooseFileInDialog(Robot robot, long processId, String dialogTitle, Path file, Path logFile)
          throws InterruptedException
        {
                awaitWindowTitle(processId, dialogTitle, Long.getLong("khiops.dialog.wait.millis", 10000L), logFile);

                pressChord(robot, KeyEvent.VK_ALT, KeyEvent.VK_N);
                pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_A);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(file.toString()),
                                                                             null);
                pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);

                awaitWindowTitleClosed(
                  processId, dialogTitle, Long.getLong("khiops.dialog.wait.millis", 10000L), logFile);
        }

        private static void pressChord(Robot robot, int modifierKey, int key)
        {
                robot.keyPress(modifierKey);
                robot.keyPress(key);
                robot.keyRelease(key);
                robot.keyRelease(modifierKey);
        }

        // ---------------------------------------------------------------------------------------------------------
        // Process and window helpers
        // ---------------------------------------------------------------------------------------------------------

        private static String buildClasspath(Path jarDirectory) throws IOException
        {
                try (Stream<Path> jars = Files.list(jarDirectory)) {
                        return jars.filter(path -> path.getFileName().toString().endsWith(".jar"))
                          .sorted()
                          .map(Path::toString)
                          .collect(Collectors.joining(File.pathSeparator));
                }
        }

        private static void prependRuntimePaths(Map<String, String> environment, Path executableDirectory)
        {
                Path javaHome = Paths.get(System.getProperty("java.home"));
                String runtimePaths = executableDirectory + File.pathSeparator +
                                      javaHome.resolve("bin").resolve("server") + File.pathSeparator +
                                      javaHome.resolve("bin");
                String currentPath = environment.get("PATH");
                environment.put("PATH", runtimePaths + (currentPath == null ? "" : File.pathSeparator + currentPath));
        }

        private static WindowInfo awaitMainWindow(Process modl, long waitMillis, Path logFile)
          throws InterruptedException
        {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
                while (System.nanoTime() < deadline) {
                        if (!modl.isAlive()) {
                                fail("MODL exited before its main window was captured (exit code " + modl.exitValue() +
                                     "). See " + logFile);
                        }
                        WindowInfo mainWindow = findMainWindow(modl.pid());
                        if (mainWindow != null) {
                                return mainWindow;
                        }
                        long remainingNanos = deadline - System.nanoTime();
                        TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(250)));
                }
                fail("MODL did not open a visible main window within " + waitMillis + " ms. See " + logFile);
                return null;
        }

        private static WindowInfo findMainWindow(long processId)
        {
                AtomicReference<WindowInfo> largestWindow = new AtomicReference<>();
                User32.INSTANCE.EnumWindows(new WNDENUMPROC() {
                        @Override public boolean callback(HWND window, Pointer data)
                        {
                                IntByReference ownerProcessId = new IntByReference();
                                User32.INSTANCE.GetWindowThreadProcessId(window, ownerProcessId);
                                if (ownerProcessId.getValue() != processId ||
                                    !User32.INSTANCE.IsWindowVisible(window) ||
                                    User32.INSTANCE.GetWindow(window, new DWORD(WinUser.GW_OWNER)) != null) {
                                        return true;
                                }

                                Rectangle bounds = getWindowBounds(window);
                                if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                                        return true;
                                }

                                WindowInfo candidate = new WindowInfo(window, bounds);
                                WindowInfo current = largestWindow.get();
                                if (current == null || candidate.area() > current.area()) {
                                        largestWindow.set(candidate);
                                }
                                return true;
                        }
                }, null);
                return largestWindow.get();
        }

        private static WindowInfo awaitWindowTitle(long processId, String title, long waitMillis, Path logFile)
          throws InterruptedException
        {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
                while (System.nanoTime() < deadline) {
                        WindowInfo dialog = findWindowTitle(processId, title);
                        if (dialog != null) {
                                return dialog;
                        }
                        TimeUnit.MILLISECONDS.sleep(100);
                }
                fail("MODL did not open the '" + title + "' dialog within " + waitMillis + " ms. See " + logFile);
                return null;
        }

        private static void awaitWindowTitleClosed(long processId, String title, long waitMillis, Path logFile)
          throws InterruptedException
        {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
                while (System.nanoTime() < deadline) {
                        if (findWindowTitle(processId, title) == null) {
                                return;
                        }
                        TimeUnit.MILLISECONDS.sleep(100);
                }
                fail("MODL '" + title + "' dialog did not close within " + waitMillis + " ms. See " + logFile);
        }

        private static WindowInfo findWindowTitle(long processId, String expectedTitle)
        {
                AtomicReference<WindowInfo> matchingWindow = new AtomicReference<>();
                User32.INSTANCE.EnumWindows(new WNDENUMPROC() {
                        @Override public boolean callback(HWND window, Pointer data)
                        {
                                IntByReference ownerProcessId = new IntByReference();
                                User32.INSTANCE.GetWindowThreadProcessId(window, ownerProcessId);
                                if (ownerProcessId.getValue() != processId ||
                                    !User32.INSTANCE.IsWindowVisible(window)) {
                                        return true;
                                }

                                char[] title = new char[256];
                                int titleLength = User32.INSTANCE.GetWindowText(window, title, title.length);
                                if (titleLength > 0 && expectedTitle.equals(Native.toString(title))) {
                                        Rectangle bounds = getWindowBounds(window);
                                        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                                                matchingWindow.set(new WindowInfo(window, bounds));
                                                return false;
                                        }
                                }
                                return true;
                        }
                }, null);
                return matchingWindow.get();
        }

        private static Rectangle getWindowBounds(HWND window)
        {
                RECT bounds = new RECT();
                int result =
                  DwmApi.INSTANCE.DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, bounds, bounds.size());
                if (result != 0) {
                        return null;
                }
                return new Rectangle(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top);
        }

        private interface DwmApi extends StdCallLibrary
        {
                DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class, W32APIOptions.DEFAULT_OPTIONS);

                int DwmGetWindowAttribute(HWND window, int attribute, RECT value, int valueSize);
        }

        private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

        private static final class WindowInfo
        {
                private final HWND handle;
                private final Rectangle bounds;

                private WindowInfo(HWND handle, Rectangle bounds)
                {
                        this.handle = handle;
                        this.bounds = bounds;
                }

                private long area() { return (long)bounds.width * bounds.height; }
        }

        private static void stopModl(Process modl) throws InterruptedException
        {
                modl.destroy();
                if (!modl.waitFor(5, TimeUnit.SECONDS)) {
                        modl.destroyForcibly();
                        modl.waitFor(5, TimeUnit.SECONDS);
                }
        }
}
