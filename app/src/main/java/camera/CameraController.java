package camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import java.io.File;

import rx.Observable;
import rx.Single;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

@TargetApi(21)
public class CameraController {

    static final String TAG = CameraController.class.getName();

    @NonNull
    private final Context mContext;
    @NonNull
    private final Callback mCallback;
    private final int mLayoutOrientation;
    @NonNull
    private final File mFile;
    @NonNull
    private final AutoFitTextureView mTextureView;
    @NonNull
    private final WindowManager mWindowManager;
    @NonNull
    private final CameraManager mCameraManager;
    private SurfaceParams mSurfaceParams;
    private ImageReader mImageReader;

    private class CameraParams {
        @NonNull
        private final String cameraId;
        @NonNull
        private final CameraCharacteristics cameraCharacteristics;
        @NonNull
        private final Size previewSize;

        private CameraParams(@NonNull String cameraId, @NonNull CameraCharacteristics cameraCharacteristics, @NonNull Size previewSize) {
            this.cameraId = cameraId;
            this.cameraCharacteristics = cameraCharacteristics;
            this.previewSize = previewSize;
        }
    }

    private class SurfaceParams {
        @NonNull
        private final Surface previewSurface;

        private SurfaceParams(@NonNull Surface previewSurface) {
            this.previewSurface = previewSurface;
        }
    }

    private final CompositeSubscription mSubscriptions = new CompositeSubscription();
    private final PublishSubject<Object> mOnPauseSubject = PublishSubject.create();
    private final PublishSubject<Object> mOnShutterClick = PublishSubject.create();
    private final PublishSubject<Object> mOnSwitchCamera = PublishSubject.create();
    private final PublishSubject<SurfaceTexture> mOnSurfaceTextureAvailable = PublishSubject.create();
    private final ConvergeWaiter mAutoFocusConvergeWaiter = ConvergeWaiter.Factory.createAutoFocusConvergeWaiter();
    private final ConvergeWaiter mAutoExposureConvergeWaiter = ConvergeWaiter.Factory.createAutoExposureConvergeWaiter();


    public CameraController(@NonNull Context context, @NonNull Callback callback, @NonNull String photoFileUrl,
                            @NonNull AutoFitTextureView textureView, int layoutOrientation) {
        mContext = context;
        mCallback = callback;
        mFile = new File(photoFileUrl);
        mTextureView = textureView;
        mLayoutOrientation = layoutOrientation;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

    }

    public void takePhoto() {
        mOnShutterClick.onNext(null);
    }

    public void switchCamera() {
        mOnSwitchCamera.onNext(null);
    }

    public AndroidLifecycle getLifecycle() {
        return mLifecycleImpl;
    }

