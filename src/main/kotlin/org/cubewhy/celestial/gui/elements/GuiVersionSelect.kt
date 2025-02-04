/*
 * Celestial Launcher <me@lunarclient.top>
 * License under GPLv3
 * Do NOT remove this note if you want to copy this file.
 */
package org.cubewhy.celestial.gui.elements

import com.sun.tools.attach.AttachNotSupportedException
import org.apache.commons.io.FileUtils
import org.cubewhy.celestial.*
import org.cubewhy.celestial.event.impl.GameStartEvent
import org.cubewhy.celestial.event.impl.GameTerminateEvent
import org.cubewhy.celestial.files.DownloadManager.waitForAll
import org.cubewhy.celestial.game.addon.LunarCNMod
import org.cubewhy.celestial.game.addon.WeaveMod
import org.cubewhy.celestial.game.thirdparty.LunarQT
import org.cubewhy.celestial.gui.GuiLauncher.Companion.statusBar
import org.cubewhy.celestial.utils.CrashReportType
import org.cubewhy.celestial.utils.findJava
import org.cubewhy.celestial.utils.lunar.GameArtifactInfo
import org.cubewhy.celestial.utils.lunar.LauncherData.Companion.getMainClass
import org.cubewhy.celestial.utils.lunar.LauncherData.Companion.getSupportModules
import org.cubewhy.celestial.utils.lunar.LauncherData.Companion.getSupportVersions
import org.cubewhy.celestial.utils.saveFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.GridLayout
import java.io.File
import java.io.IOException
import java.io.NotActiveException
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.filechooser.FileNameExtensionFilter

private val log: Logger = LoggerFactory.getLogger(GuiVersionSelect::class.java)

class GuiVersionSelect : JPanel() {
    private val versionSelect = JComboBox<String>()
    private val moduleSelect = JComboBox<String>()
    private val branchInput = JTextField()
    private var isFinishOk = false
    private val btnOnline: JButton = JButton(f.getString("gui.version.online"))
    private val btnOffline: JButton = JButton(f.getString("gui.version.offline"))
    private var isLaunching = false

    private fun interface CreateProcess {

        fun create(): Process?
    }

    init {
        this.border = TitledBorder(
            null,
            f.getString("gui.version-select.title"),
            TitledBorder.DEFAULT_JUSTIFICATION,
            TitledBorder.DEFAULT_POSITION,
            null,
            Color.orange
        )
        this.layout = GridLayout(5, 2, 5, 5)

        this.initGui()
    }


    private fun initGui() {
        this.add(JLabel(f.getString("gui.version-select.label.version")))
        this.add(versionSelect)
        this.add(JLabel(f.getString("gui.version-select.label.module")))
        this.add(moduleSelect)
        this.add(JLabel(f.getString("gui.version-select.label.branch")))
        this.add(branchInput)

        // add items
        val map = getSupportVersions(metadata)
        val supportVersions: List<String> = map["versions"] as ArrayList<String>
        for (version in supportVersions) {
            versionSelect.addItem(version)
        }
        versionSelect.addActionListener {
            try {
                refreshModuleSelect(this.isFinishOk)
                if (this.isFinishOk) {
                    saveVersion()
                }
            } catch (ex: IOException) {
                throw RuntimeException(ex)
            }
        }
        moduleSelect.addActionListener {
            if (this.isFinishOk) {
                saveModule()
            }
        }
        refreshModuleSelect(false)
        // get is first launch
        if (config.game.target == null) {
            val game = GameVersionInfo(
                versionSelect.selectedItem as String,
                moduleSelect.selectedItem as String,
                "master"
            )
            config.game.target = game
            versionSelect.selectedItem = map["default"]
        }
        initInput(versionSelect, moduleSelect, branchInput)
        isFinishOk = true

        // add launch buttons
        btnOnline.addActionListener {
            try {
                this.online()
            } catch (e: Exception) {
                log.error(e.stackTraceToString())
            }
        }
        this.add(btnOnline)

        this.add(btnOffline)
        btnOffline.addActionListener {
            try {
                this.offline()
            } catch (e: IOException) {
                log.error(e.stackTraceToString())
            } catch (e: InterruptedException) {
                log.error(e.stackTraceToString())
            } catch (ignored: AttachNotSupportedException) {
                log.warn("Failed to attach to the game process")
            }
        }

        val btnWipeCache = JButton(f.getString("gui.version.cache.wipe"))

        btnWipeCache.addActionListener {
            if (JOptionPane.showConfirmDialog(
                    this,
                    f.getString("gui.version.cache.warn"),
                    "Confirm",
                    JOptionPane.YES_NO_OPTION
                ) == JOptionPane.YES_OPTION
            ) {
                statusBar.text = f.getString("gui.version.cache.start")
                try {
                    if (wipeCache(null)) {
                        statusBar.text = f.getString("gui.version.cache.success")
                    } else {
                        statusBar.text = f.getString("gui.version.cache.failure")
                    }
                } catch (ex: IOException) {
                    throw RuntimeException(ex)
                }
            }
        }
        this.add(btnWipeCache)

        val btnFetchJson = JButton(f.getString("gui.version.fetch"))

        btnFetchJson.addActionListener {
            // open file save dialog
            val file = saveFile(FileNameExtensionFilter("Json (*.json)", "json"))
            file?.apply {
                log.info("Fetching version json...")
                val json = launcherData.getVersion(
                    versionSelect.selectedItem as String,
                    branchInput.text,
                    moduleSelect.selectedItem as String,
                )
                var file1 = this
                if (!this.name.endsWith(".json")) {
                    file1 = file + ".json" // add extension
                }
                log.info("Fetch OK! Dumping to ${file1.path}")
                FileUtils.write(file1, JSON.encodeToString(GameArtifactInfo.serializer(), json), StandardCharsets.UTF_8)
            }
        }

        this.add(btnFetchJson)
    }


