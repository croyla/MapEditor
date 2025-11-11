package com.github.croyla.mapeditor

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.*

class GeoJsonSplitEditor(
    private val project: Project,
    private val file: VirtualFile
) : FileEditor {

    private val textEditor: TextEditor
    private val mapEditor: GeoJsonMapEditor
    private val splitPane: JSplitPane

    init {
        // Create text editor for JSON
        textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor

        // Create map editor
        mapEditor = GeoJsonMapEditor(project, file, textEditor)

        // Create split pane
        splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textEditor.component, mapEditor.component)
        splitPane.resizeWeight = 0.5
        splitPane.setDividerLocation(0.5)
    }

    override fun getComponent(): JComponent = splitPane

    override fun getPreferredFocusedComponent(): JComponent? = textEditor.preferredFocusedComponent

    override fun getName(): String = "GeoJSON Map Editor"

    override fun setState(state: FileEditorState) {
        textEditor.setState(state)
    }

    override fun isModified(): Boolean = textEditor.isModified

    override fun isValid(): Boolean = textEditor.isValid && file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        textEditor.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        textEditor.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = textEditor.currentLocation

    override fun dispose() {
        textEditor.dispose()
        mapEditor.dispose()
    }

    override fun getFile(): VirtualFile = file

    override fun <T : Any?> getUserData(key: Key<T>): T? = textEditor.getUserData(key)

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) {
        textEditor.putUserData(key, value)
    }
}