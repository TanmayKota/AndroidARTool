package com.example.ar_google_maps_app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.EnumSet

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // ARCore session object
    private var arSession: Session? = null

    // used to request ARCore install if missing
    private var userRequestedInstall = true

    // GLSurfaceView that provides GL context for ARCore
    private var glSurfaceView: GLSurfaceView? = null

    // UI references
    private var glContainer: FrameLayout? = null
    private var toggleButton: Button? = null
    private var statusText: TextView? = null

    // Flag set when GL surface + camera texture were created
    @Volatile private var surfaceReady = false

    // If onResume was called but surface wasn't ready yet, we set this to true so we resume session when ready.
    @Volatile private var pendingResumeWhenSurfaceReady = false

    // Permissions required
    private val REQUIRED_PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)

    // Permission launcher
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val grantedAll = REQUIRED_PERMS.all { results[it] == true }
            if (grantedAll) {
                Log.d(TAG, "Permissions granted")
                updateStatus("Permissions granted — ready")
            } else {
                Log.e(TAG, "Required permissions not granted")
                updateStatus("Permissions denied. Grant CAMERA + LOCATION and restart the app.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // inflate XML layout
        setContentView(R.layout.mainlayout)

        // find views
        glContainer = findViewById(R.id.gl_container)
        toggleButton = findViewById(R.id.button_toggle_camera)
        statusText = findViewById(R.id.status_text)

        // request permissions if needed
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(REQUIRED_PERMS)
            updateStatus("Requesting CAMERA + LOCATION permissions...")
        } else {
            updateStatus("Permissions already granted — ready")
        }

        // button toggles camera view on/off
        toggleButton?.setOnClickListener {
            val showing = (glSurfaceView != null)
            if (!showing) {
                showCameraView()
            } else {
                hideCameraView()
            }
        }
    }

    private fun updateStatus(text: String) {
        Log.d(TAG, text)
        runOnUiThread { statusText?.text = text }
    }

    private fun showCameraView() {
        // create GLSurfaceView only if not already created
        if (glSurfaceView == null) {
            glSurfaceView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(2)

                // attach renderer
                setRenderer(
                    ARRenderer(
                        sessionProvider = { arSession },
                        onSurfaceCreatedCallback = {
                            surfaceReady = true
                            Log.d(TAG, "GL surface ready")
                            if (pendingResumeWhenSurfaceReady) {
                                pendingResumeWhenSurfaceReady = false
                                tryResumeSessionIfReady()
                            }
                        },
                        onPoseUpdate = { lat, lng, alt, hAcc ->
                            if (lat != null && lng != null) {
                                updateStatus("Localizing: lat=%.6f, lng=%.6f, alt=%.2fm (hAcc=%.2fm)".format(lat, lng, alt, hAcc ?: 0f))
                            } else {
                                updateStatus("Localizing... (Earth not tracking yet)")
                            }
                        },
                        onError = { t ->
                            Log.e(TAG, "Renderer error", t)
                            updateStatus("Frame update error: ${t::class.java.simpleName}: ${t.message}")
                        },
                        flipXDefault = false,
                        flipYDefault = true,
                        swapXYDefault = false
                    )
                )
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            // add to container
            glContainer?.addView(glSurfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))

            toggleButton?.text = "Hide Camera"

            // call glSurfaceView.onResume() later in onResume() lifecycle if needed
            // but if already resumed, call it now:
            glSurfaceView?.onResume()
        }
    }

    private fun hideCameraView() {
        try {
            // pause GL and session
            glSurfaceView?.onPause()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing GLSurfaceView: ${e.message}")
        }

        try {
            arSession?.pause()
            updateStatus("Camera turned OFF (session paused)")
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing ARSession: ${e.message}")
            updateStatus("Camera turned OFF")
        }

        // remove GLSurfaceView from container and destroy reference
        glContainer?.removeView(glSurfaceView)
        glSurfaceView = null
        toggleButton?.text = "Show Camera"
        surfaceReady = false
    }

    override fun onResume() {
        super.onResume()

        if (!hasAllPermissions()) {
            Log.w(TAG, "Missing permissions; will not start AR session until granted.")
            return
        }

        // Ensure ARCore is installed/updated
        try {
            val availability = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
            when (availability) {
                ArCoreApk.InstallStatus.INSTALLED -> userRequestedInstall = false
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    userRequestedInstall = false
                    updateStatus("Requesting ARCore installation...")
                    return
                }
            }
        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.e(TAG, "ARCore not installed: ${e.message}")
            updateStatus("ARCore not installed")
            return
        } catch (e: UnavailableApkTooOldException) {
            Log.e(TAG, "ARCore APK too old: ${e.message}")
            updateStatus("ARCore APK too old")
            return
        } catch (e: UnavailableSdkTooOldException) {
            Log.e(TAG, "ARCore SDK too old: ${e.message}")
            updateStatus("ARCore SDK too old")
            return
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.e(TAG, "Device not compatible with ARCore: ${e.message}")
            updateStatus("Device not compatible with ARCore")
            return
        }

        // Create/configure session if needed
        if (arSession == null) {
            try {
                arSession = Session(this)

                // Try to set the highest-resolution camera config available
                setHighResCamera(arSession!!)

                val config = Config(arSession)
                if (arSession!!.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                    config.geospatialMode = Config.GeospatialMode.ENABLED
                } else {
                    config.geospatialMode = Config.GeospatialMode.DISABLED
                }
                arSession!!.configure(config)
                updateStatus("AR session created (geospatial mode set)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session: ${e.message}")
                arSession = null
                updateStatus("Failed to create AR session")
                return
            }
        }

        // If GLSurfaceView exists and surface is ready, resume the session
        glSurfaceView?.onResume()
        if (surfaceReady) {
            tryResumeSessionIfReady()
        } else {
            pendingResumeWhenSurfaceReady = true
        }
    }

    /**
     * Selects and applies the highest-resolution camera configuration available.
     * Call this after creating the Session and before session.configure()/resume().
     */
    private fun setHighResCamera(session: Session) {
        try {
            // prefer 30fps configs and pick the config with the largest image size
            val filter = CameraConfigFilter(session).apply {
                // targetFps expects an EnumSet<CameraConfig.TargetFps>
                targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
            }

            val configs: List<CameraConfig> = session.getSupportedCameraConfigs(filter)
            if (configs.isEmpty()) {
                Log.w(TAG, "No camera configs returned by ARCore")
                return
            }
            // pick the largest resolution available (width * height)
            val ultraResConfig = configs.maxByOrNull { it.imageSize.width * it.imageSize.height }
            if (ultraResConfig != null) {
                try {
                    session.cameraConfig = ultraResConfig
                    Log.d(TAG, "Using high-res camera: ${ultraResConfig.imageSize.width}x${ultraResConfig.imageSize.height}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set cameraConfig: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setHighResCamera failed: ${e.message}")
        }
    }

    private fun tryResumeSessionIfReady() {
        try {
            arSession?.resume()
            updateStatus("AR session resumed — localizing...")
            Log.d(TAG, "arSession resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume AR session: ${e.message}", e)
            updateStatus("Failed to resume AR session: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            arSession?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Error while pausing session: ${e.message}")
        }
        glSurfaceView?.onPause()
        updateStatus("AR session paused")
    }

    private fun hasAllPermissions(): Boolean {
        for (perm in REQUIRED_PERMS) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    // Renderer with camera-background rendering (full-screen quad)
    private class ARRenderer(
        private val sessionProvider: () -> Session?,
        private val onSurfaceCreatedCallback: () -> Unit,
        private val onPoseUpdate: (Double?, Double?, Double?, Float?) -> Unit,
        private val onError: (Throwable) -> Unit,
        // toggles: set flipY=true to remove vertical mirroring
        private val flipXDefault: Boolean = false,
        private val flipYDefault: Boolean = true,
        private val swapXYDefault: Boolean = false
    ) : GLSurfaceView.Renderer {

        private val TAG = "ARRenderer"
        private val mainHandler = Handler(Looper.getMainLooper())
        private val cameraTextureId = IntArray(1)
        private var surfaceCreatedCalled = false

        // Quad coords (NDC)
        private val quadCoords = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
        )
        // Default tex coords (0..1)
        private val quadTexCoords = floatArrayOf(
            1f, 0f,
            1f, 1f,
            0f, 0f,
            0f, 1f
        )

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadCoords); position(0) }

        private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadTexCoords); position(0) }

        // Program / attribute/uniform handles
        private var bgProgram = 0
        private var aPositionHandle = -1
        private var aTexCoordHandle = -1
        private var uTextureHandle = -1
        private var uFlipXHandle = -1
        private var uFlipYHandle = -1
        private var uSwapXYHandle = -1

        // Shader sources (vertex unchanged)
        private val VERTEX_SHADER = """
        attribute vec2 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
          v_TexCoord = a_TexCoord;
          gl_Position = vec4(a_Position, 0.0, 1.0);
        }
    """.trimIndent()

        // Fragment shader: supports flipX, flipY, swapXY uniforms (integers 0/1)
        private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES u_Texture;
        uniform int u_flipX;
        uniform int u_flipY;
        uniform int u_swapXY;
        void main() {
          vec2 tex = v_TexCoord;
          if (u_swapXY == 1) {
            tex = vec2(tex.y, tex.x);
          }
          if (u_flipX == 1) {
            tex.x = 1.0 - tex.x;
          }
          if (u_flipY == 1) {
            tex.y = 1.0 - tex.y;
          }
          // sample external OES texture
          gl_FragColor = texture2D(u_Texture, tex);
        }
    """.trimIndent()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Create external texture for camera
            GLES20.glGenTextures(1, cameraTextureId, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            // compile & link shaders into program (with error logging)
            try {
                createProgram()
            } catch (ex: RuntimeException) {
                Log.e(TAG, "Shader program creation failed", ex)
                mainHandler.post { onError(ex) }
                return
            }

            // notify Activity / caller that GL context + texture are ready
            if (!surfaceCreatedCalled) {
                surfaceCreatedCalled = true
                mainHandler.post { onSurfaceCreatedCallback() }
            }

            // try to set camera texture on session if available
            try {
                sessionProvider()?.setCameraTextureName(cameraTextureId[0])
            } catch (t: Throwable) {
                // may fail here; renderer will set again in onDrawFrame
                Log.d(TAG, "setCameraTextureName failed onSurfaceCreated: ${t.message}")
            }

            // Set default uniform toggles right away (safe here because program is created)
            GLES20.glUseProgram(bgProgram)
            GLES20.glUniform1i(uFlipXHandle, if (flipXDefault) 1 else 0)
            GLES20.glUniform1i(uFlipYHandle, if (flipYDefault) 1 else 0)
            GLES20.glUniform1i(uSwapXYHandle, if (swapXYDefault) 1 else 0)
            GLES20.glUseProgram(0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val session = sessionProvider() ?: run {
                mainHandler.post { onPoseUpdate(null, null, null, null) }
                return
            }

            try {
                // ensure the session knows our camera texture
                session.setCameraTextureName(cameraTextureId[0])

                // call update on GL thread
                val frame = session.update()

                // draw camera background (uses program and toggles)
                drawCameraBackground()

                // geospatial pose extraction (unchanged)
                val earth = session.earth
                if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                    val geoPose = earth.cameraGeospatialPose
                    val lat = geoPose.latitude
                    val lng = geoPose.longitude
                    val alt = geoPose.altitude
                    val rawHAcc = try { geoPose.getHorizontalAccuracy() } catch (_: Throwable) { null }
                    val hAccFloat: Float? = when (rawHAcc) {
                        is Float -> rawHAcc
                        is Double -> rawHAcc.toFloat()
                        else -> null
                    }
                    mainHandler.post { onPoseUpdate(lat, lng, alt, hAccFloat) }
                } else {
                    mainHandler.post { onPoseUpdate(null, null, null, null) }
                }
            } catch (e: CameraNotAvailableException) {
                mainHandler.post { onError(e) }
            } catch (t: Throwable) {
                mainHandler.post { onError(t) }
            }
        }

        // ------- shader compile / link / helpers -------
        private fun loadShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                Log.e(TAG, "Shader compile failed: $info")
                throw RuntimeException("Shader compile failed: $info")
            }
            return shader
        }

        private fun createProgram() {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

            bgProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(bgProgram, v)
            GLES20.glAttachShader(bgProgram, f)
            GLES20.glLinkProgram(bgProgram)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(bgProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES20.glGetProgramInfoLog(bgProgram)
                GLES20.glDeleteProgram(bgProgram)
                Log.e(TAG, "Program link failed: $info")
                throw RuntimeException("Program link failed: $info")
            }

            // cache locations
            aPositionHandle = GLES20.glGetAttribLocation(bgProgram, "a_Position")
            aTexCoordHandle = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
            uTextureHandle = GLES20.glGetUniformLocation(bgProgram, "u_Texture")
            uFlipXHandle = GLES20.glGetUniformLocation(bgProgram, "u_flipX")
            uFlipYHandle = GLES20.glGetUniformLocation(bgProgram, "u_flipY")
            uSwapXYHandle = GLES20.glGetUniformLocation(bgProgram, "u_swapXY")

            // sanity logs
            Log.d(TAG, "shader locs: aPos=$aPositionHandle aTex=$aTexCoordHandle uTex=$uTextureHandle flipX=$uFlipXHandle flipY=$uFlipYHandle swapXY=$uSwapXYHandle")
        }

        private fun drawCameraBackground() {
            GLES20.glUseProgram(bgProgram)

            // enable and bind vertex attributes
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

            // bind OES texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId[0])
            GLES20.glUniform1i(uTextureHandle, 0)

            // draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // disable attributes
            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTexCoordHandle)
            GLES20.glUseProgram(0)
        }

        // Optional: runtime toggle setters (call from UI thread if you want)
        fun setFlipX(enabled: Boolean) {
            GLES20.glUseProgram(bgProgram)
            GLES20.glUniform1i(uFlipXHandle, if (enabled) 1 else 0)
            GLES20.glUseProgram(0)
        }
        fun setFlipY(enabled: Boolean) {
            GLES20.glUseProgram(bgProgram)
            GLES20.glUniform1i(uFlipYHandle, if (enabled) 1 else 0)
            GLES20.glUseProgram(0)
        }
        fun setSwapXY(enabled: Boolean) {
            GLES20.glUseProgram(bgProgram)
            GLES20.glUniform1i(uSwapXYHandle, if (enabled) 1 else 0)
            GLES20.glUseProgram(0)
        }
    }
}
