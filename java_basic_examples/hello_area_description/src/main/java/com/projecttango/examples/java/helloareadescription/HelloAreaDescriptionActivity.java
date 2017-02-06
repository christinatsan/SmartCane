/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.examples.java.helloareadescription;

import android.app.Activity;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.TangoAreaDescriptionMetaData;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

import static java.lang.String.valueOf;

/**
 * Main Activity class for the Area Description example. Handles the connection to the Tango service
 * and propagation of Tango pose data to Layout view.
 */
public class HelloAreaDescriptionActivity extends Activity implements
        SetAdfNameDialog.CallbackListener,
        SaveAdfTask.SaveAdfListener {

    private static final String TAG = HelloAreaDescriptionActivity.class.getSimpleName();
    private static final int SECS_TO_MILLISECS = 1000;
    private Tango mTango;
    private TangoConfig mConfig;
    private TextView mUuidTextView;
    private TextView mRelocalizationTextView;
    private TextView mCurrentLocationTextView;
    private TextView mDestinationTextView;
    private TextView mReachedDestinationTextView;
    private TextView mFileContentView;
    private TextView mStringx;
    private TextView mStringy;
    private TextView mStringz;

    private Button mSaveAdfButton;
    private Button mSaveLandButton;
    private EditText mLandmarkName;

    private float translation[];

    private boolean mSaveLand;
    private ArrayList<TangoPoseData> landmarkList = new ArrayList<TangoPoseData>();
    private ArrayList<String> landmarkName = new ArrayList<String>();
    private ArrayList<String> adfName = new ArrayList<String>();
    private TangoPoseData currentPose;

    private float[] arrayLands;
    private int countLands = 0;

    private double mPreviousPoseTimeStamp;
    private double mTimeToNextUpdate = UPDATE_INTERVAL_MS;

    private boolean mIsRelocalized;
    private boolean mIsLearningMode;
    private boolean mIsConstantSpaceRelocalize;

    private String mPositionString;
    private float[] mDestinationTranslation = {(float)2, (float)0, (float)0};

    // Long-running task to save the ADF.
    private SaveAdfTask mSaveAdfTask;

    private static final double UPDATE_INTERVAL_MS = 100.0;

    private final Object mSharedLock = new Object();

    private String landmarksStored;
    private float xPose = 0.0f;
    private float yPose = 0.0f;
    private float zPose = 0.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_learning);
        Intent intent = getIntent();
        mIsLearningMode = intent.getBooleanExtra(StartActivity.USE_AREA_LEARNING, false);
        mIsConstantSpaceRelocalize = intent.getBooleanExtra(StartActivity.LOAD_ADF, false);

       // arrayLands = new float[20];
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Initialize Tango Service as a normal Android Service, since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time when onResume gets called, we
        // should create a new Tango object.
        mTango = new Tango(HelloAreaDescriptionActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready, this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there is no UI
            // thread changes involved.
            @Override
            public void run() {
                synchronized (HelloAreaDescriptionActivity.this) {
                    try {
                        mConfig = setTangoConfig(
                                mTango, mIsLearningMode, mIsConstantSpaceRelocalize);
                        mTango.connect(mConfig);
                        startupTango();
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.tango_out_of_date_exception), e);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.tango_error), e);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.tango_invalid), e);
                    } catch (SecurityException e) {
                        // Area Learning permissions are required. If they are not available,
                        // SecurityException is thrown.
                        Log.e(TAG, getString(R.string.no_permissions), e);
                    }
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (HelloAreaDescriptionActivity.this) {
                            setupTextViewsAndButtons(mTango, mIsLearningMode,
                                    mIsConstantSpaceRelocalize);
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Clear the relocalization state: we don't know where the device will be since our app
        // will be paused.
        mIsRelocalized = false;
        synchronized (this) {
            try {
                mTango.disconnect();
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.tango_error), e);
            }
        }
    }

    /**
     * Sets Texts views to display statistics of Poses being received. This also sets the buttons
     * used in the UI. Please note that this needs to be called after TangoService and Config
     * objects are initialized since we use them for the SDK related stuff like version number
     * etc.
     */
    private void setupTextViewsAndButtons(Tango tango, boolean isLearningMode, boolean isLoadAdf) {
        mSaveAdfButton = (Button) findViewById(R.id.save_adf_button);
        mUuidTextView = (TextView) findViewById(R.id.adf_uuid_textview);
        mRelocalizationTextView = (TextView) findViewById(R.id.relocalization_textview);
        mDestinationTextView = (TextView) findViewById(R.id.destination_textview);
        mCurrentLocationTextView = (TextView) findViewById(R.id.current_location_textview);
        mReachedDestinationTextView = (TextView) findViewById(R.id.reached_destination_textview);
        mSaveLandButton = (Button) findViewById(R.id.land_button);
        mLandmarkName = (EditText) findViewById(R.id.landmarkName);
        mFileContentView = (TextView) findViewById(R.id.fileString);
        mStringx = (TextView) findViewById(R.id.xString);
        mStringy = (TextView) findViewById(R.id.yString);
        mStringz = (TextView) findViewById(R.id.zString);

        mSaveLandButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                landmarkList.add(currentPose);
                Log.i("landmarkList.len =  ", valueOf(landmarkList.size()));

               // for (TangoPoseData t : landmarkList) {
                //    Log.i("t = ", t.toString());
               // }

              //  Log.d("Size of landmarks")

                Context context = getApplicationContext();
                CharSequence text = "Landmark saved";
                int duration = Toast.LENGTH_SHORT;

                Toast toast = Toast.makeText(context, text, duration);
                toast.show();

                String name = mLandmarkName.getText().toString();
                landmarkName.add(name);
            }
        });

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("X:" + mDestinationTranslation[0] + ", Y:" + mDestinationTranslation[1] + ", Z:" + mDestinationTranslation[2]);

        mDestinationTextView.setText(stringBuilder.toString());

        if(isLoadAdf) {
            if (isLearningMode) {
                // Disable save ADF button until Tango relocalizes to the current ADF.
                mSaveAdfButton.setEnabled(false);
            } else {
                // Hide to save ADF button if leanring mode is off.
                mSaveAdfButton.setVisibility(View.GONE);
            }
        }


            if (isLoadAdf) {
                ArrayList<String> fullUuidList;
                // Returns a list of ADFs with their UUIDs
                fullUuidList = tango.listAreaDescriptions();
                if (fullUuidList.size() == 0) {
                    mUuidTextView.setText(R.string.no_uuid);
                } else {
                    mUuidTextView.setText(getString(R.string.number_of_adfs) + fullUuidList.size()
                            + getString(R.string.latest_adf_is)
                            + fullUuidList.get(fullUuidList.size() - 1));
                }
            }
        }


    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setTangoConfig(Tango tango, boolean isLearningMode, boolean isLoadAdf) {
        // Use default configuration for Tango Service.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // Check if learning mode
        if (isLearningMode) {
            // Set learning mode to config.
            config.putBoolean(TangoConfig.KEY_BOOLEAN_LEARNINGMODE, true);

        }
        // Check for Load ADF/Constant Space relocalization mode.
        if (isLoadAdf) {
            ArrayList<String> fullUuidList;
            // Returns a list of ADFs with their UUIDs.
            fullUuidList = tango.listAreaDescriptions();
            // Load the latest ADF if ADFs are found.
            if (fullUuidList.size() > 0) {
                config.putString(TangoConfig.KEY_STRING_AREADESCRIPTION,
                        fullUuidList.get(fullUuidList.size() - 1));
            }
        }
        return config;
    }

    /**
     * Set up the callback listeners for the Tango service and obtain other parameters required
     * after Tango connection.
     */
    private void startupTango() {
        // Set Tango Listeners for Poses Device wrt Start of Service, Device wrt
        // ADF and Start of Service wrt ADF.
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                TangoPoseData.COORDINATE_FRAME_DEVICE));
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE));

        mTango.connectListener(framePairs, new OnTangoUpdateListener() {

            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                // Make sure to have atomic access to Tango Data so that UI loop doesn't interfere
                // while Pose call back is updating the data.
                synchronized (mSharedLock) {


                    //Log.i ("pose is called", String.valueOf(pose));
                    currentPose = pose;

                    //Log.i("pose =",valueOf(currentPose));
                    // Check for Device wrt ADF pose, Device wrt Start of Service pose, Start of
                    // Service wrt ADF pose (This pose determines if the device is relocalized or
                    // not).
                    if (pose.baseFrame == TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION
                            && pose.targetFrame == TangoPoseData
                            .COORDINATE_FRAME_DEVICE) {
                        if (pose.statusCode == TangoPoseData.POSE_VALID) {
                            mIsRelocalized = true;

                            Log.i("mIsRelocalized = ", valueOf(mIsRelocalized));

                            StringBuilder stringBuilder = new StringBuilder();

                            translation = pose.getTranslationAsFloats();
                            stringBuilder.append("X:" + translation[0] + ", Y:" + translation[1] + ", Z:" + translation[2]);
                            mPositionString = stringBuilder.toString();

                            // Load saved landmarks and

                            ArrayList<String> fullUuidList;
                            // Returns a list of ADFs with their UUIDs
                            fullUuidList = mTango.listAreaDescriptions();
                            if(fullUuidList.size() > 0) {

                                String adfFileName = fullUuidList.get(fullUuidList.size() - 1);

                                landmarksStored = "empty file";

                                landmarksStored = readFile(adfFileName);

                                Log.d("landmarksStored", landmarksStored);

                                // String from file to json and then set values of translation

                                /*float xPose = 0.0f;
                                float yPose = 0.0f;
                                float zPose = 0.0f;*/

                                // Get pose from first landmark saved (for now)


                                //String lastLandmark = landmarkName.get(landmarkName.size()-1);
                                StringBuilder xNameBuilder = new StringBuilder();
                                StringBuilder yNameBuilder = new StringBuilder();
                                StringBuilder zNameBuilder = new StringBuilder();

                                xNameBuilder.append(landmarkName.get(0) + "_x");
                                yNameBuilder.append(landmarkName.get(0) + "_y");
                                zNameBuilder.append(landmarkName.get(0) + "_z");

                                String xName = xNameBuilder.toString();
                                String yName = yNameBuilder.toString();
                                String zName = zNameBuilder.toString();

                                try {
                                    JSONObject JSONlandmarks = new JSONObject(landmarksStored);
                                    xPose = Float.valueOf(JSONlandmarks.getString(xName));
                                    yPose = Float.valueOf(JSONlandmarks.getString(yName));
                                    zPose = Float.valueOf(JSONlandmarks.getString(zName));


                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }

                        } else {
                            mIsRelocalized = false;
                        }
                    }
                }

                // get current pose


                final double deltaTime = (pose.timestamp - mPreviousPoseTimeStamp) *
                        SECS_TO_MILLISECS;
                mPreviousPoseTimeStamp = pose.timestamp;
                mTimeToNextUpdate -= deltaTime;


                if (mTimeToNextUpdate < 0.0) {
                    mTimeToNextUpdate = UPDATE_INTERVAL_MS;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (mSharedLock) {
                                mSaveAdfButton.setEnabled(mIsRelocalized);
                                mRelocalizationTextView.setText(mIsRelocalized ?
                                        getString(R.string.localized) :
                                        getString(R.string.not_localized));

                                if (mIsRelocalized) {

                                    mFileContentView.setText(landmarksStored);

                                    mCurrentLocationTextView.setText(mPositionString);
                                    mStringx.setText(String.valueOf(xPose));
                                    mStringy.setText(String.valueOf(yPose));
                                    mStringz.setText(String.valueOf(zPose));


                                    mReachedDestinationTextView.setText(valueOf(((int) translation[0] == (int) mDestinationTranslation[0]) &&
                                            ((int) translation[1] == (int) mDestinationTranslation[1]) &&
                                            ((int) translation[2] == (int) mDestinationTranslation[2])));

                                }
                            }
                        }
                    });
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(TangoPointCloudData xyzij) {
                // We are not using onPointCloudAvailable for this app.
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    private String readFile(String adfId){

        StringBuilder finalString = new StringBuilder();

        try {

           // StringBuilder fileName = new StringBuilder();
           // fileName.append(adfId + ".txt");

           // String name = fileName.toString();

            FileInputStream inStream = this.openFileInput(adfId);
            InputStreamReader inputStreamReader = new InputStreamReader(inStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String oneLine;

            while ((oneLine = bufferedReader.readLine()) != null) {
                finalString.append(oneLine);
            }

            bufferedReader.close();
            inStream.close();
            inputStreamReader.close();

        } catch (IOException e){
            e.printStackTrace();


        }

        String landmarkString = finalString.toString();

        return landmarkString;
    }

    private void saveLandmarks(String id){

        // Check if this is called

        Log.d("Checking","save Landmarks to file is called");

        int size = landmarkList.size();

        JSONObject jsonObj = new JSONObject();

       // for(int i=0; i<size; i++){
            StringBuilder xNameBuilder = new StringBuilder();
            StringBuilder yNameBuilder = new StringBuilder();
            StringBuilder zNameBuilder = new StringBuilder();

            xNameBuilder.append(landmarkName.get(0) + "_x");
            yNameBuilder.append(landmarkName.get(0) + "_y");
            zNameBuilder.append(landmarkName.get(0) + "_z");

            String xName = xNameBuilder.toString();
            String yName = yNameBuilder.toString();
            String zName = zNameBuilder.toString();

            float translationStored[] = landmarkList.get(0).getTranslationAsFloats();
            String xPose = Float.toString(translationStored[0]);
            String yPose = Float.toString(translationStored[1]);
            String zPose = Float.toString(translationStored[2]);

            try {
                jsonObj.put(xName,xPose);
                jsonObj.put(yName,yPose);
                jsonObj.put(zName,zPose);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        //}

        // Create a file in the Internal Storage
        String fileName = id; //name file with uuid
        String content = jsonObj.toString();
        Log.d("content", content);
        Log.d("filename", fileName);

        FileOutputStream outputStream = null;
        try {
            outputStream = openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(content.getBytes());
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Log.d("checking ", "success stored landmarks");


    }

    /**
     * Implements SetAdfNameDialog.CallbackListener.
     */
    @Override
    public void onAdfNameOk(String name, String uuid) {
        saveAdf(name);
    }

    /**
     * Implements SetAdfNameDialog.CallbackListener.
     */
    @Override
    public void onAdfNameCancelled() {
        // Continue running.
    }

    /**
     * The "Save ADF" button has been clicked.
     * Defined in {@code activity_area_description.xml}
     */
    public void saveAdfClicked(View view) {
        showSetAdfNameDialog();
    }

    /**
     * Save the current Area Description File.
     * Performs saving on a background thread and displays a progress dialog.
     */
    private void saveAdf(String adfName) {
        mSaveAdfTask = new SaveAdfTask(this, this, mTango, adfName);
        mSaveAdfTask.execute();
    }

    /**
     * Handles failed save from mSaveAdfTask.
     */
    @Override
    public void onSaveAdfFailed(String adfName) {
        String toastMessage = String.format(
                getResources().getString(R.string.save_adf_failed_toast_format),
                adfName);
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        mSaveAdfTask = null;
    }

    /**
     * Handles successful save from mSaveAdfTask.
     */
    @Override
    public void onSaveAdfSuccess(String adfName, String adfUuid) {
        String toastMessage = String.format(
                getResources().getString(R.string.save_adf_success_toast_format),
                adfName, adfUuid);
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        mSaveAdfTask = null;


        saveLandmarks(adfUuid);
        finish();
    }

    /**
     * Shows a dialog for setting the ADF name.
     */
    private void showSetAdfNameDialog() {
        Bundle bundle = new Bundle();
        bundle.putString(TangoAreaDescriptionMetaData.KEY_NAME, "New ADF");
        // UUID is generated after the ADF is saved.
        bundle.putString(TangoAreaDescriptionMetaData.KEY_UUID, "");

        FragmentManager manager = getFragmentManager();
        SetAdfNameDialog setAdfNameDialog = new SetAdfNameDialog();
        setAdfNameDialog.setArguments(bundle);
        setAdfNameDialog.show(manager, "ADFNameDialog");
    }
}
