package com.github.croyla.mapeditor

import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class GeoJsonMapEditor(
    private val project: Project,
    private val file: VirtualFile,
    private var textEditor: TextEditor?,
    parentDisposable: Disposable
) : Disposable {

    private val panel = JPanel(BorderLayout())
    private val browser: JBCefBrowser = JBCefBrowser()
    private var isUpdatingFromMap = false
    private var isUpdatingFromText = false
    private var documentListener: DocumentListener? = null
    private var currentThemeIsDark = isDarkTheme()

    val component: JComponent
        get() = panel

    init {
        // Register this disposable with parent
        com.intellij.openapi.util.Disposer.register(parentDisposable, this)

        panel.add(browser.component, BorderLayout.CENTER)

        // Listen for theme changes
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                val newThemeIsDark = isDarkTheme()
                if (newThemeIsDark != currentThemeIsDark) {
                    currentThemeIsDark = newThemeIsDark
                    reloadMapWithNewTheme()
                }
            }
        )

        val jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        jsQuery.addHandler { geoJsonString ->
            if (!isUpdatingFromText) {
                updateTextFromMap(geoJsonString)
            }
            null
        }

        // JS query for feature selection (to scroll code to feature)
        val featureSelectionQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        featureSelectionQuery.addHandler { featureId ->
            scrollCodeToFeature(featureId)
            null
        }

        // Load the map HTML
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject the JS query handlers
                    this@GeoJsonMapEditor.browser.cefBrowser.executeJavaScript(
                        """
                        window.updateGeoJsonFromMap = function(geoJson) { ${jsQuery.inject("JSON.stringify(geoJson)")} };
                        window.notifyFeatureSelected = function(featureId) { ${featureSelectionQuery.inject("featureId")} };
                        """.trimIndent(),
                        cefBrowser?.url ?: "", 0
                    )

                    // Initial load of GeoJSON
                    loadGeoJsonToMap()
                }
            }
        }, browser.cefBrowser)

        browser.loadHTML(getMapHtml())

        // Set up document listener if text editor is available
        if (textEditor != null) {
            setupDocumentListener()
        }
    }

    fun setTextEditor(editor: TextEditor) {
        this.textEditor = editor
        setupDocumentListener()
    }

    private fun setupDocumentListener() {
        // Remove old listener if exists
        documentListener?.let { listener ->
            val document = FileDocumentManager.getInstance().getDocument(file)
            document?.removeDocumentListener(listener)
        }

        // Add new listener
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isUpdatingFromMap) {
                    loadGeoJsonToMap()
                }
            }
        }
        documentListener = listener

        val document = FileDocumentManager.getInstance().getDocument(file)
        document?.addDocumentListener(listener)

        // Add caret listener to pan map when cursor moves to a feature
        textEditor?.editor?.caretModel?.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                panMapToFeatureAtCursor()
            }
        })
    }

    private fun panMapToFeatureAtCursor() {
        val editor = textEditor?.editor ?: return

        ApplicationManager.getApplication().runReadAction {
            try {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction
                val offset = editor.caretModel.offset
                val text = document.text

                // Parse the GeoJSON to find feature boundaries
                val geoJson = JsonParser.parseString(text)
                if (!geoJson.isJsonObject) return@runReadAction

                val obj = geoJson.asJsonObject
                if (!obj.has("type") || obj.get("type").asString != "FeatureCollection") return@runReadAction
                if (!obj.has("features")) return@runReadAction

                val features = obj.getAsJsonArray("features")
                if (features.size() == 0) return@runReadAction

                // Find the "features" array position in the text
                val featuresPos = text.indexOf("\"features\"")
                if (featuresPos < 0 || offset < featuresPos) return@runReadAction

                // Find the opening bracket of the features array
                var arrayStart = text.indexOf('[', featuresPos)
                if (arrayStart < 0 || offset < arrayStart) return@runReadAction

                // Now find which feature the cursor is in
                var featureIndex = -1
                var currentPos = arrayStart + 1
                var depth = 0
                var inString = false
                var escape = false

                for (i in 0 until features.size()) {
                    // Find the start of this feature object
                    var featureStart = -1
                    for (j in currentPos until text.length) {
                        val c = text[j]

                        if (escape) {
                            escape = false
                            continue
                        }

                        if (c == '\\' && inString) {
                            escape = true
                            continue
                        }

                        if (c == '"' && !escape) {
                            inString = !inString
                            continue
                        }

                        if (inString) continue

                        if (c == '{') {
                            if (depth == 0) {
                                featureStart = j
                            }
                            depth++
                        } else if (c == '}') {
                            depth--
                            if (depth == 0 && featureStart >= 0) {
                                // This is the end of the current feature
                                if (offset >= featureStart && offset <= j) {
                                    featureIndex = i
                                    break
                                }
                                currentPos = j + 1
                                break
                            }
                        }
                    }

                    if (featureIndex >= 0) break
                }

                if (featureIndex >= 0) {
                    val finalIndex = featureIndex
                    // Pan map to this feature
                    ApplicationManager.getApplication().invokeLater {
                        val jsCode = """
                            if (window.panToFeatureByIndex) {
                                window.panToFeatureByIndex($finalIndex);
                            }
                        """.trimIndent()
                        browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url, 0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scrollCodeToFeature(featureId: String) {
        ApplicationManager.getApplication().invokeLater {
            val editor = textEditor?.editor ?: return@invokeLater

            ApplicationManager.getApplication().runReadAction {
                try {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction
                    val text = document.text

                    // Search for the feature ID in the text
                    // Look for "id": "featureId" pattern
                    val searchPattern = "\"id\":\\s*\"$featureId\""
                    val regex = Regex(searchPattern)
                    val match = regex.find(text)

                    if (match != null) {
                        val idPosition = match.range.first

                        // Find the start of this feature object (previous opening brace)
                        var featureStart = idPosition
                        var braceCount = 0
                        for (i in idPosition downTo 0) {
                            when (text[i]) {
                                '}' -> braceCount++
                                '{' -> {
                                    if (braceCount == 0) {
                                        featureStart = i
                                        break
                                    }
                                    braceCount--
                                }
                            }
                        }

                        ApplicationManager.getApplication().invokeLater {
                            editor.caretModel.moveToOffset(featureStart)
                            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private var updateTimer: javax.swing.Timer? = null

    private fun loadGeoJsonToMap() {
        // Debounce updates to avoid lag
        updateTimer?.stop()
        updateTimer = javax.swing.Timer(500) {
            ApplicationManager.getApplication().runReadAction {
                try {
                    isUpdatingFromText = true
                    val document = FileDocumentManager.getInstance().getDocument(file)
                    val geoJson = document?.text ?: "{}"

                    // Validate JSON
                    JsonParser.parseString(geoJson)

                    val jsCode = """
                        if (window.loadGeoJson) {
                            window.loadGeoJson($geoJson);
                        }
                    """.trimIndent()

                    browser.cefBrowser.executeJavaScript(jsCode, browser.cefBrowser.url, 0)
                } catch (_: Exception) {
                    // Invalid JSON, skip update
                } finally {
                    isUpdatingFromText = false
                }
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun reloadMapWithNewTheme() {
        ApplicationManager.getApplication().invokeLater {
            browser.loadHTML(getMapHtml())
        }
    }

    private fun updateTextFromMap(geoJsonString: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                isUpdatingFromMap = true
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater

                // Pretty print JSON with proper indentation
                val jsonObject = JsonParser.parseString(geoJsonString).asJsonObject
                val prettyJson = com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(jsonObject)

                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(prettyJson)
                }

                // Trigger reformat to ensure proper IDE indentation
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)
                        if (psiFile != null) {
                            WriteCommandAction.runWriteCommandAction(project) {
                                com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                                    .reformat(psiFile)
                            }
                        }
                    } catch (_: Exception) {
                        // Reformatting failed, but at least we have pretty JSON
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isUpdatingFromMap = false
            }
        }
    }

    private fun isDarkTheme(): Boolean {
        val globalScheme = EditorColorsManager.getInstance().globalScheme
        val backgroundColor = globalScheme.defaultBackground
        // Calculate luminance to determine if background is dark
        val luminance = (0.299 * backgroundColor.red + 0.587 * backgroundColor.green + 0.114 * backgroundColor.blue) / 255.0
        return luminance < 0.5
    }

    private fun getMapHtml(): String {
        val isDark = isDarkTheme()

        // Read HTML template from resources
        val htmlTemplate = javaClass.getResourceAsStream("/GeoJsonMapRender.html")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Could not load GeoJsonMapRender.html")

        // Theme values
        val basemapStyle = if (isDark) {
            "https://tiles.basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
        } else {
            "https://tiles.basemaps.cartocdn.com/gl/positron-gl-style/style.json"
        }

        // Theme colors
        val bgColor = if (isDark) "#2B2B2B" else "#FFFFFF"
        val borderColor = if (isDark) "#3C3F41" else "#D1D5DB"
        val textColor = if (isDark) "#BBBBBB" else "#374151"
        val hoverBg = if (isDark) "#3C3F41" else "#F3F4F6"
        val activeBg = if (isDark) "#4A88C7" else "#3B82F6"
        val panelBg = if (isDark) "#313335" else "#FFFFFF"
        val panelBorder = if (isDark) "#3C3F41" else "#E5E7EB"
        val labelColor = if (isDark) "#888" else "#6B7280"
        val iconFilter = if (isDark) "invert(0.8)" else "none"
        val attribBg = if (isDark) "rgba(43, 43, 43, 0.8)" else "rgba(255, 255, 255, 0.8)"
        val linkColor = if (isDark) "#4A88C7" else "#3B82F6"
        val closeButtonColor = if (isDark) "#888" else "#999"

        // Replace placeholders with actual values
        return htmlTemplate
            .replace("{{BASEMAP_STYLE}}", basemapStyle)
            .replace("{{BG_COLOR}}", bgColor)
            .replace("{{BORDER_COLOR}}", borderColor)
            .replace("{{TEXT_COLOR}}", textColor)
            .replace("{{HOVER_BG}}", hoverBg)
            .replace("{{ACTIVE_BG}}", activeBg)
            .replace("{{PANEL_BG}}", panelBg)
            .replace("{{PANEL_BORDER}}", panelBorder)
            .replace("{{LABEL_COLOR}}", labelColor)
            .replace("{{ICON_FILTER}}", iconFilter)
            .replace("{{ATTRIB_BG}}", attribBg)
            .replace("{{LINK_COLOR}}", linkColor)
            .replace("{{CLOSE_BUTTON_COLOR}}", closeButtonColor)
    }

    override fun dispose() {
        updateTimer?.stop()
        browser.dispose()
    }
}