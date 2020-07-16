/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.wai2k.launcher

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.*
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipInputStream
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.concurrent.thread

/*
 * GPLv3 License
 *
 *  Copyright (c) WAI2K by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

object Main {
    private val client = OkHttpClient()
    private val endpoint = "https://api.github.com/repos/waicool20/WAI2K/releases/latest"
    private val appPath = Paths.get(System.getProperty("user.home")).resolve(".wai2k").toAbsolutePath()
    private val libPath = appPath.resolve("libs")
    private val lastUpdatedPath = appPath.resolve("last_updated.txt")

    var lastUpdated = Instant.ofEpochMilli(0)


    val label = JLabel().apply {
        text = "Launching WAI2K"
    }
    val content = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(label)
    }
    val frame = JFrame("WAI2K Launcher").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        add(content)
        size = Dimension(500, 75)
        setLocationRelativeTo(null)
        isResizable = false
        isVisible = true
    }

    init {
        if (Files.notExists(appPath)) Files.createDirectories(appPath)
        if (Files.notExists(libPath)) Files.createDirectories(libPath)
        if (Files.notExists(lastUpdatedPath)) Files.createFile(lastUpdatedPath)
        try {
            lastUpdated = Instant.parse(Files.newInputStream(lastUpdatedPath).bufferedReader().readText())
        } catch (e: Exception) {
            // Do nothing
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        checkLatestRelease()
        checkDependencies()
        launchWai2K(args)
    }

    private fun checkLatestRelease() {
        val json = try {
            val request = Request.Builder().url(endpoint).build()
            client.newCall(request).execute().use {
                ObjectMapper().readTree(it.body?.string())
            }
        } catch (e: Exception) {
            null
        } ?: return

        val lastCreated = Instant.parse(json.at("/created_at").textValue())
        if (lastCreated.isAfter(lastUpdated)) {
            val assets = json.at("/assets")
            var downloaded = 0
            label.text = "Downloading main files: $downloaded/${assets.size()}"
            assets.forEach {
                val url = it["browser_download_url"].textValue()
                val filename = it["name"].textValue()
                val dest = appPath.resolve(filename)
                println("[DOWNLOAD] $url")

                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use {
                    Files.write(dest, it.body!!.bytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                downloaded++
                label.text = "Downloading main files: $downloaded/${assets.size()}"
            }
            Files.write(lastUpdatedPath, lastCreated.toString().toByteArray())
            thread {
                val file = appPath.resolve("assets.zip")
                val destDir = appPath.resolve("wai2k/assets")
                val zis = ZipInputStream(Files.newInputStream(file))
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outputFile = destDir.resolve(entry.name)
                        Files.createDirectories(outputFile.parent)
                        val output = Files.newOutputStream(outputFile)
                        zis.copyTo(output)
                        output.close()
                    }
                    entry = zis.nextEntry
                }
                zis.closeEntry()
                zis.close()
            }
        }
    }

    private fun checkDependencies() {
        if (Files.notExists(appPath.resolve("dependencies.txt"))) return
        val text = Files.readAllLines(appPath.resolve("dependencies.txt"))
        val repos = mutableListOf<String>()
        val depsString = mutableListOf<String>()

        var isRepo = false

        for (line in text) {
            when {
                line.startsWith("Repositories:") -> isRepo = true
                line.startsWith("Dependencies") -> isRepo = false
                line.startsWith("- ") -> {
                    val entry = line.drop(2)
                    if (isRepo) {
                        repos.add(entry)
                    } else {
                        depsString.add(entry)
                    }
                }
            }
        }

        val deps = depsString
                .map { it.split(":") }
                .filter { (_, name, version) ->
                    Files.notExists(libPath.resolve("$name-$version.jar"))
                }

        val latch = CountDownLatch(deps.size)
        label.text = "Downloading libraries: ${deps.size - latch.count}/${deps.size}"
        for ((grp, name, version) in deps) {
            val group = grp.replace(".", "/")
            for (repo in repos) {
                val url = if (repo.endsWith("/")) repo else "$repo/"
                val filename = "$name-$version.jar"
                val path = libPath.resolve(filename)
                if (Files.exists(path)) {
                    println("[OK] $path")
                    break
                }
                val request = Request.Builder().url("$url$group/$name/$version/$filename").build()
                client.newCall(request).enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        if (response.code == 200) {
                            println("[DOWNLOAD] $path")
                            val input = response.body!!.byteStream()
                            val output = Files.newOutputStream(path)
                            input.copyTo(output)
                            input.close()
                            output.close()
                            println("[OK] $path")
                            latch.countDown()
                            label.text = "Downloading libraries: ${deps.size - latch.count}/${deps.size}"
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        // Do Nothing
                    }
                })
                break
            }
        }
        latch.await()
    }

    private fun launchWai2K(args: Array<String>) {
        frame.isVisible = false
        frame.dispose()
        val classpath = if (System.getProperty("os.name").contains("win", true)) {
            "$libPath\\*;$appPath\\WAI2K.jar"
        } else {
            "$libPath/*:$appPath/WAI2K.jar"
        }
        println("Launching WAI2K")
        println("Classpath: $classpath")
        println("Args: ${args.joinToString()}")
        val process = ProcessBuilder("java", "-cp",
                classpath,
                "com.waicool20.wai2k.LauncherKt",
                *args
        ).inheritIO().start()
        process.waitFor()
    }
}