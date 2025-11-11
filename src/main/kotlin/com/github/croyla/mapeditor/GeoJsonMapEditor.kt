package com.github.croyla.mapeditor

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
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
    private val textEditor: TextEditor
) : Disposable {

    private val panel = JPanel(BorderLayout())
    private val browser: JBCefBrowser = JBCefBrowser()
    private val gson = Gson()
    private var isUpdatingFromMap = false
    private var isUpdatingFromText = false

    val component: JComponent
        get() = panel

    init {
        panel.add(browser.component, BorderLayout.CENTER)

        // Set up JS query for map-to-text updates
        val jsQuery = JBCefJSQuery.create(browser)
        jsQuery.addHandler { geoJsonString ->
            if (!isUpdatingFromText) {
                updateTextFromMap(geoJsonString)
            }
            null
        }

        // Load the map HTML
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject the JS query handler
                    this@GeoJsonMapEditor.browser.cefBrowser.executeJavaScript(
                        "window.updateGeoJsonFromMap = function(geoJson) { ${jsQuery.inject("JSON.stringify(geoJson)")} };",
                        cefBrowser?.url ?: "", 0
                    )

                    // Initial load of GeoJSON
                    loadGeoJsonToMap()
                }
            }
        }, browser.cefBrowser)

        browser.loadHTML(getMapHtml())

        // Listen for text editor changes
        val document = FileDocumentManager.getInstance().getDocument(file)
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isUpdatingFromMap) {
                    loadGeoJsonToMap()
                }
            }
        })
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
                } catch (e: Exception) {
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
                    } catch (e: Exception) {
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

    private fun getMapHtml(): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>GeoJSON Map Editor</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
    <link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet">
    <script src="https://unpkg.com/@mapbox/mapbox-gl-draw@1.4.3/dist/mapbox-gl-draw.js"></script>
    <link rel="stylesheet" href="https://unpkg.com/@mapbox/mapbox-gl-draw@1.4.3/dist/mapbox-gl-draw.css">
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body, html {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
        }
        #map {
            width: 100%;
            height: 100%;
        }
        .maplibregl-ctrl-top-left {
            top: 70px;
        }
    </style>
