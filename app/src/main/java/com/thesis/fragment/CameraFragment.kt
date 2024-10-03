/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thesis.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.camera.view.PreviewView
import androidx.core.content.PermissionChecker
import com.thesis.MainViewModel
import com.thesis.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.thesis.ApiService
import com.thesis.HandLandmarkerHelper
import com.thesis.R
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener{

    companion object {
        private const val TAG = "Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!
    //hands
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    lateinit var textview:TextView
    lateinit var tts_btn:Button
    //pose



    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isRecording=false
    private val retrofit = Retrofit.Builder()
        .baseUrl("http://220.69.208.121:5000/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    val apiService = retrofit.create(ApiService::class.java)

    override fun onResume() {
        super.onResume()


        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }

        }
    }

    override fun onPause() {
        super.onPause()
        if(this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)



            // Close the HandLandmarkerHelper and release resources
            backgroundExecutor.execute {
                handLandmarkerHelper.clearHandLandmarker()

            }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textview = view.findViewById(R.id.textView)
        tts_btn = view.findViewById(R.id.tts)
        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the HandLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = viewModel.currentDelegate,
                handLandmarkerHelperListener = this,
                tts_text = this.textview,
                tts_btn = this.tts_btn,
                cameraFragment = this
            )



        }


    }


    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }
    // Initialize CameraX, and prepare to bind the camera use cases
    val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()
    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")



        // Preview
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // 16:9 비율 선택
            .build()

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)

            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    try {
                        // 손 인식 수행
                        detectHand(image)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image: ${e.message}")
                    } finally {
                        // 항상 ImageProxy를 닫아줍니다.
                        image.close()
                    }
                }
            }

        cameraProvider.unbindAll()

        try {
            // Preview와 ImageAnalyzer를 생명주기에 바인딩

                // Preview와 ImageAnalyzer 바인딩 (녹화 종료 후 손 검출 재개)
            camera = cameraProvider.bindToLifecycle(
                this@CameraFragment, cameraSelector, preview, imageAnalyzer
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))

                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)



        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: HandLandmarkerHelper.ResultBundle
    ) {

        activity?.runOnUiThread {


            if (_fragmentCameraBinding != null) {

                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()


            }

        }


    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Log.d("error",error)

            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()

        }
    }



    override fun startRecording() {


        if (videoCapture == null || isRecording) {
            return
        }
        // 메인 스레드에서 실행되도록 수정
        ContextCompat.getMainExecutor(requireContext()).execute {
            val videoFileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis())

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, videoFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CameraX-Video")
                }
            }

            val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                requireContext().contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(contentValues)
                .build()
            val mediaUri=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).toString()+"/CameraX-Video/"+videoFileName+".mp4"
            Log.d("uri체크",mediaUri)

            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(
                this@CameraFragment, cameraSelector, preview, videoCapture
            )

            currentRecording = videoCapture!!.output
                .prepareRecording(requireContext(), mediaStoreOutput)
                .apply {
                    if (PermissionChecker.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                        PermissionChecker.PERMISSION_GRANTED) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                    try {
                        when (recordEvent) {
                            is VideoRecordEvent.Start -> {
                                isRecording = true


                            }
                            is VideoRecordEvent.Finalize -> {
                                val uri = createPartFromUri(mediaUri)
                                apiService.uploadVideo(uri).enqueue(object : retrofit2.Callback<ResponseBody>{
                                    override fun onResponse(
                                        call: Call<ResponseBody>,
                                        response: Response<ResponseBody>
                                    ) {
                                        if (response.isSuccessful) {
                                            // 업로드 성공
                                            Log.d(TAG, "Upload successful: ${response.body()}")
                                            val file = File(mediaUri) // URI로부터 파일 경로를 가져옵니다.
                                            if (file.exists()) {
                                                if (file.delete()) {
                                                    Log.d(TAG, "File deleted successfully: ${file.path}")
                                                } else {
                                                    Log.e(TAG, "Failed to delete file: ${file.path}")
                                                }
                                            } else {
                                                Log.e(TAG, "File does not exist: ${file.path}")
                                            }
                                        } else {
                                            // 업로드 실패
                                            Log.e(TAG, "Upload failed: ${response.errorBody()}")
                                        }
                                    }

                                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                        Log.e(TAG, "Upload error: ${t.message}")
                                    }

                                }

                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("오류", "녹화 이벤트 처리 중 오류 발생: ${e.message}")
                        Toast.makeText(requireContext(), "녹화 이벤트 처리 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    override fun stopRecording() {

        if (!isRecording) {
            Log.e("CameraFragment", "녹화가 진행 중이 아닙니다.")
            return
        }
        if (currentRecording == null) return
        ContextCompat.getMainExecutor(requireContext()).execute {
        currentRecording?.stop()
        currentRecording = null
        isRecording = false
        // saveVideoToGallery()를 stopRecording에서 호출하지 않음
        cameraProvider?.unbindAll()
        camera = cameraProvider?.bindToLifecycle(
            this, cameraSelector, preview, imageAnalyzer
        )

        }
    }
    fun createPartFromUri(uri: String):MultipartBody.Part{
        val file =File(uri)
        val requestFile = RequestBody.create("video/mp4".toMediaTypeOrNull(),file)
        return MultipartBody.Part.createFormData("file",file.name,requestFile)
    }




}