    private fun beforeLaunch() {
        if (gamePid.get() != 0L) {
            if (findJava(getMainClass(null)) != null) {
                JOptionPane.showMessageDialog(
                    this,
                    f.getString("gui.version.launched.message"),
                    f.getString("gui.version.launched.title"),
                    JOptionPane.WARNING_MESSAGE
                )
            } else {
                gamePid.set(0)
                statusBar.isRunningGame = false
            }
        }
        // check update for loaders
        val weave = config.addon.weave
        val cn = config.addon.lunarcn
        var checkUpdate = false

        try {
            if (weave.state && weave.checkUpdate) {
                log.info("Checking update for Weave loader")
                checkUpdate = WeaveMod.checkUpdate()
            }
            if (cn.state && cn.checkUpdate) {
                log.info("Checking update for LunarCN loader")
                checkUpdate = LunarCNMod.checkUpdate()
            }
        } catch (e: Exception) {
            log.error("Failed to check loader updates")
            log.error(e.stackTraceToString())
            if (!config.proxy.mirror.containsKey("github.com:443") && JOptionPane.showConfirmDialog(
                    this,
                    f.getString("gui.proxy.suggest.gh"),
                    "Apply GitHub Mirror",
                    JOptionPane.YES_NO_OPTION
                ) == JOptionPane.YES_OPTION
            ) {
                log.info("Applying GitHub mirror")
                config.proxy.mirror["github.com:443"] = "github.ink:443"
            }
        }

        try {
            if (config.addon.lcqt.state && config.addon.lcqt.checkUpdate) {
                log.info("Checking update for LunarQT")
                checkUpdate = LunarQT.checkUpdate()
            }
        } catch (e: Exception) {
            log.error("Failed to check lcqt updates")
            log.error(e.stackTraceToString())
        }

        if (checkUpdate) {
            statusBar.text = f.getString("gui.addon.update")
            waitForAll()
        }
    }


