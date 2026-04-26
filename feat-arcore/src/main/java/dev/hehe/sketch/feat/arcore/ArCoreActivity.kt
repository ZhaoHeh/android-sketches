package dev.hehe.sketch.feat.arcore

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.rendering.DpToMetersViewSizer
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode

class ArCoreActivity : AppCompatActivity() {
    private lateinit var arFragment: ArFragment
    private lateinit var statusView: TextView
    private lateinit var styleNameView: TextView
    private lateinit var hintView: TextView
    private lateinit var loadingView: View
    private lateinit var placeButton: MaterialButton

    private var wallArtRenderable: ViewRenderable? = null
    private var currentAnchorNode: AnchorNode? = null
    private var currentArtNode: TransformableNode? = null
    private var currentStyleIndex = 0
    private var arSceneBound = false

    private val styles = listOf(
        WallArtStyle(
            nameRes = R.string.arcore_style_landscape_name,
            artRes = R.drawable.wall_art_nature_landscape
        ),
        WallArtStyle(
            nameRes = R.string.arcore_style_geometry_name,
            artRes = R.drawable.wall_art_geometric_composition
        ),
        WallArtStyle(
            nameRes = R.string.arcore_style_skyline_name,
            artRes = R.drawable.wall_art_city_skyline
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arcore)

        arFragment =
            supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        statusView = findViewById(R.id.statusView)
        styleNameView = findViewById(R.id.styleNameView)
        hintView = findViewById(R.id.hintView)
        loadingView = findViewById(R.id.loadingView)
        placeButton = findViewById(R.id.actionPlaceArtwork)

        bindArConfiguration()
        bindControls()
        updateStyleTexts()
        buildWallArtRenderable()
    }

    override fun onResume() {
        super.onResume()
        ensureArSceneBound()
    }

    private fun bindArConfiguration() {
        arFragment.setOnSessionConfigurationListener { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.focusMode = Config.FocusMode.AUTO
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
        }
    }

    private fun bindControls() {
        findViewById<MaterialButton>(R.id.actionSwitchStyle).setOnClickListener {
            currentStyleIndex = (currentStyleIndex + 1) % styles.size
            updateStyleTexts()
            updateRenderableView()
        }

        placeButton.setOnClickListener {
            tryPlaceAtScreenCenter()
        }

        findViewById<MaterialButton>(R.id.actionResetPlacement).setOnClickListener {
            clearPlacedArt()
            statusView.text = getString(R.string.arcore_status_reset)
            hintView.text = getString(R.string.arcore_hint_scan_wall)
            placeButton.isEnabled = false
        }
    }

    private fun ensureArSceneBound() {
        if (arSceneBound) {
            return
        }

        val sceneView = arFragment.arSceneView
        if (sceneView == null) {
            window.decorView.post { ensureArSceneBound() }
            return
        }

        arSceneBound = true
        bindArScene(sceneView)
    }

