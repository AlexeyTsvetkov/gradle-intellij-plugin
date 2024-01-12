// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import com.jetbrains.plugin.structure.base.utils.inputStream
import com.jetbrains.plugin.structure.base.utils.simpleName
import com.jetbrains.plugin.structure.intellij.utils.JDOMUtil
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.jdom2.CDATA
import org.jdom2.Document
import org.jdom2.Element
import org.jetbrains.intellij.*
import org.jetbrains.intellij.IntelliJPluginConstants.PLUGIN_GROUP_NAME
import java.io.File

/**
 * Patches `plugin.xml` files with values provided to the task.
 *
 * To maintain and generate an up-to-date changelog, try using [Gradle Changelog Plugin](https://github.com/JetBrains/gradle-changelog-plugin).
 *
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html">Plugin Configuration File</a>
 */
@CacheableTask
abstract class PatchPluginXmlTask : DefaultTask() {

    /**
     * The directory where the patched plugin.xml will be written.
     *
     * Default value: `${project.buildDir}/patchedPluginXmlFiles`
     */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    /**
     * The list of output `plugin.xml` files.
     */
    @get:OutputFiles
    abstract val outputFiles: ListProperty<File>

    /**
     * The list of `plugin.xml` files to patch.
     *
     * Default value: auto-discovered from the project
     */
    @get:SkipWhenEmpty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginXmlFiles: ListProperty<File>

    /**
     * The description of the plugin – will be set to the `<description>` tag.
     */
    @get:Input
    @get:Optional
    abstract val pluginDescription: Property<String>

    /**
     * The lower bound of the version range to be patched – will be set for the `since-build` attribute of the `<idea-version>` tag.
     *
     * Default value: `intellij.version` in `Branch.Build.Fix` format
     */
    @get:Input
    @get:Optional
    abstract val sinceBuild: Property<String>

    /**
     * The upper bound of the version range to be patched – will be set for the `until-build` attribute of the `<idea-version>` tag.
     *
     * Default value: `intellij.version` in `Branch.Build.*` format
     */
    @get:Input
    @get:Optional
    abstract val untilBuild: Property<String>

    /**
     * The version of the plugin – will be set for the `<version>` tag.
     *
     * Default value: `${project.version}`
     */
    @get:Input
    @get:Optional
    abstract val version: Property<String>

    /**
     * The change notes of the plugin – will be set for the `<change-notes>` tag.
     */
    @get:Input
    @get:Optional
    abstract val changeNotes: Property<String>

    /**
     * Wrap the content passed to `<change-notes>` and `<description>` elements with `<![CDATA[ ... ]]>`
     *
     * Default value: `true`
     */
    @get:Input
    @get:Optional
    abstract val useCDATA: Property<Boolean>

    /**
     * The ID of the plugin – will be set for the `<id>` tag.
     */
    @get:Input
    @get:Optional
    abstract val pluginId: Property<String>

    private val context = logCategory()

    init {
        group = PLUGIN_GROUP_NAME
        description = "Patches `plugin.xml` files with values provided to the task."
    }

    @TaskAction
    fun patchPluginXml() {
        pluginXmlFiles.get()
            .map(File::toPath)
            .forEach { path ->
                path.inputStream().use { inputStream ->
                    val document = JDOMUtil.loadDocument(inputStream)

                    sinceBuild.orNull
                        ?.let { patchAttribute(document, "idea-version", "since-build", it) }
                    untilBuild.orNull
                        ?.let { patchAttribute(document, "idea-version", "until-build", it) }
                    pluginDescription.orNull
                        ?.let { patchTag(document, "description", it) }
                    changeNotes.orNull
                        ?.let { patchTag(document, "change-notes", it) }
                    version.orNull
                        .takeIf { it != Project.DEFAULT_VERSION }
                        ?.let { patchTag(document, "version", it) }
                    pluginId.orNull
                        ?.let { patchTag(document, "id", it) }

                    destinationDir.get()
                        .asPath
                        .resolve(path.simpleName)
                        .let { transformXml(document, it) }
                }
            }
    }

    /**
     * Sets the [content] value of the given [document] to the given [name] tag.
     */
    private fun patchTag(document: Document, name: String, content: String) {
        if (content.isEmpty()) {
            return
        }
        val pluginXml = document.rootElement.takeIf { it.name == "idea-plugin" } ?: return

        val tag = pluginXml.getChild(name)
            ?.also {
                val existingValue = it.text
                if (existingValue.isNotEmpty()) {
                    warn(context, "Patching plugin.xml: value of '$name[$existingValue]' tag will be set to '$content'")
                }
            }
            .or {
                val tag = Element(name)
                pluginXml.addContent(0, tag)

                tag
            }
            .let {
                if (useCDATA.get() && name in listOf("change-notes", "description")) {
                    it.setContent(CDATA(content))
                } else {
                    it.text = content
                }
            }
    }

    /**
     * Sets the [attributeValue] value for the given [attributeName] of the given [document] under the specific [tagName] tag.
     */
    private fun patchAttribute(document: Document, tagName: String, attributeName: String, attributeValue: String) {
        if (attributeValue.isEmpty()) {
            return
        }
        val pluginXml = document.rootElement.takeIf { it.name == "idea-plugin" } ?: return

        val tag = pluginXml.getChild(tagName)
        if (tag != null) {
            val existingValue = tag.getAttribute(attributeName)?.value
            if (!existingValue.isNullOrEmpty()) {
                warn(context, "Patching plugin.xml: attribute '$attributeName=[$existingValue]' of '$tagName' tag will be set to '$attributeValue'")
            }
            tag.setAttribute(attributeName, attributeValue)
        } else {
            pluginXml.addContent(0, Element(tagName).apply { setAttribute(attributeName, attributeValue) })
        }
    }
}