    private CameraParams mCameraParams;
    private final AndroidLifecycle mLifecycleImpl = new AndroidLifecycle() {
        private static final String SIS_CAMERA_ID = "SIS_CAMERA_ID";

        @Override
        public void onCreate(@Nullable Bundle saveState) {
            Log.d(TAG, "\tonCreate");
            String cameraId = null;
            if (saveState != null) {
                cameraId = saveState.getString(SIS_CAMERA_ID);
            }

            try {
                if (cameraId == null) {
                    Log.d(TAG, "\tchoosing default camera");
                    cameraId = CameraStrategy.chooseDefaultCamera(mCameraManager);
                }

                if (cameraId == null) {
                    mCallback.onException(new IllegalStateException("Can't find any camera"));
                    return;
                }

                mCameraParams = getCameraParams(cameraId);
                setTextureAspectRatio(mCameraParams);
            }
            catch (CameraAccessException e) {
                mCallback.onException(e);
                return;
            }

            mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "\tonSurfaceTextureAvailable");
                    mOnSurfaceTextureAvailable.onNext(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    Log.d(TAG, "\tonSurfaceTextureSizeChanged");
                    mOnSurfaceTextureAvailable.onNext(surface);
                    //NO-OP
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    Log.d(TAG, "\tonSurfaceTextureDestroyed");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                }
            });

            // For some reasons onSurfaceSizeChanged is not always called, this is a workaround
            mTextureView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                Log.d(TAG, "\tonLayoutChange");
                if (mTextureView.isAvailable()) {
                    Log.d(TAG, "\tmTextureView.isAvailable()");
                    mOnSurfaceTextureAvailable.onNext(mTextureView.getSurfaceTexture());
                }
            });
        }

        @Override
        public void onDestroy() {
            Log.d(TAG, "\tonDestroy");
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            outState.putString(SIS_CAMERA_ID, mCameraParams.cameraId);
        }

        @Override
        public void onStart() {
            Log.d(TAG, "\tonStart");
        }

        @Override
        public void onResume() {
            Log.d(TAG, "\tonResume");

            if (mTextureView == null) {
                return;
            }

            subscribe();

            // When the screen is turned off and turned back on, the SurfaceTexture is already
            // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
            // a camera and start preview from here (otherwise, we wait until the surface is ready in
            // the SurfaceTextureListener).
            if (mTextureView.isAvailable()) {
                Log.d(TAG, "\tmTextureView.isAvailable()");
                mOnSurfaceTextureAvailable.onNext(mTextureView.getSurfaceTexture());
            }
        }

        @Override
        public void onPause() {
            Log.d(TAG, "\tonPause");
            mOnPauseSubject.onNext(null);
        }

        @Override
        public void onStop() {
            Log.d(TAG, "\tonStop");
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {

        }
    };

    private CameraParams getCameraParams(@NonNull String cameraId) throws CameraAccessException {
        Log.d(TAG, "\tsetupPreviewSize");
        CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraId);
        Size previewSize = CameraStrategy.getPreviewSize(cameraCharacteristics);
        return new CameraParams(cameraId, cameraCharacteristics, previewSize);
    }

    private void setTextureAspectRatio(@NonNull CameraParams cameraParams) {
        // We fit the aspect ratio of TextureView to the size of preview we picked.
        // looks like the dimensions we get from camera characteristics are for Landscape layout, so we swap it for portrait
        if (mLayoutOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(cameraParams.previewSize.getWidth(), cameraParams.previewSize.getHeight());
        }
        else {
            mTextureView.setAspectRatio(cameraParams.previewSize.getHeight(), cameraParams.previewSize.getWidth());
        }
    }

    /**
     * Flow is configured in this method
     */
    private void subscribe() {
        mSubscriptions.clear();

        //this emits state with non-null camera device when camera is opened, and emits camera with null device when it's closed
        //todo change to single
        Observable<CameraDevice> cameraDeviceObservable = mOnSurfaceTextureAvailable.asObservable()
            .first()
            .doOnNext(this::setupSurface)
            .doOnNext(s -> initImageReader())
            .flatMap(s -> CameraRxWrapper.openCamera(mCameraParams.cameraId, mCameraManager))
            .share();

        Observable<CameraCaptureSessionParams> cameraCaptureSessionObservable = cameraDeviceObservable
            .filter(cameraDevice -> cameraDevice != null)
            .flatMap(cameraDevice -> CameraRxWrapper
                .createCaptureSession(cameraDevice, mImageReader, mSurfaceParams.previewSurface)
                .map(cameraCaptureSession -> new CameraCaptureSessionParams(cameraDevice, cameraCaptureSession))
            )
            .share();

        //todo change to single
        Observable<CaptureResultParams> previewObservable = cameraCaptureSessionObservable
            .filter(cameraCaptureSessionParams -> cameraCaptureSessionParams.cameraCaptureSession != null)
            .flatMap(cameraCaptureSessionParams -> startPreview(cameraCaptureSessionParams).first())
            .doOnNext(state -> mTextureView.setVisibility(View.VISIBLE))
            .share();

        mSubscriptions.add(Observable.combineLatest(previewObservable, mOnShutterClick, (captureResultParams, o) -> captureResultParams)
            .doOnNext(state -> mCallback.onFocusStarted())
            .flatMap(this::waitForAf)
            .flatMap(this::waitForAe)
            .doOnNext(state -> mCallback.onFocusFinished())
            .flatMap(captureResultParams -> captureStillPicture(captureResultParams.mCameraCaptureSessionParams))
            .subscribe(state -> {
            }, this::onError));

        mSubscriptions.add(Observable.combineLatest(previewObservable, mOnSwitchCamera.first(), (captureResultParams, o) -> captureResultParams)
            .first()
            .doOnNext(state -> mTextureView.setVisibility(View.INVISIBLE))
            .doOnNext(captureResultParams -> closeSession(captureResultParams.mCameraCaptureSessionParams.cameraCaptureSession))
            .flatMap(captureResultParams -> cameraCaptureSessionObservable
                .filter(cameraCaptureSessionParams -> cameraCaptureSessionParams.cameraCaptureSession == null)  //waiting for real close
            )
            .doOnNext(cameraCaptureSessionParams -> closeCamera(cameraCaptureSessionParams.cameraDevice))
            .flatMap(cameraCaptureSessionParams -> cameraDeviceObservable
                .filter(cameraDevice -> cameraDevice == null) //wait for real close
            )
            .doOnNext(cameraDevice -> closeImageReader())
            .subscribe(cameraDevice -> switchCameraInternal(), this::onError));

        mSubscriptions.add(Observable.combineLatest(previewObservable, mOnPauseSubject.first(), (state, o) -> state)
            .doOnNext(state -> mTextureView.setVisibility(View.INVISIBLE))
            .doOnNext(captureResultParams -> closeSession(captureResultParams.mCameraCaptureSessionParams.cameraCaptureSession))
            .flatMap(captureResultParams -> cameraCaptureSessionObservable
                .filter(cameraCaptureSessionParams -> cameraCaptureSessionParams.cameraCaptureSession == null)  //waiting for real close
            )
            .doOnNext(cameraCaptureSessionParams -> closeCamera(cameraCaptureSessionParams.cameraDevice))
            .flatMap(cameraCaptureSessionParams -> cameraDeviceObservable
                .filter(cameraDevice -> cameraDevice == null) //wait for real close
            )
            .doOnNext(cameraDevice -> closeImageReader())
            .subscribe(state -> unsubscribe(), this::onError));
    }

    private void onError(Throwable throwable) {
        unsubscribe();
        if (throwable instanceof CameraAccessException) {
            mCallback.onCameraAccessException();
        }
        else if (throwable instanceof OpenCameraException) {
            mCallback.onCameraOpenException(((OpenCameraException) throwable).getReason());
        }
        else {
            mCallback.onException(throwable);
        }
    }

    private void unsubscribe() {
        mSubscriptions.clear();
    }

    private void setupSurface(@NonNull SurfaceTexture surfaceTexture) {
        surfaceTexture.setDefaultBufferSize(mCameraParams.previewSize.getWidth(), mCameraParams.previewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);
        mSurfaceParams = new SurfaceParams(previewSurface);
    }

    private void switchCameraInternal() {
        Log.d(TAG, "\tswitchCameraInternal");
        try {
            unsubscribe();
            String cameraId = CameraStrategy.switchCamera(mCameraManager, mCameraParams.cameraId);
            mCameraParams = getCameraParams(cameraId);
            setTextureAspectRatio(mCameraParams);
            subscribe();
            // waiting for textureView to be measured
        }
        catch (CameraAccessException e) {
            onError(e);
        }
    }

    private void initImageReader() {
        Log.d(TAG, "\tinitImageReader");
        Size sizeForImageReader = CameraStrategy.getStillImageSize(mCameraParams.cameraCharacteristics, mCameraParams.previewSize);
        mImageReader = ImageReader.newInstance(sizeForImageReader.getWidth(), sizeForImageReader.getHeight(), ImageFormat.JPEG, 1);
        mSubscriptions.add(ImageSaverRxWrapper.createOnImageAvailableObservable(mImageReader)
            .observeOn(Schedulers.io())
            .flatMap(imageReader1 -> ImageSaverRxWrapper.save(imageReader1.acquireLatestImage(), mFile).toObservable())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(file -> mCallback.onPhotoTaken(file.getAbsolutePath(), getLensFacingPhotoType())));
    }

    @Nullable
    private Integer getLensFacingPhotoType() {
        return mCameraParams.cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
    }

    @NonNull
    private Observable<CaptureResultParams> startPreview(@NonNull CameraCaptureSessionParams cameraCaptureSessionParams) {
        Log.d(TAG, "\tstartPreview");
        try {
            CaptureRequest.Builder previewBuilder = createPreviewBuilder(cameraCaptureSessionParams.cameraCaptureSession, mSurfaceParams.previewSurface);
            return CameraRxWrapper
                .fromSetRepeatingRequest(cameraCaptureSessionParams.cameraCaptureSession, previewBuilder.build())
                .map(captureResult -> new CaptureResultParams(cameraCaptureSessionParams, captureResult));
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    private Observable<CaptureResultParams> waitForAf(@NonNull CaptureResultParams captureResultParams) {
        try {
            CaptureRequest.Builder previewBuilder = createPreviewBuilder(captureResultParams.mCameraCaptureSessionParams.cameraCaptureSession, mSurfaceParams.previewSurface);
            return mAutoFocusConvergeWaiter
                .waitForConverge(captureResultParams, previewBuilder)
                .toObservable();
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private Observable<CaptureResultParams> waitForAe(@NonNull CaptureResultParams captureResultParams) {
        try {
            CaptureRequest.Builder previewBuilder = createPreviewBuilder(captureResultParams.mCameraCaptureSessionParams.cameraCaptureSession, mSurfaceParams.previewSurface);
            return mAutoExposureConvergeWaiter
                .waitForConverge(captureResultParams, previewBuilder)
                .toObservable();
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private Observable<CaptureResultParams> captureStillPicture(@NonNull CameraCaptureSessionParams cameraCaptureSessionParams) {
        Log.d(TAG, "\tcaptureStillPicture");
        try {
            final CaptureRequest.Builder builder = createStillPictureBuilder(cameraCaptureSessionParams.cameraDevice);
            return CameraRxWrapper.fromCapture(cameraCaptureSessionParams.cameraCaptureSession, builder.build())
                .map(result -> new CaptureResultParams(cameraCaptureSessionParams, result));
        }
        catch (CameraAccessException e) {
            return Observable.error(e);
        }
    }

    @NonNull
    private CaptureRequest.Builder createStillPictureBuilder(@NonNull CameraDevice cameraDevice) throws CameraAccessException {
        final CaptureRequest.Builder builder;
        builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        builder.addTarget(mImageReader.getSurface());
        setup3Auto(builder);

        int rotation = mWindowManager.getDefaultDisplay().getRotation();
        builder.set(CaptureRequest.JPEG_ORIENTATION, CameraOrientationHelper.getJpegOrientation(mCameraParams.cameraCharacteristics, rotation));
        return builder;
    }

    @NonNull
    CaptureRequest.Builder createPreviewBuilder(CameraCaptureSession captureSession, Surface previewSurface) throws CameraAccessException {
        CaptureRequest.Builder builder = captureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(previewSurface);
        setup3Auto(builder);
        return builder;
    }

    private void setup3Auto(CaptureRequest.Builder builder) {
        // Enable auto-magical 3A run by camera device
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist = mCameraParams.cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        boolean noAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!noAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            int[] afModes = mCameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        int[] aeModes = mCameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (contains(aeModes, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
        else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        }

        // If there is an auto-magical white balance control mode available, use it.
        int[] awbModes = mCameraParams.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (contains(awbModes, CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    private void closeSession(@Nullable CameraCaptureSession cameraCaptureSession) {
        Log.d(TAG, "\tcloseSession");
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
        }
    }

    private void closeCamera(@Nullable CameraDevice cameraDevice) {
        Log.d(TAG, "\tcloseCamera");
        if (cameraDevice != null) {
            cameraDevice.close();
        }
    }

    private void closeImageReader() {
        Log.d(TAG, "\tcloseImageReader");
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    public interface Callback {
        void onFocusStarted();

        void onFocusFinished();

        void onPhotoTaken(@NonNull String photoUrl, @Nullable Integer photoSourceType);

        void onCameraAccessException();

        void onCameraOpenException(@Nullable OpenCameraException.Reason reason);

        void onException(Throwable throwable);
    }

}
