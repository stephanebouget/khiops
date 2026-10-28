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
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.assertj.swing.image.ScreenshotTaker;
import org.junit.jupiter.api.Test;

class ModlMainWindowTest
{
        // Mnemonic (underlined letter) of the "Data Dictionary" menu in the menu bar
        private static final int DATA_DICTIONARY_MENU_MNEMONIC = KeyEvent.VK_D;

        @Test void launchesModlAndCapturesMainScreen() throws Exception
        {
                Path executable = Paths.get(System.getProperty("khiops.executable")).toAbsolutePath().normalize();
                Path jarDirectory = Paths.get(System.getProperty("khiops.jar.dir")).toAbsolutePath().normalize();
                Path outputDirectory = Paths.get("target", "screenshots").toAbsolutePath().normalize();
                Path trainDatabaseScreenshot = outputDirectory.resolve("Khiops_TrainDatabase.png");
                Path samplingScreenshot = outputDirectory.resolve("Khiops_TrainDatabase_Sampling.png");
                Path selectionScreenshot = outputDirectory.resolve("Khiops_TrainDatabase_Selection.png");
                Path parametersScreenshot = outputDirectory.resolve("Khiops_Parameters.png");
                Path advancedParametersScreenshot = outputDirectory.resolve("Khiops_Parameters_Advanced.png");
                Path recodersScreenshot = outputDirectory.resolve("Khiops_Parameters_Recorders.png");
                Path preprocessingScreenshot = outputDirectory.resolve("Khiops_Parameters_Preprocessing.png");
                Path systemParametersScreenshot = outputDirectory.resolve("Khiops_Parameters_SystemParameters.png");
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

                Files.createDirectories(outputDirectory);
                Files.createDirectories(logFile.getParent());

                ProcessBuilder processBuilder = new ProcessBuilder(executable.toString());
                processBuilder.directory(executable.getParent().toFile());
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(logFile.toFile());

                Map<String, String> environment = processBuilder.environment();
                environment.put("CLASSPATH", buildClasspath(jarDirectory));
                prependRuntimePaths(environment, executable.getParent());

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

                        // Open the "Data Dictionary" menu with its mnemonic instead of a pixel click
                        pressChord(robot, KeyEvent.VK_ALT, DATA_DICTIONARY_MENU_MNEMONIC);

                        TimeUnit.MILLISECONDS.sleep(500);
                        captureDataDictionaryMenu(
                          robot, processId, mainWindow.handle, outputDirectory.resolve("DataDictionary_menu.png"));
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                        TimeUnit.MILLISECONDS.sleep(200);

                        pressChord(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_O);
                        chooseFileInDialog(robot, processId, "Open", dictionaryFile, logFile);

                        User32.INSTANCE.SetForegroundWindow(mainWindow.handle);
                        pressTab(robot);
                        pressTab(robot);
                        pressTab(robot);
                        pressTab(robot);
                        pressTab(robot);
                        pressTab(robot);
                        pressTab(robot);
                        pressKey(robot, KeyEvent.VK_RIGHT);
                        pressKey(robot, KeyEvent.VK_F2);
                        pasteFocusedValue(robot, databaseFile.toString());
                        pressKey(robot, KeyEvent.VK_ENTER);
                        pressTab(robot);
                        captureWindowScreenshot(robot, mainWindow.handle, trainDatabaseScreenshot);

                        selectNextTab(robot);
                        captureWindowScreenshot(robot, mainWindow.handle, samplingScreenshot);

                        selectNextTab(robot);
                        captureWindowScreenshot(robot, mainWindow.handle, selectionScreenshot);

                        selectTabByMnemonic(robot, KeyEvent.VK_P);
                        TimeUnit.MILLISECONDS.sleep(300);
                        setFocusedComboValue(robot, "Class");
                        pressTab(robot);
                        setFocusedComboValue(robot, "Iris-setosa");
                        pressTab(robot);
                        captureWindowScreenshot(robot, mainWindow.handle, parametersScreenshot);

                        selectTabByMnemonic(robot, KeyEvent.VK_V);
                        captureWindowScreenshot(robot, mainWindow.handle, advancedParametersScreenshot);

                        selectTabByMnemonic(robot, KeyEvent.VK_O);
                        captureWindowScreenshot(robot, mainWindow.handle, recodersScreenshot);

                        selectTabByMnemonic(robot, KeyEvent.VK_G);
                        captureWindowScreenshot(robot, mainWindow.handle, preprocessingScreenshot);

                        selectTabByMnemonic(robot, KeyEvent.VK_S);
                        captureWindowScreenshot(robot, mainWindow.handle, systemParametersScreenshot);
                } finally {
                        stopModl(modl);
                }
        }

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
