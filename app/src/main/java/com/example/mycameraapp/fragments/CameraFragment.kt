package com.example.mycameraapp.fragments

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.mycameraapp.MainActivity
import com.example.mycameraapp.databinding.CameraUiBinding
import com.example.mycameraapp.databinding.FragmentCameraBinding
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.example.mycameraapp.R


class CameraFragment : Fragment(R.layout.fragment_camera) {
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private val fragmentCameraBinding by viewBinding(FragmentCameraBinding::bind)
    private var cameraUiBinding: CameraUiBinding? = null
    private lateinit var outputDirectory: File

    private var displayId: Int = -1
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager

    private lateinit var cameraExecutor: ExecutorService


    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private var filenameTemplate = "MyCameraApp"
    private var templateEditTextString: String? = null
    private var photoCounter = 0

    private var pref: SharedPreferences? = null
    private var templateDialog: AlertDialog? = null
    private var templateEditText: EditText? = null


    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                imageCapture?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        displayManager.registerDisplayListener(displayListener, null)

        windowManager = WindowManager(view.context)

        cameraExecutor = Executors.newSingleThreadExecutor()


        outputDirectory = MainActivity.getOutputDirectory(requireContext())


        fragmentCameraBinding.viewFinder.post {

            displayId = fragmentCameraBinding.viewFinder.display.displayId
            updateCameraUI()
            setupCamera()
        }

        templateEditTextString = savedInstanceState?.getString(TEMPLATE_EDIT_TEXT_KEY)

        savedInstanceState?.getBoolean(IS_EDITING_KEY, false)?.let {
            if (it)
                showChangeTemplateDialog()
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pref = requireContext().getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
        pref?.let {
            photoCounter = it.getInt(COUNTER_KEY, 0)
            filenameTemplate = it.getString(TEMPLATE_KEY, "MyCameraApp").toString()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        val editor = pref?.edit()
        editor?.putInt(COUNTER_KEY, photoCounter)
        editor?.putString(TEMPLATE_KEY, filenameTemplate)
        editor?.apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraFragmentToPermissionFragment()
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        templateDialog?.isShowing?.let {
            outState.putBoolean(IS_EDITING_KEY, it)
        }
        templateEditText?.let {
            outState.putString(TEMPLATE_EDIT_TEXT_KEY, it.text.toString())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindPreviewCapture()
    }

    private fun setGalleryThumbnail(uri: Uri) {
        cameraUiBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                Glide.with(photoViewButton)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    private fun setupCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            cameraProvider = cameraProviderFuture.get()
            bindPreviewCapture()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindPreviewCapture() {
        val metrics = windowManager.getCurrentWindowMetrics().bounds

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())

        val rotation = fragmentCameraBinding.viewFinder.display.rotation


        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()



        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        cameraProvider.unbindAll()

        try {

            camera = cameraProvider.bindToLifecycle(
                this as LifecycleOwner, cameraSelector, preview, imageCapture
            )

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Binding failed", exc)
        }
    }

    private fun updateCameraUI() {
        cameraUiBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiBinding = CameraUiBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.root,
            true
        )

        cameraUiBinding?.counterTextView?.text = getString(R.string.counter_text, photoCounter)

        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        cameraUiBinding?.cameraCaptureButton?.setOnClickListener {

            imageCapture?.let { imageCapture ->

                val photoFile =
                    createFile(
                        outputDirectory,
                        FILENAME,
                        PHOTO_EXTENSION,
                        filenameTemplate,
                        photoCounter
                    )

                val metadata = ImageCapture.Metadata()

                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(metadata)
                    .build()

                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            photoCounter += 1
                            viewLifecycleOwner.lifecycleScope.launch {
                                cameraUiBinding?.counterTextView?.text =
                                    getString(R.string.counter_text, photoCounter)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                setGalleryThumbnail(savedUri)
                            }

                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                            ) { _, uri ->
                                Log.d(TAG, "Image capture scanned into media store: $uri")
                            }
                        }
                    })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                            { fragmentCameraBinding.root.foreground = null }, 50L
                        )
                    }, 100L)
                }
            }

        }
        cameraUiBinding?.templateSwitchButton?.setOnClickListener {
            showChangeTemplateDialog()
        }

        cameraUiBinding?.photoViewButton?.setOnClickListener {
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(
                    requireActivity(), R.id.fragment_container
                ).navigate(
                    CameraFragmentDirections
                        .actionCameraFragmentToGalleryFragment(outputDirectory.absolutePath)
                )
            }
        }
    }

    private fun showChangeTemplateDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(templateEditTextString ?: filenameTemplate)
            gravity = 1
        }
        templateEditText = input

        templateDialog = AlertDialog.Builder(requireContext())
            .setView(input)
            .setTitle(R.string.template_dialog_title)
            .setPositiveButton(
                R.string.positive_button_text,
                null
            )
            .setNegativeButton(R.string.negative_button_text, null)
            .create().apply {
                setOnShowListener {
                    val button: Button = getButton(AlertDialog.BUTTON_POSITIVE)
                    button.setOnClickListener {
                        if (input.text.toString()
                                .isNotBlank() && input.text.toString() != filenameTemplate
                        ) {
                            filenameTemplate = input.text.toString()
                            templateEditTextString = null
                            photoCounter = 0
                            cameraUiBinding?.counterTextView?.text =
                                getString(R.string.counter_text, photoCounter)
                            dismiss()
                        } else if (input.text.toString().isBlank()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.empty_template_message,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                R.string.same_template_message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    val button2: Button = getButton(AlertDialog.BUTTON_NEGATIVE)
                    button2.setOnClickListener {
                        templateEditTextString = null
                        dismiss()
                    }
                }
                show()
            }

    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    companion object {

        private const val TAG = "MyCameraApp"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val APP_PREFERENCES = "photos_counter"

        private const val COUNTER_KEY = "cnt"
        private const val TEMPLATE_KEY = "template"
        private const val IS_EDITING_KEY = "is_editing"
        private const val TEMPLATE_EDIT_TEXT_KEY = "template_edit_text"

        val EXTENSION_WHITELIST = arrayOf("JPG")



        private fun createFile(
            baseFolder: File,
            format: String,
            extension: String,
            template: String,
            photoCounter: Int
        ) =
            File(
                baseFolder, SimpleDateFormat(format, Locale.US)
                    .format(System.currentTimeMillis()) + "-$template-$photoCounter" + extension
            )
    }

}