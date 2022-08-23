package com.example.main_evstek_activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.evs_network_library.evs_network_library;
import com.example.evs_ocr_model.OcrResultModel;
import com.example.evs_ocr_model.Predictor;
import com.example.evs_ocr_model.evs_ocr_model;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class ScanEvstekActivity extends AppCompatActivity implements ImageAnalysis.Analyzer{
    public ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private PreviewView previewView;
    private ImageAnalysis imageAnalysis;
    private SurfaceView surfaceView;
    private SurfaceView bboxView;
    private SurfaceHolder holder;
    private SurfaceHolder holderBbox;
    private Bitmap croppedImage;
    evs_network_library evsNetwork;
    evs_ocr_model ocr_model = new evs_ocr_model();
    Canvas canvas;
    //************** Will be read from setting.json
    int  xOffset, yOffset, boxWidth, boxHeight;
    Double COVERAGE_PERCENT = 0.05;
    private boolean pauseServer = false;
    private String rcpCallResult;
    //**************************



    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        ocr_model.init_evs_model(this.getApplicationContext());
        setContentView(R.layout.activity_evstek);
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        checkCameraPermissions(this);
        String configString = loadJSONFromAsset();
        if (configString!= null){
            try {
                JSONObject configJSON = parseJSON(configString);
                initRPC(configJSON);
            } catch (JSONException e) {
                e.printStackTrace();
            }

        }


        previewView = findViewById(R.id.evsPreviewView);
        surfaceView = findViewById(R.id.overlay);
        bboxView = findViewById(R.id.bboxSurface);

        surfaceView = findViewById(R.id.overlay);
        surfaceView.setZOrderOnTop(true);
        holder = surfaceView.getHolder();
        holder.setFormat(PixelFormat.TRANSPARENT);


        bboxView = (SurfaceView)findViewById(R.id.bboxSurface);
        bboxView.setZOrderOnTop(true);
        holderBbox = bboxView.getHolder();
        holderBbox.setFormat(PixelFormat.TRANSPARENT);
        holderBbox.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);


        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {

            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                startCameraX(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future
                // This should never be reached
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

    }

    public void startCameraX(ProcessCameraProvider cameraProvider){
        cameraProvider.unbindAll();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this);

        DrawFocusRect(Color.parseColor("#FFFFFF"));
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

    }
    public static void checkCameraPermissions(Context context){
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
        {
            // Permission is not granted
            Log.d("checkCameraPermissions", "No Camera Permissions");
            ActivityCompat.requestPermissions((Activity) context,
                    new String[] { Manifest.permission.CAMERA },
                    100);
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {

        final Bitmap bitmap = previewView.getBitmap();
        if(bitmap == null)
            return;


        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //Bitmap croppedImage = cropImage(bitmap);
                boolean isloaded = ocr_model.predictor.isLoaded();
                if (!isloaded){
                    ocr_model.loadModel();
                }
                croppedImage = cropImage(bitmap);
                ocr_model.predictor.setInputImage(croppedImage);
                boolean result = ocr_model.predictor.runModel();
                if (result){

                    DrawBboxs(bitmap);
                    int charNum = countCharacterOnImage();
                    boolean isCover = calculateBboxRatio();
                    if (charNum >0){
                        DrawFocusRect(Color.parseColor("#3B85F5"));
                        if (isCover && (!pauseServer)){
                            pauseServer = true;
                            Thread RPCThread = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    String serverResult = EVSNetworkCall(croppedImage);
                                    Log.i("ScanEvstekActivity/serverResult",serverResult);
//                                    if (!(serverResult.matches("BADROI")))
//                                    {
//
//                                    }
                                }
                            });
                            RPCThread.start();

                        }
                    }
                    else{
                        DrawFocusRect(Color.parseColor("#FFFFFF"));
                        //ocrView.setImageBitmap(bitmap);
                    }

                }else{
                    DrawFocusRect(Color.parseColor("#FFFFFF"));
                    //ocrView.setImageBitmap(bitmap);
                }


            }
        });
        image.close();

    }
    private Bitmap cropImage(Bitmap originalImage){
        Bitmap croppedBmp = Bitmap.createBitmap(originalImage, xOffset, yOffset, boxWidth, boxHeight);
        return croppedBmp;
    }

    private void DrawFocusRect(int color) {
        DisplayMetrics displaymetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
        int height = previewView.getHeight();
        int width = previewView.getWidth();

        //cameraHeight = height;
        //cameraWidth = width;

        int left, right, top, bottom, diameter;

        diameter = width;
        if (height < width) {
            diameter = height;
        }

        int offset = (int) (0.05 * diameter);
        diameter -= offset;

        canvas = holder.lockCanvas();
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        //border's properties
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(5);

        left = width / 2 - diameter / 8;
        top = height / 2 - diameter / 6;
        right = width / 2 + diameter / 10;
        bottom = height / 2 + diameter / 50;

        xOffset = left;
        yOffset = top;
        boxHeight = bottom - top;
        boxWidth = right - left;
        //Changing the value of x in diameter/x will change the size of the box ; inversely proportionate to x
        canvas.drawRect(left, top, right, bottom, paint);
        holder.unlockCanvasAndPost(canvas);
    }

    private boolean calculateBboxRatio(){
        ArrayList<OcrResultModel> results_final;
        results_final = ocr_model.predictor.get_final_results();
        int focus_area = (boxWidth) * (boxHeight);
        double total_iou = 0;
        int total_area = 0;
        for (OcrResultModel result : results_final) {
            int xMin = 0, xMax = 0, yMin = 0, yMax = 0;
            List<Point> points = result.getPoints();
            for (int i = points.size() - 1; i >= 0; i--) {
                Point p = points.get(i);
                if (xMin > p.x){
                    xMin = p.x;
                }
                else if(xMax < p.x){
                    xMax = p.x;
                }

                if (yMin > p.y){
                    yMin = p.y;
                }
                else if(yMax < p.y){
                    yMax = p.y;
                }
            }
            total_area += (xMax - xMin) * (yMax - yMin);
            if ((total_area / focus_area) >= COVERAGE_PERCENT){ // %50 den fazla alan kapliyor mu ?
                //total_iou += calculateIOU(xMin, xMax, yMin, yMax);
                //if (total_iou >= COVERAGE_PERCENT){
                return true;
            }
        }
        return false;

    }

    private void DrawBboxs(Bitmap input_image){

        ArrayList<OcrResultModel> results_final;
        results_final = ocr_model.predictor.get_final_results();

        if (results_final != null){
            //Canvas canvas = new Canvas(input_image);
            Canvas canvas = holderBbox.lockCanvas();
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);


            Paint paintFillAlpha = new Paint();
            paintFillAlpha.setStyle(Paint.Style.FILL);
            paintFillAlpha.setColor(Color.parseColor("#3B85F5"));
            paintFillAlpha.setAlpha(50);

            Paint paint = new Paint();
            paint.setColor(Color.parseColor("#3B85F5"));
            paint.setStrokeWidth(5);
            paint.setStyle(Paint.Style.STROKE);

            for (OcrResultModel result : results_final) {
                Path path = new Path();
                List<Point> points = result.getPoints();
                path.moveTo(points.get(0).x + xOffset, points.get(0).y + yOffset);
                for (int i = points.size() - 1; i >= 0; i--) {
                    Point p = points.get(i);
                    path.lineTo(p.x + xOffset, p.y + yOffset);
                }
                canvas.drawPath(path, paint);
                canvas.drawPath(path, paintFillAlpha);
            }
            holderBbox.unlockCanvasAndPost(canvas);
        }
    }

    private int countCharacterOnImage(){
        ArrayList<OcrResultModel> results_final;
        results_final = ocr_model.predictor.get_final_results();

        return results_final.size();
    }

    public void initRPC(JSONObject configJSSON){
        String [] serverConnectionParams = new String[5];
        try {
            serverConnectionParams[0] = configJSSON.getJSONObject("serverConnection").getString("EVSNetworkhostnName");
            serverConnectionParams[1] = configJSSON.getJSONObject("serverConnection").getString("EVSNetworkUserName");
            serverConnectionParams[2] = configJSSON.getJSONObject("serverConnection").getString("EVSNetworkPass");
            serverConnectionParams[3] = configJSSON.getJSONObject("serverConnection").getString("EVSNetworkQueue");
            serverConnectionParams[4] = configJSSON.getJSONObject("serverConnection").getString("ResultQueue");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        evsNetwork = new evs_network_library(serverConnectionParams);
    }

    private JSONObject parseJSON(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        return obj;
    }

    private String loadJSONFromAsset() {
        String json = new String();
        try {
            InputStream is = this.getAssets().open("evs_ocr_config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }

    public String EVSNetworkCall(Bitmap image)
    {   String result = new String();

        result = evsNetwork.postDataUsingRPC(image);
        pauseServer = false;
        return result;

    }

}