    private fun bindArScene(sceneView: com.google.ar.sceneform.ArSceneView) {
        arFragment.setOnTapArPlaneListener { hitResult, plane, _ ->
            val renderable = wallArtRenderable
            if (renderable == null) {
                Toast.makeText(this, R.string.arcore_status_loading_art, Toast.LENGTH_SHORT).show()
                return@setOnTapArPlaneListener
            }

            if (plane.type == Plane.Type.VERTICAL) {
                placeWallArt(hitResult, renderable, PlacementMode.VERTICAL_PLANE)
            }
        }

        sceneView.scene.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_UP &&
                currentAnchorNode == null &&
                wallArtRenderable != null
            ) {
                tryPlaceFromTouch(sceneView, motionEvent)
            }
            false
        }

        sceneView.scene.addOnUpdateListener {
            val frame = sceneView.arFrame ?: return@addOnUpdateListener
            val camera = frame.camera
            if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                statusView.text = getString(R.string.arcore_status_searching)
                hintView.text = getString(R.string.arcore_hint_move_phone)
                placeButton.isEnabled = false
                return@addOnUpdateListener
            }

            if (currentAnchorNode == null) {
                val readyCandidate = findBestPlacementHit(
                    frame = frame,
                    x = sceneView.width / 2f,
                    y = sceneView.height / 2f
                )
                placeButton.isEnabled = wallArtRenderable != null
                statusView.text = if (readyCandidate != null) {
                    getString(R.string.arcore_status_ready)
                } else {
                    getString(R.string.arcore_status_searching_wall)
                }

                hintView.text = if (readyCandidate != null) {
                    when (readyCandidate.mode) {
                        PlacementMode.VERTICAL_PLANE -> getString(R.string.arcore_hint_center_wall)
                        PlacementMode.DEPTH_POINT -> getString(R.string.arcore_hint_depth_wall)
                        PlacementMode.FEATURE_POINT -> getString(R.string.arcore_hint_feature_wall)
                        PlacementMode.INSTANT -> getString(R.string.arcore_hint_instant_wall)
                    }
                } else {
                    getString(R.string.arcore_hint_place_anyway)
                }
            } else {
                placeButton.isEnabled = false
            }
        }
    }

    private fun buildWallArtRenderable() {
        loadingView.visibility = View.VISIBLE
        statusView.text = getString(R.string.arcore_status_loading_art)

        ViewRenderable.builder()
            .setView(this, R.layout.view_wall_art_sticker)
            .build()
            .thenAccept { renderable ->
                renderable.isShadowCaster = false
                renderable.isShadowReceiver = false
                renderable.sizer = DpToMetersViewSizer(WALL_ART_DP_PER_METER)
                renderable.verticalAlignment = ViewRenderable.VerticalAlignment.CENTER
                renderable.horizontalAlignment = ViewRenderable.HorizontalAlignment.CENTER
                wallArtRenderable = renderable
                updateRenderableView()
                loadingView.visibility = View.GONE
            }
            .exceptionally { throwable ->
                loadingView.visibility = View.GONE
                statusView.text = getString(R.string.arcore_status_load_failed)
                hintView.text = throwable.localizedMessage
                    ?: getString(R.string.arcore_status_load_failed_hint)
                null
            }
    }

    private fun updateRenderableView() {
        val renderable = wallArtRenderable ?: return
        val style = styles[currentStyleIndex]
        val renderableView = renderable.view
        renderableView.findViewById<ImageView>(R.id.wallArtImage).setImageResource(style.artRes)
    }

    private fun tryPlaceFromTouch(
        sceneView: com.google.ar.sceneform.ArSceneView,
        motionEvent: MotionEvent
    ) {
        val frame = sceneView.arFrame ?: return
        val renderable = wallArtRenderable ?: return
        val placementHit = findBestPlacementHit(frame, motionEvent.x, motionEvent.y)

        if (placementHit == null) {
            statusView.text = getString(R.string.arcore_status_need_wall)
            hintView.text = getString(R.string.arcore_hint_need_wall)
            return
        }

        placeWallArt(placementHit.hitResult, renderable, placementHit.mode)
    }

    private fun tryPlaceAtScreenCenter() {
        val sceneView = arFragment.arSceneView ?: return
        val frame = sceneView.arFrame ?: return
        val renderable = wallArtRenderable ?: return
        val placementHit = findBestPlacementHit(
            frame = frame,
            x = sceneView.width / 2f,
            y = sceneView.height / 2f
        )

        if (placementHit == null) {
            statusView.text = getString(R.string.arcore_status_need_wall)
            hintView.text = getString(R.string.arcore_hint_center_retry)
            return
        }

        placeWallArt(placementHit.hitResult, renderable, placementHit.mode)
    }

    private fun placeWallArt(
        hitResult: HitResult,
        renderable: ViewRenderable,
        placementMode: PlacementMode
    ) {
        clearPlacedArt()

        val anchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(anchor).apply {
            setParent(arFragment.arSceneView.scene)
        }

        val artNode = TransformableNode(arFragment.transformationSystem).apply {
            setParent(anchorNode)
            this.renderable = renderable
            worldRotation = createCameraFacingWallRotation(anchorNode.worldPosition)
            localScale = Vector3(0.85f, 0.85f, 0.85f)
            select()
        }

        currentAnchorNode = anchorNode
        currentArtNode = artNode

        when (placementMode) {
            PlacementMode.VERTICAL_PLANE -> {
                statusView.text = getString(R.string.arcore_status_placed)
                hintView.text = getString(R.string.arcore_hint_reposition)
            }
            PlacementMode.DEPTH_POINT -> {
                statusView.text = getString(R.string.arcore_status_placed_depth)
                hintView.text = getString(R.string.arcore_hint_reposition)
            }
            PlacementMode.FEATURE_POINT -> {
                statusView.text = getString(R.string.arcore_status_placed_feature)
                hintView.text = getString(R.string.arcore_hint_reposition)
            }
            PlacementMode.INSTANT -> {
                statusView.text = getString(R.string.arcore_status_placed_instant)
                hintView.text = getString(R.string.arcore_hint_refine_wall)
            }
        }
        placeButton.isEnabled = false
    }

    private fun clearPlacedArt() {
        currentArtNode?.setParent(null)
        currentArtNode = null

        currentAnchorNode?.anchor?.detach()
        currentAnchorNode?.setParent(null)
        currentAnchorNode = null
    }

    private fun updateStyleTexts() {
        val style = styles[currentStyleIndex]
        styleNameView.text = getString(style.nameRes)
    }

    private fun createCameraFacingWallRotation(anchorPosition: Vector3): Quaternion {
        val cameraPosition = arFragment.arSceneView.scene.camera.worldPosition
        val toCamera = Vector3.subtract(cameraPosition, anchorPosition)
        val yawDegrees = Math.toDegrees(
            kotlin.math.atan2(toCamera.x.toDouble(), toCamera.z.toDouble())
        ).toFloat()
        return Quaternion.axisAngle(Vector3(0f, 1f, 0f), yawDegrees)
    }

    private fun findBestPlacementHit(
        frame: Frame,
        x: Float,
        y: Float
    ): PlacementHit? {
        val standardHit = frame.hitTest(x, y)

        standardHit.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.type == Plane.Type.VERTICAL &&
                trackable.isPoseInPolygon(hit.hitPose)
        }?.let { hit ->
            return PlacementHit(hit, PlacementMode.VERTICAL_PLANE)
        }

        standardHit.firstOrNull { hit -> hit.trackable is DepthPoint }?.let { hit ->
            return PlacementHit(hit, PlacementMode.DEPTH_POINT)
        }

        standardHit.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Point &&
                trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }?.let { hit ->
            return PlacementHit(hit, PlacementMode.FEATURE_POINT)
        }

        frame.hitTestInstantPlacement(x, y, APPROXIMATE_WALL_DISTANCE_METERS)
            .firstOrNull { hit -> hit.trackable is InstantPlacementPoint }
            ?.let { hit ->
                return PlacementHit(hit, PlacementMode.INSTANT)
            }

        return null
    }
}

private data class WallArtStyle(
    val nameRes: Int,
    val artRes: Int
)

private data class PlacementHit(
    val hitResult: HitResult,
    val mode: PlacementMode
)

private enum class PlacementMode {
    VERTICAL_PLANE,
    DEPTH_POINT,
    FEATURE_POINT,
    INSTANT
}

private const val APPROXIMATE_WALL_DISTANCE_METERS = 2.0f
private const val WALL_ART_DP_PER_METER = 900
