package dev.hehe.sketch.feat.arcore

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.FixedHeightViewSizer
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode

class ArCoreActivity : AppCompatActivity() {
    private lateinit var arFragment: ArFragment
    private lateinit var statusView: TextView
    private lateinit var styleNameView: TextView
    private lateinit var hintView: TextView
    private lateinit var loadingView: View

    private var wallArtRenderable: ViewRenderable? = null
    private var currentAnchorNode: AnchorNode? = null
    private var currentArtNode: TransformableNode? = null
    private var currentStyleIndex = 0
    private var arSceneBound = false

    private val styles = listOf(
        WallArtStyle(
            nameRes = R.string.arcore_style_sunset_name,
            subtitleRes = R.string.arcore_style_sunset_subtitle,
            artRes = R.drawable.bg_wall_art_sunset
        ),
        WallArtStyle(
            nameRes = R.string.arcore_style_geometry_name,
            subtitleRes = R.string.arcore_style_geometry_subtitle,
            artRes = R.drawable.bg_wall_art_geometry
        ),
        WallArtStyle(
            nameRes = R.string.arcore_style_botanic_name,
            subtitleRes = R.string.arcore_style_botanic_subtitle,
            artRes = R.drawable.bg_wall_art_botanic
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

        findViewById<MaterialButton>(R.id.actionResetPlacement).setOnClickListener {
            clearPlacedArt()
            statusView.text = getString(R.string.arcore_status_reset)
            hintView.text = getString(R.string.arcore_hint_scan_wall)
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
            if (plane.type != Plane.Type.VERTICAL) {
                statusView.text = getString(R.string.arcore_status_need_wall)
                hintView.text = getString(R.string.arcore_hint_need_wall)
                return@setOnTapArPlaneListener
            }

            val renderable = wallArtRenderable
            if (renderable == null) {
                Toast.makeText(this, R.string.arcore_status_loading_art, Toast.LENGTH_SHORT).show()
                return@setOnTapArPlaneListener
            }

            placeWallArt(hitResult, renderable)
        }

        sceneView.scene.addOnUpdateListener {
            val frame = sceneView.arFrame ?: return@addOnUpdateListener
            val camera = frame.camera
            if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                statusView.text = getString(R.string.arcore_status_searching)
                hintView.text = getString(R.string.arcore_hint_move_phone)
                return@addOnUpdateListener
            }

            if (currentAnchorNode == null) {
                val hasVerticalPlane = hasTrackedVerticalPlane()
                statusView.text = if (hasVerticalPlane) {
                    getString(R.string.arcore_status_ready)
                } else {
                    getString(R.string.arcore_status_searching_wall)
                }
                hintView.text = if (hasVerticalPlane) {
                    getString(R.string.arcore_hint_tap_wall)
                } else {
                    getString(R.string.arcore_hint_scan_wall)
                }
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
                renderable.sizer = FixedHeightViewSizer(0.55f)
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
        renderableView.findViewById<TextView>(R.id.wallArtTitle).text = getString(style.nameRes)
        renderableView.findViewById<TextView>(R.id.wallArtSubtitle).text =
            getString(style.subtitleRes)
        renderableView.findViewById<ImageView>(R.id.wallArtImage).setImageResource(style.artRes)
    }

    private fun placeWallArt(hitResult: HitResult, renderable: ViewRenderable) {
        clearPlacedArt()

        val anchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(anchor).apply {
            setParent(arFragment.arSceneView.scene)
        }

        val artNode = TransformableNode(arFragment.transformationSystem).apply {
            setParent(anchorNode)
            this.renderable = renderable
            localScale = Vector3(0.85f, 0.85f, 0.85f)
            select()
        }

        currentAnchorNode = anchorNode
        currentArtNode = artNode

        statusView.text = getString(R.string.arcore_status_placed)
        hintView.text = getString(R.string.arcore_hint_reposition)
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

    private fun hasTrackedVerticalPlane(): Boolean {
        val session = arFragment.arSceneView.session ?: return false
        return session.getAllTrackables(Plane::class.java).any { plane ->
            plane.type == Plane.Type.VERTICAL &&
                plane.trackingState == com.google.ar.core.TrackingState.TRACKING
        }
    }
}

private data class WallArtStyle(
    val nameRes: Int,
    val subtitleRes: Int,
    val artRes: Int
)
