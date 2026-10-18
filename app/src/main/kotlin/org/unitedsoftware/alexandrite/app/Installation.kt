package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ControlRequest
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** The variable the installed launcher sets to the directory the install script installed into. */
internal const val INSTALL_DIR = "ALEXANDRITE_INSTALL_DIR"

private const val VERSIONS = "versions"
private const val LAUNCHER = "alexandrite"

/** The lock file a runtime keeps in its data directory. */
private const val DATA_LOCK = "runtime.lock"
private val RELEASE = Regex("v?[0-9]+(\\.[0-9]+)*([-+.][0-9A-Za-z.]+)?")

/** The directory the install script installed into, else where it installs by default. */
internal fun installRoot(cli: Cli): Path {
    cli.environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
    val running = cli.install?.toAbsolutePath()?.normalize()
    running?.parent?.takeIf { it.fileName?.toString() == VERSIONS }?.parent?.let { return it }
    return when (cli.platform) {
        Platform.WINDOWS -> {
            val local = cli.environment["LOCALAPPDATA"]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
                ?: cli.home.resolve("AppData").resolve("Local")
            local.resolve("Programs").resolve("Alexandrite")
        }

        else -> cli.home.resolve(".local").resolve("lib").resolve("alexandrite")
    }
}

/** Runs the installed install script in its update mode for this command's config file and data directory. */
internal fun update(cli: Cli, version: String?): Int {
    if (version != null && !RELEASE.matches(version)) return cli.fail(cli.t("update.no_release", "version" to version))
    val root = cli.environment[INSTALL_DIR]?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?: return cli.fail(cli.t("update.not_scripted"))
    val config = "${cli.locations.configFile.toAbsolutePath()}"
    val data = "${cli.locations.dataDir.toAbsolutePath()}"
    val command = if (cli.platform == Platform.WINDOWS) {
        listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "${root.resolve("install.ps1")}") +
            listOf("-Update") + (version?.let { listOf("-Version", it) } ?: emptyList()) +
            listOf("-Config", config, "-DataDir", data)
    } else {
        listOf("sh", "${root.resolve("install.sh")}", "--update") +
            (version?.let { listOf("--version", it) } ?: emptyList()) + listOf("--config", config, "--data-dir", data)
    }
    return try {
        cli.machine.attach(command)
    } catch (e: IOException) {
        cli.fail(cli.t("service.cannot_run", "program" to command.first(), "reason" to (e.message ?: e)))
    }
}

/** Removes the service, the installed program and, unless [keepData], the config, secrets, data and cache. */
internal fun uninstall(cli: Cli, keepData: Boolean, yes: Boolean): Int {
    val service = installedService(cli)
    val root = installRoot(cli).takeIf { Files.isDirectory(it) && isInstallRoot(it) }
    val launcher = launcher(cli, root)
    val files = if (keepData) emptyList() else dataFiles(cli)
    val trees = if (keepData) emptyList() else dataTrees(cli)
    val plan = listOfNotNull(
        service?.let { "${it.description}${it.definition?.let { file -> " ($file)" }.orEmpty()}" },
        root?.let { cli.t("uninstall.program", "directory" to it) },
        launcher?.let { cli.t("uninstall.launcher", "file" to it) },
    ) + files.map { "$it" } + trees.map { cli.t("uninstall.tree", "directory" to it) }
    if (plan.isEmpty()) return cli.done(cli.t("uninstall.nothing"))
    cli.out.println(cli.t("uninstall.removes"))
    plan.forEach { cli.out.println("  $it") }
    if (keepData) cli.out.println(cli.t("uninstall.data_stays"))
    if (!yes && !cli.confirm(cli.t("uninstall.confirm"))) {
        return cli.fail(cli.t(if (cli.machine.interactive) "uninstall.kept" else "uninstall.kept_yes"))
    }
    if (service != null) {
        service.stop()
    } else if (cli.status() != null) {
        try {
            cli.ask(ControlRequest.Stop)
        } catch (e: IOException) {
            cli.fail(cli.t("uninstall.cannot_stop", "reason" to (e.message ?: e)))
        }
    }
    awaitStopped(cli)
    val failures = mutableListOf<String>()
    fun removing(what: Any, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            failures += "$what: ${e.message ?: e}"
        }
    }
    service?.let { removing(it.description) { it.remove() } }
    launcher?.let { removing(it) { Files.deleteIfExists(it) } }
    root?.let { removing(it) { deleteTree(it) } }
    files.forEach { removing(it) { Files.deleteIfExists(it) } }
    trees.forEach { removing(it) { deleteTree(it) } }
    if (!keepData) {
        cli.locations.configFile.toAbsolutePath().parent?.let { directory ->
            removing(directory) { if (isEmptyDirectory(directory)) Files.delete(directory) }
        }
    }
    if (failures.isNotEmpty()) {
        failures.forEach { cli.fail(cli.t("uninstall.cannot_remove", "what" to it)) }
        return ExitCode.FAILURE
    }
    if (cli.platform == Platform.WINDOWS && root != null) {
        cli.out.println(cli.t("uninstall.path", "directory" to root.resolve("bin")))
    }
    return cli.done(cli.t("uninstall.done"))
}

private fun isInstallRoot(directory: Path): Boolean = Files.isDirectory(directory.resolve(VERSIONS)) ||
    Files.exists(directory.resolve("current"), LinkOption.NOFOLLOW_LINKS) ||
    Files.exists(directory.resolve("current.txt"))

/** The launcher link in the user's bin directory that points into [root], null when there is none. */
private fun launcher(cli: Cli, root: Path?): Path? {
    if (cli.platform == Platform.WINDOWS || root == null) return null
    val link = cli.home.resolve(".local").resolve("bin").resolve(LAUNCHER)
    if (!Files.isSymbolicLink(link)) return null
    val target = try {
        link.parent.resolve(Files.readSymbolicLink(link)).normalize()
    } catch (e: IOException) {
        return null
    }
    return link.takeIf { target.startsWith(root.normalize()) }
}

private fun dataFiles(cli: Cli): List<Path> {
    val config = cli.locations.configFile.toAbsolutePath()
    return listOf(config, config.resolveSibling("${config.fileName}.bak"), cli.secrets).filter(Files::exists)
}

/** The data and cache directories, each when it exists and may be removed whole. */
private fun dataTrees(cli: Cli): List<Path> {
    val data = cli.locations.dataDir.toAbsolutePath().normalize()
    val cache = cli.locations.cacheDir.toAbsolutePath().normalize()
    return listOf(data, cache).distinct().filter { Files.isDirectory(it) && removable(cli, it) }
        .filter { it != data || Files.exists(it.resolve(DATA_LOCK)) || isEmptyDirectory(it) }
        .filterNot { tree -> tree != data && tree.startsWith(data) }
}

/** Whether [directory] is no home, root or a directory that holds the home. */
private fun removable(cli: Cli, directory: Path): Boolean {
    val home = cli.home.toAbsolutePath().normalize()
    return directory.nameCount >= 2 && !home.startsWith(directory)
}

private fun isEmptyDirectory(directory: Path): Boolean =
    Files.isDirectory(directory) && Files.list(directory).use { it.findAny().isEmpty }

private fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
