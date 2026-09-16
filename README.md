[![official JetBrains project](http://jb.gg/badges/official.svg)](https://github.com/JetBrains/.github/blob/main/profile/README.md) [![IntelliJ IDEA build status](https://github.com/JetBrains/intellij-community/actions/workflows/IntelliJ_IDEA.yml/badge.svg?branch=master)](https://github.com/JetBrains/intellij-community/actions/workflows/IntelliJ_IDEA.yml?query=branch%3Amaster) [![PyCharm build status](https://github.com/JetBrains/intellij-community/actions/workflows/PyCharm.yml/badge.svg?branch=master)](https://github.com/JetBrains/intellij-community/actions/workflows/PyCharm.yml?query=branch%3Amaster)

# IntelliJ Open Source Repository

This repository is the open-source part of the JetBrains IDEs codebase.
It also serves as the basis for [IntelliJ Platform development](https://www.jetbrains.com/opensource/idea). 

These instructions will help you build and run open source parts of IntelliJ Platform / IntelliJ IDEA / PyCharm.

If you are new to the community and would like to contribute code or help others learn,
see [CONTRIBUTING.md](https://github.com/JetBrains/intellij-community/blob/master/CONTRIBUTING.md) to get started.

The following conventions will be used to refer to directories on your machine:
* `<USER_HOME>` is your OS user's home directory.
* `<IDEA_HOME>` is the root directory for the **IntelliJ source code**.


---
## Getting the Source Code

This section will guide you through getting the project sources and help avoid common issues in Git config and other steps before opening it in the IDE.

### Prerequisites

- Install [Git](https://git-scm.com/).
- On **Windows**, set these Git options to avoid common issues during cloning:
  ```shell
  git config --global core.longpaths true
  git config --global core.autocrlf input
  ```

### Clone Main Repository

IntelliJ open source repository is available from the [GitHub repository](https://github.com/JetBrains/intellij-community),
which can be cloned or downloaded as a zip file (based on a branch) into `<IDEA_HOME>`. 
The **master** (_default_) branch contains the source code which will be used to create the next major version of all JetBrains IDEs. 
The branch names and build numbers for older releases of JetBrains IDEs can be found on the
[Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html) page.

You can [clone this project](https://www.jetbrains.com/help/idea/manage-projects-hosted-on-github.html#clone-from-GitHub) using IntelliJ IDEA
(see [Opening the IntelliJ IDEA Source Code in the IDE](#opening-the-intellij-idea-source-code-in-the-ide)). 

Alternatively, run the following commands in the terminal:
   ```shell
   git clone https://github.com/JetBrains/intellij-community.git
   cd intellij-community
   ```

> [!TIP]
> - **For faster download**: If the complete repository history isn't needed,
> create [shallow clone](https://git-scm.com/docs/git-clone#Documentation/git-clone.txt---depthdepth).
> To download only the latest revision of the repository,  add `--depth 1` option after `clone`.
> - Cloning in IntelliJ IDEA also supports creating shallow clone.

### Get Android Modules

IntelliJ IDEA requires additional Android modules from separate Git repositories.
Run the following script from project root `<IDEA_HOME>` to get the required modules:
- Unix: `./getPlugins.sh`
- Windows: `getPlugins.bat`

> [!IMPORTANT]
>
>  Always `git checkout` the `intellij-community` and `android` Git repositories to the same branches/tags.


---
## Building IntelliJ IDEA

These instructions will help you build IntelliJ IDEA from source code, which is the basis for IntelliJ Platform development.

> [!IMPORTANT]
>
> IntelliJ IDEA project is currently being migrated to the [Bazel](https://bazel.build/) build system. 
> The migration is still in progress, so you may encounter some rough edges or temporary issues along the way, mostly related to IDE integration.
> * Building the project using only IDE built-in capabilities is not supported anymore,
> so make sure the [Bazel plugin](https://plugins.jetbrains.com/plugin/22977-bazel) is installed and enabled.
> * Known issue: some tests are not yet possible to be run with Bazel. In case of any issues,
> please depend on the `tests.cmd` script mentioned in the [Running IntelliJ IDEA in a CI/CD environment](#running-intellij-idea-in-a-cicd-environment) section.

### Opening the IntelliJ IDEA Source Code in the IDE

**Prerequisites**:
- Install [IntelliJ IDEA 2026.1](https://www.jetbrains.com/idea/download) or newer.
- Install the [Bazel plugin](https://plugins.jetbrains.com/plugin/22977-bazel) (via _Settings | Plugins | Marketplace_).
- If the **Maven** plugin is disabled, [add the path variable](https://www.jetbrains.com/help/idea/absolute-path-variables.html)
  `MAVEN_REPOSITORY` pointing to `<USER_HOME>/.m2/repository` directory.

Using the latest IntelliJ IDEA, click _File | Open_ and select the `<IDEA_HOME>/.bazelproject`.
If IntelliJ IDEA displays a message about a missing or out-of-date required plugin (e.g., Kotlin),
[enable, upgrade, or install that plugin](https://www.jetbrains.com/help/idea/managing-plugins.html) and restart IntelliJ IDEA.

### Building the IntelliJ IDEA Application

**In IntelliJ IDEA**, choose _Build | Build Project_ from the main menu.

**From the command line**, run the [bazel-build-all-community.cmd](bazel-build-all-community.cmd) script
in the `<IDEA_HOME>` directory (**note**: all `*.cmd` scripts work on both Windows and Unix systems).

**To build installation packages**, run the [installers.cmd](installers.cmd) script in the `<IDEA_HOME>` directory.
Options to build installers are passed as system properties to `installers.cmd` command.
You may find the list of available properties in [BuildOptions.kt](platform/build-scripts/src/org/jetbrains/intellij/build/BuildOptions.kt)

Pass `--debug` to suspend and wait for the debugger to attach to port 5005.

Installer build examples:
```shell
# Build installers only for current operating system:
./installers.cmd -Dintellij.build.target.os=current
```

> [!TIP]
> 
> The `installers.cmd` is used to run [OpenSourceCommunityInstallersBuildTarget](build/src/OpenSourceCommunityInstallersBuildTarget.kt) from the command line.
> You can also call it directly from IDEA, using run configuration `Build IntelliJ IDEA Installers (current OS)`.
> Equivalently, run the build target via Bazel directly. Note that every argument after `--`
> is passed to the build target prefixed with `--jvm_flag=`:
> ```shell
> ./bazel.cmd run //build:i_build_target -- --jvm_flag=-Dintellij.build.target.os=current
> ```

> [!IMPORTANT]
>
> **Where the build output goes.** The build output root is `out/idea-ce/` (not the plain `out/`):
> * Finished installers are written to `out/idea-ce/artifacts/`, e.g. `ideaIC-<version>-aarch64.dmg`
>   and a universal `.dmg`/`.sit` on macOS.
> * JPS build logs live in `out/idea-ce/log`, and the build target's console output is useful for
>   diagnosing failures.
> * Intermediate distribution pieces are staged in `out/idea-ce/dist.all/` (shared `lib/` with all
>   jars, `bin/`, `plugins/`) and `out/idea-ce/dist.mac.aarch64/` (macOS-specific overlay: `Info.plist`,
>   `MacOS/`, `Resources/` — no `lib/`). The product `.app` bundle is the two overlaid.

> [!IMPORTANT]
>
> **On memory-limited machines** (e.g. 16 GB RAM), the Bazel server plus 2×CPU compile workers
> routinely exhaust memory and the build gets killed mid-compile. Cap the server heap, the job
> count, and per-worker multiplexing. `--host_jvm_args` is a *startup* option and must come before
> the `run` subcommand:
> ```shell
> ./bazel.cmd --host_jvm_args=-Xmx2g run --jobs=4 --worker_max_multiplex_instances=2 -- //build:i_build_target -- --jvm_flag=-Xmx3g -Dintellij.build.target.os=current
> ```
> Expect swap usage to climb (tens of GB) on such machines even with these caps.

> [!IMPORTANT]
>
> **Apple macOS SDK download.** During analysis, the build fetches `CLTools_macOSNMOS_SDK.pkg`
> from the JetBrains download redirector, which can return `401 Unauthorized` (the Apple CDN itself
> returns 403). Workaround: copy `build/bazel_downloader.cfg` to e.g. `out/downloader.cfg` (an
> untracked file), add the line `allow web.archive.org`, and pass the config to Bazel:
> ```shell
> ./bazel.cmd --downloader_config="$PWD/out/downloader.cfg" run //build:i_build_target ...
> ```
> `web.archive.org` serves the same `...id_/...` SDK URL (also listed as a fallback in `MODULE.bazel`).

### Dockerized Build Environment

To build installation packages inside a Docker container with preinstalled dependencies and tools,
run the following command in `<IDEA_HOME>` directory (on Windows, use PowerShell):
```shell
docker build . --target intellij_idea --tag intellij_idea_env
docker run --rm --user "$(id -u)" --volume "${PWD}:/community" intellij_idea_env
```
> [!NOTE]
> 
> Please remember to specify the `--user "$(id -u)"` argument for the container's user to match the host's user.
> This prevents issues with permissions for the checked-out repository, the build output, if any.


---
## Running IntelliJ IDEA from IDEA

To run the IntelliJ IDEA that was built from source, choose _Run | Run..._ from the main menu
and choose the preconfigured run configuration `Run //build:idea_community`.

To run tests on the build, apply these settings to the _Run | Edit Configurations... | Templates | JUnit_ configuration tab:
* Working dir: `<IDEA_HOME>/bin`
* VM options:  `-ea`

## Running IntelliJ IDEA from the command line

```shell
./bazel.cmd run //build:idea_community
```

### Running the built installer (macOS `.app` bundle)

The `.dmg`/`.sit` installers in `out/idea-ce/artifacts/` contain a complete application bundle, e.g.:

```shell
open out/idea-ce/artifacts/ideaIC-263.SNAPSHOT-aarch64.dmg          # mounts at /Volumes/IntelliJ IDEA OSS
open "/Volumes/IntelliJ IDEA OSS/IntelliJ IDEA OSS.app"             # launches the app
```

> [!IMPORTANT]
>
> **The launcher runs the JVM in-process.** The `Contents/MacOS/idea` launcher (Rust "XPlat"
> launcher) creates the JVM via JNI inside its own process — a separate `java` child process never
> appears, so don't wait for one (`jstat`/`top`/`pgrep` for `jbr/.../bin/java` will find nothing).
> The bundled runtime is at `Contents/jbr/Contents/Home/bin/java` if you need to poke at it directly.

> [!IMPORTANT]
>
> **Possible startup deadlock: `PerfMemory::initialize` → `open_directory_secure_cwd`.**
> On some macOS setups the freshly built app hangs at JVM init with no window (the launcher process
> sits spinning with no children). Workaround: disable the perf-memory subsystem via user vmoptions
> (no need to touch the bundle, which lives on a read-only DMG):
> ```shell
> mkdir -p "$HOME/Library/Application Support/JetBrains/IdeaIC2026.3"
> printf -- '-XX:-UsePerfData\n' > "$HOME/Library/Application Support/JetBrains/IdeaIC2026.3/idea.vmoptions"
> open "/Volumes/IntelliJ IDEA OSS/IntelliJ IDEA OSS.app"
> ```
> (`JAVA_TOOL_OPTIONS=-XX:-UsePerfData` at launch also works — JNI-created VMs honor it, while
> `JDK_JAVA_OPTIONS` does not.)

### Running IntelliJ IDEA in a CI/CD environment

To run tests outside IntelliJ IDEA, use the `tests.cmd` command in `<IDEA_HOME>` directory.
Options to run tests are passed as system properties to `tests.cmd` command.
You may find the list of available properties in [TestingOptions.kt](platform/build-scripts/src/org/jetbrains/intellij/build/TestingOptions.kt)

```shell
# Run specific run configuration:
./tests.cmd -Dintellij.build.test.configurations=ApiCheckTest
```
```shell
# Run a specific test: 
./tests.cmd -Dintellij.build.test.patterns=com.intellij.util.ArrayUtilTest
```

To debug tests, use: `-Dintellij.build.test.debug.enabled=true -Dintellij.build.test.debug.suspend=true -Dintellij.build.test.debug.port=5005`.

`tests.cmd` is used just to run [CommunityRunTestsBuildTarget](build/src/CommunityRunTestsBuildTarget.kt) from the command line.
You can also call it directly from IDEA, see run configuration `tests` for an example.
