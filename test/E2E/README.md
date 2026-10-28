# Khiops GUI E2E tests

These tests launch the native Khiops application and use AssertJ Swing to capture the interactive desktop. Run them in a Windows desktop session; headless or locked sessions cannot provide a useful screenshot.

## Prerequisites

- Java 11 or later and Maven
- A Khiops build containing `MODL.exe`, `norm.jar`, and `khiops.jar` plus the GUI dependency JARs
- A logged-in desktop session

Configure and build the Windows GUI targets from a Visual Studio Developer PowerShell session
(so that `cl.exe` is available on `PATH`), from the repository root:

```powershell
cmake --preset windows-msvc-release -D BUILD_JARS=ON
cmake --build --preset windows-msvc-release --target MODL norm_jar khiops_jar
```

Run the E2E suite from the `test\E2E` directory. The working directory is used to locate the
Iris fixtures in `test\LearningTest\datasets\Iris`:

```powershell
Set-Location test\E2E
mvn test
```

The first test starts MODL without command-line arguments, opens the repository's `Iris.kdic`
through the Data dictionary menu, then uses keyboard navigation to focus the Data table file
cell, starts editing it with Space, pastes the repository's `Iris.txt` path, and confirms with
Enter. It saves the window after setup, then selects the Sampling and Selection tabs and saves
a screenshot after each:

- `target/screenshots/DataDictionary_menu.png`
- `target/screenshots/Khiops_TrainDatabase.png`
- `target/screenshots/Khiops_TrainDatabase_Sampling.png`
- `target/screenshots/Khiops_TrainDatabase_Selection.png`
- `target/screenshots/Khiops_Parameters.png` after setting Target variable to `Class` and Main target value to `Iris-setosa`
- `target/screenshots/Khiops_Parameters_Advanced.png`
- `target/screenshots/Khiops_Parameters_Recorders.png`
- `target/screenshots/Khiops_Parameters_Preprocessing.png`
- `target/screenshots/Khiops_Parameters_SystemParameters.png`

Combined application output is saved to `target/modl-e2e.log`.

Override the default build paths when needed:

```powershell
mvn test `
  "-Dkhiops.executable=C:/Applications/Workspace/khiops/khiops/build/lib/MODL.exe" `
  "-Dkhiops.jar.dir=C:/Applications/Workspace/khiops/khiops/build/lib/jars"
```

The startup wait can be changed with `-Dkhiops.startup.wait.millis=15000`.

## GitHub Actions and local checks

The `E2E screenshots` workflow compiles the Java tests on Ubuntu, then builds Khiops and runs the
GUI suite on a Windows runner. Screenshots and `modl-e2e.log` are uploaded as the
`khiops-e2e-screenshots` artifact, including when the test fails.

Install and start Docker Desktop with Linux containers, then install `act` (for example,
`choco install act-cli`). The repository's `.actrc` selects the Ubuntu runner image. Run the
Ubuntu compile job locally from the repository root:

```powershell
act pull_request -W .github/workflows/e2e-screenshots.yml -j e2e-compile
```

`act` runs workflow jobs in Docker containers and cannot provide the Windows desktop APIs used
by the screenshot test. To run the actual GUI test and produce screenshots locally, use the
Windows desktop-session instructions above (`mvn test` from `test\E2E`).