    private fun runGame(cp: CreateProcess, run: Runnable?) {
        val p = arrayOfNulls<Process>(1) // create process

        val threadGetId = Thread {
            // find the game process
            Thread.sleep(3000) // sleep 3s
            if (p[0]!!.isAlive) {
                try {
                    val java = findJava(getMainClass(null))!!
                    val id = java.id()
                    gamePid.set(id.toLong())
                    java.detach()
                } catch (ex: Exception) {
                    log.error("Failed to get the real pid of the game, is Celestial launched non java program?")
                    log.warn("process.pid() will be used to get the process id, which may not be the real PID")
                    gamePid.set(p[0]!!.pid())
                }
                log.info("Pid: $gamePid")
                statusBar.text = f.format("status.launch.started", gamePid)
                GameStartEvent(gamePid.get()).call()
            }
        }
        Thread {
            try {
                run?.run()
                p[0] = cp.create()
                threadGetId.start()
                val code = p[0]!!.waitFor()
                log.info("Game terminated")
                statusBar.text = f.getString("status.launch.terminated")
                gamePid.set(0)
                GameTerminateEvent().call()
                if (code != 0) {
                    // upload crash report
                    statusBar.text = f.getString("status.launch.crashed")
                    log.info("Client looks crashed")
                    try {
                        if (config.dataSharing) {
                            val trace = FileUtils.readFileToString(gameLogFile, StandardCharsets.UTF_8)
                            val script = FileUtils.readFileToString(launchScript, StandardCharsets.UTF_8)
                            val result = launcherData.uploadCrashReport(trace, CrashReportType.GAME, script)
                            if (result != null) {
                                val url = result.url
                                val id = result.id
                                JOptionPane.showMessageDialog(
                                    this,
                                    String.format(
                                        f.getString("gui.message.clientCrash1"),
                                        id,
                                        url,
                                        gameLogFile.path,
                                        f.getString("gui.version.crash.tip")
                                    ),
                                    "Game crashed!",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            } else {
                                throw RuntimeException("Failed to upload crash report")
                            }
                        } else {
                            throw NotActiveException()
                        }
                    } catch (e: Exception) {
                        JOptionPane.showMessageDialog(
                            this,
                            String.format(
                                f.getString("gui.message.clientCrash2"),
                                gameLogFile.path,
                                f.getString("gui.version.crash.tip")
                            ),
                            "Game crashed!",
                            JOptionPane.ERROR_MESSAGE
                        )
                        if (e !is NotActiveException) {
                            throw RuntimeException(e)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                log.error(e.stackTraceToString())
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }.start()
    }


    private fun online() {
        beforeLaunch()
        val natives =
            launch((versionSelect.selectedItem as String), branchInput.text, moduleSelect.selectedItem as String)
        runGame({
            try {
                statusBar.text = f.getString("status.launch.call-process")
                return@runGame launch().start()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }) {
            try {
                isLaunching = true
                statusBar.text = f.getString("status.launch.begin")
                checkUpdate(
                    (versionSelect.selectedItem as String),
                    moduleSelect.selectedItem as String,
                    branchInput.text
                )
                waitForAll()
                try {
                    statusBar.text = f.getString("status.launch.natives")
                    natives.unzipNatives(File(config.installationDir))
                } catch (e: Exception) {
                    log.error("Is game launched? Failed to unzip natives.")
                    log.error(e.stackTraceToString())
                }
                // exec, run
                log.info("Everything is OK, starting game...")
                isLaunching = false
            } catch (e: Exception) {
                log.error("Failed to check update")
                val trace = e.stackTraceToString()
                log.error(trace)
                JOptionPane.showMessageDialog(
                    null,
                    f.format("gui.check-update.error.message", trace),
                    f.getString("gui.check-update.error.title"),
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun offline() {
        beforeLaunch()
        val process = launch()
        runGame({
            try {
                statusBar.text = f.getString("status.launch.call-process")
                return@runGame process.start()
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
        }, null)
    }

    private fun initInput(versionSelect: JComboBox<String>, moduleSelect: JComboBox<String>, branchInput: JTextField) {
        val game = config.game.target!!
        versionSelect.selectedItem = game.version
        moduleSelect.selectedItem = game.module
        branchInput.text = game.branch
    }

    private fun saveVersion() {
        val version = versionSelect.selectedItem as String
        log.info("Select version -> $version")
        config.game.target?.version = version
    }

    private fun saveModule() {
        if (moduleSelect.selectedItem == null) {
            return
        }
        val module = moduleSelect.selectedItem as String
        log.info("Select module -> $module")
        config.game.target?.module = module
    }


    private fun refreshModuleSelect(reset: Boolean) {
        moduleSelect.removeAllItems()
        val map = getSupportModules(metadata, (versionSelect.selectedItem as String))
        val modules: List<String> = map["modules"] as ArrayList<String>
        val defaultValue = map["default"] as String?
        for (module in modules) {
            moduleSelect.addItem(module)
        }
        if (reset) {
            moduleSelect.selectedItem = defaultValue
        }
    }
}

fun File.unzipNatives(baseDir: File) {
    log.info("Unzipping natives...")
    val dir = File(baseDir, "natives")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    this.toZip().unzip(dir)
    log.info("Unzipped successful")
}

private operator fun File.plus(s: String): File {
    return File(this.path + s)
}