</head>
<body>
    <div class="relative w-full h-full">
        <!-- Toolbar -->
        <div class="absolute top-0 left-0 right-0 z-10 bg-white border-b border-gray-200 shadow-sm">
            <div class="flex items-center gap-2 p-3 flex-wrap">
                <button id="select-btn" class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 active:bg-blue-600 active:text-white">
                    Select
                </button>
                <button id="point-btn" class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500">
                    Point
                </button>
                <button id="line-btn" class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500">
                    LineString
                </button>
                <button id="polygon-btn" class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500">
                    Polygon
                </button>
                <div class="w-px h-8 bg-gray-300"></div>
                <button id="delete-btn" class="px-4 py-2 text-sm font-medium text-white bg-red-600 border border-red-600 rounded-md hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500 disabled:opacity-50 disabled:cursor-not-allowed" disabled>
                    Delete
                </button>
            </div>
        </div>

        <!-- Map Container -->
        <div id="map" class="pt-[60px]"></div>

        <!-- Properties Panel -->
        <div id="properties-panel" class="hidden absolute top-[70px] right-4 w-80 max-h-[calc(100vh-90px)] bg-white rounded-lg shadow-lg border border-gray-200 overflow-hidden">
            <div class="flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50">
                <h3 class="text-sm font-semibold text-gray-900">Feature Properties</h3>
                <button id="close-panel" class="text-gray-400 hover:text-gray-600">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
                    </svg>
                </button>
            </div>
            <div id="properties-content" class="p-4 overflow-y-auto max-h-[calc(100vh-170px)]"></div>
        </div>
    </div>

    <script>
        let map, draw, currentFeatureCollection;
        let selectedFeatureId = null;

        function initMap() {
            // Initialize MapLibre GL map with simpler raster tiles for better performance
            map = new maplibregl.Map({
                container: 'map',
                style: {
                    version: 8,
                    sources: {
                        'raster-tiles': {
                            type: 'raster',
                            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
                            tileSize: 256,
                            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                        }
                    },
                    layers: [{
                        id: 'simple-tiles',
                        type: 'raster',
                        source: 'raster-tiles',
                        minzoom: 0,
                        maxzoom: 22
                    }]
                },
                center: [0, 20],
                zoom: 2,
                fadeDuration: 0,
                renderWorldCopies: false
            });

            // Initialize MapBox GL Draw
            draw = new MapboxDraw({
                displayControlsDefault: false,
                controls: {},
                styles: [
                    // Point style
                    {
                        'id': 'gl-draw-point',
                        'type': 'circle',
                        'filter': ['all', ['==', '${'$'}type', 'Point'], ['!=', 'mode', 'static']],
                        'paint': {
                            'circle-radius': 6,
                            'circle-color': '#3b82f6'
                        }
                    },
                    // Line style
                    {
                        'id': 'gl-draw-line',
                        'type': 'line',
                        'filter': ['all', ['==', '${'$'}type', 'LineString'], ['!=', 'mode', 'static']],
                        'layout': {
                            'line-cap': 'round',
                            'line-join': 'round'
                        },
                        'paint': {
                            'line-color': '#3b82f6',
                            'line-width': 3
                        }
                    },
                    // Polygon fill
                    {
                        'id': 'gl-draw-polygon-fill',
                        'type': 'fill',
                        'filter': ['all', ['==', '${'$'}type', 'Polygon'], ['!=', 'mode', 'static']],
                        'paint': {
                            'fill-color': '#3b82f6',
                            'fill-opacity': 0.2
                        }
                    },
                    // Polygon outline
                    {
                        'id': 'gl-draw-polygon-stroke',
                        'type': 'line',
                        'filter': ['all', ['==', '${'$'}type', 'Polygon'], ['!=', 'mode', 'static']],
                        'layout': {
                            'line-cap': 'round',
                            'line-join': 'round'
                        },
                        'paint': {
                            'line-color': '#3b82f6',
                            'line-width': 3
                        }
                    },
                    // Vertex points
                    {
                        'id': 'gl-draw-polygon-and-line-vertex-active',
                        'type': 'circle',
                        'filter': ['all', ['==', 'meta', 'vertex'], ['==', '${'$'}type', 'Point']],
                        'paint': {
                            'circle-radius': 5,
                            'circle-color': '#fff',
                            'circle-stroke-width': 2,
                            'circle-stroke-color': '#3b82f6'
                        }
                    }
                ]
            });

            map.addControl(draw);

            // Add navigation controls
            map.addControl(new maplibregl.NavigationControl(), 'bottom-left');

            // Map events
            map.on('draw.create', updateGeoJson);
            map.on('draw.delete', updateGeoJson);
            map.on('draw.update', updateGeoJson);
            map.on('draw.selectionchange', handleSelectionChange);

            map.on('click', (e) => {
                const features = map.queryRenderedFeatures(e.point, {
                    layers: ['gl-draw-polygon-fill', 'gl-draw-line', 'gl-draw-point']
                });

                if (features.length > 0 && draw.getMode() === 'simple_select') {
                    const feature = features[0];
                    selectedFeatureId = feature.id;
                    showPropertiesPanel(feature);
                }
            });
        }

        function setDrawMode(mode) {
            // Remove active state from all buttons
            document.querySelectorAll('button[id$="-btn"]').forEach(btn => {
                btn.classList.remove('bg-blue-600', 'text-white');
                btn.classList.add('bg-white', 'text-gray-700');
            });

            switch(mode) {
                case 'select':
                    draw.changeMode('simple_select');
                    document.getElementById('select-btn').classList.add('bg-blue-600', 'text-white');
                    document.getElementById('select-btn').classList.remove('bg-white', 'text-gray-700');
                    break;
                case 'point':
                    draw.changeMode('draw_point');
                    document.getElementById('point-btn').classList.add('bg-blue-600', 'text-white');
                    document.getElementById('point-btn').classList.remove('bg-white', 'text-gray-700');
                    break;
                case 'line':
                    draw.changeMode('draw_line_string');
                    document.getElementById('line-btn').classList.add('bg-blue-600', 'text-white');
                    document.getElementById('line-btn').classList.remove('bg-white', 'text-gray-700');
                    break;
                case 'polygon':
                    draw.changeMode('draw_polygon');
                    document.getElementById('polygon-btn').classList.add('bg-blue-600', 'text-white');
                    document.getElementById('polygon-btn').classList.remove('bg-white', 'text-gray-700');
                    break;
            }
        }

        function handleSelectionChange(e) {
            if (e.features.length > 0) {
                selectedFeatureId = e.features[0].id;
                document.getElementById('delete-btn').disabled = false;
                showPropertiesPanel(e.features[0]);
            } else {
                selectedFeatureId = null;
                document.getElementById('delete-btn').disabled = true;
                hidePropertiesPanel();
            }
        }

        function showPropertiesPanel(feature) {
            const panel = document.getElementById('properties-panel');
            const content = document.getElementById('properties-content');

            const geometryType = feature.geometry.type;
            let html = '<div class="mb-4">';
            html += '<div class="text-xs font-medium text-gray-500 mb-1">Geometry Type</div>';
            html += '<div class="text-sm text-gray-900">' + geometryType + '</div>';
            html += '</div>';

            const properties = feature.properties || {};

            html += '<div class="mb-4">';
            html += '<label class="block text-xs font-medium text-gray-700 mb-2">Properties (JSON)</label>';
            html += '<textarea id="properties-json" rows="10" ';
            html += 'class="w-full px-3 py-2 text-sm font-mono border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500" ';
            html += 'placeholder="{}"></textarea>';
            html += '<div id="json-error" class="mt-1 text-xs text-red-600 hidden"></div>';
            html += '<button id="save-properties" class="mt-2 w-full px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500">';
            html += 'Save Properties';
            html += '</button>';
            html += '</div>';

            html += '<div class="border-t pt-4">';
            html += '<div class="text-xs font-medium text-gray-700 mb-2">Quick Actions</div>';
            html += '<button id="delete-feature" class="w-full px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-md hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500">';
            html += 'Delete Feature';
            html += '</button>';
            html += '</div>';

            content.innerHTML = html;

            // Set the textarea value with formatted JSON
            const textarea = document.getElementById('properties-json');
            try {
                textarea.value = JSON.stringify(properties, null, 2);
            } catch (e) {
                textarea.value = '{}';
            }

            // Add event listener for save button
            document.getElementById('save-properties').addEventListener('click', () => {
                saveFeatureProperties();
            });

            // Add event listener for delete button
            document.getElementById('delete-feature').addEventListener('click', () => {
                deleteSelectedFeature();
            });

            // Real-time JSON validation
            textarea.addEventListener('input', () => {
                const errorDiv = document.getElementById('json-error');
                try {
                    JSON.parse(textarea.value);
                    errorDiv.classList.add('hidden');
                    errorDiv.textContent = '';
                    textarea.classList.remove('border-red-500');
                } catch (e) {
                    errorDiv.classList.remove('hidden');
                    errorDiv.textContent = 'Invalid JSON: ' + e.message;
                    textarea.classList.add('border-red-500');
                }
            });

            panel.classList.remove('hidden');
        }

        function saveFeatureProperties() {
            if (!selectedFeatureId) return;

            const textarea = document.getElementById('properties-json');
            const errorDiv = document.getElementById('json-error');

            try {
                const properties = JSON.parse(textarea.value);
                const feature = draw.get(selectedFeatureId);

                if (feature) {
                    feature.properties = properties;
                    draw.add(feature);
                    updateGeoJson();

                    // Show success feedback
                    errorDiv.classList.remove('hidden');
                    errorDiv.classList.remove('text-red-600');
                    errorDiv.classList.add('text-green-600');
                    errorDiv.textContent = 'Properties saved successfully!';
                    textarea.classList.remove('border-red-500');

                    setTimeout(() => {
                        errorDiv.classList.add('hidden');
                        errorDiv.classList.remove('text-green-600');
                        errorDiv.classList.add('text-red-600');
                    }, 2000);
                }
            } catch (e) {
                errorDiv.classList.remove('hidden');
                errorDiv.textContent = 'Invalid JSON: ' + e.message;
                textarea.classList.add('border-red-500');
            }
        }

        function deleteSelectedFeature() {
            if (selectedFeatureId) {
                draw.delete([selectedFeatureId]);
                selectedFeatureId = null;
                document.getElementById('delete-btn').disabled = true;
                hidePropertiesPanel();
                updateGeoJson();
            }
        }

        function hidePropertiesPanel() {
            document.getElementById('properties-panel').classList.add('hidden');
        }

        function deleteSelected() {
            const selected = draw.getSelected();
            if (selected.features.length > 0) {
                const ids = selected.features.map(f => f.id);
                draw.delete(ids);
                selectedFeatureId = null;
                document.getElementById('delete-btn').disabled = true;
                hidePropertiesPanel();
            }
        }

        function updateGeoJson() {
            const data = draw.getAll();
            currentFeatureCollection = data;

            if (window.updateGeoJsonFromMap) {
                window.updateGeoJsonFromMap(data);
            }
        }

        window.loadGeoJson = function(geoJsonData) {
            try {
                if (!geoJsonData || (!geoJsonData['type'])) {
                    return;
                }

                // Clear existing features
                draw.deleteAll();

                // Load new features
                if (geoJsonData.type === 'FeatureCollection') {
                    if (geoJsonData.features && geoJsonData.features.length > 0) {
                        draw.add(geoJsonData);

                        // Fit bounds to features - use proper bounds calculation
                        const bounds = new maplibregl.LngLatBounds();

                        function addCoordinatesToBounds(coords, geomType) {
                            if (geomType === 'Point') {
                                bounds.extend(coords);
                            } else if (geomType === 'LineString') {
                                coords.forEach(coord => bounds.extend(coord));
                            } else if (geomType === 'Polygon') {
                                coords.forEach(ring => {
                                    ring.forEach(coord => bounds.extend(coord));
                                });
                            } else if (geomType === 'MultiPoint') {
                                coords.forEach(coord => bounds.extend(coord));
                            } else if (geomType === 'MultiLineString') {
                                coords.forEach(line => {
                                    line.forEach(coord => bounds.extend(coord));
                                });
                            } else if (geomType === 'MultiPolygon') {
                                coords.forEach(polygon => {
                                    polygon.forEach(ring => {
                                        ring.forEach(coord => bounds.extend(coord));
                                    });
                                });
                            }
                        }

                        geoJsonData.features.forEach(feature => {
                            if (feature.geometry && feature.geometry.coordinates) {
                                addCoordinatesToBounds(feature.geometry.coordinates, feature.geometry.type);
                            }
                        });

                        if (!bounds.isEmpty()) {
                            map.fitBounds(bounds, { padding: 50, duration: 0 });
                        }
                    }
                } else if (geoJsonData.type === 'Feature') {
                    draw.add(geoJsonData);

                    if (geoJsonData.geometry && geoJsonData.geometry.coordinates) {
                        const coords = geoJsonData.geometry.coordinates;
                        if (geoJsonData.geometry.type === 'Point') {
                            map.jumpTo({ center: coords});
                        } else {
                            // Calculate bounds for non-point geometries
                            const bounds = new maplibregl.LngLatBounds();
                            const geomType = geoJsonData.geometry.type;

                            if (geomType === 'LineString') {
                                coords.forEach(coord => bounds.extend(coord));
                            } else if (geomType === 'Polygon') {
                                coords.forEach(ring => {
                                    ring.forEach(coord => bounds.extend(coord));
                                });
                            }

                            if (!bounds.isEmpty()) {
                                map.fitBounds(bounds, { padding: 50, duration: 0 });
                            }
                        }
                    }
                }

                currentFeatureCollection = draw.getAll();
            } catch (e) {
                console.error('Error loading GeoJSON:', e);
            }
        };

        // Button event listeners
        document.getElementById('select-btn').addEventListener('click', () => setDrawMode('select'));
        document.getElementById('point-btn').addEventListener('click', () => setDrawMode('point'));
        document.getElementById('line-btn').addEventListener('click', () => setDrawMode('line'));
        document.getElementById('polygon-btn').addEventListener('click', () => setDrawMode('polygon'));
        document.getElementById('delete-btn').addEventListener('click', deleteSelected);
        document.getElementById('close-panel').addEventListener('click', () => {
            hidePropertiesPanel();
            draw.changeMode('simple_select');
        });

        // Initialize map on load
        initMap();
        setDrawMode('select');
    </script>
</body>
</html>
        """.trimIndent()
    }

    override fun dispose() {
        updateTimer?.stop()
        browser.dispose()
    }
}