package com.kodeholic.simpletest;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import java.util.ArrayList;
import java.util.List;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public static final String TAG = MainActivity.class.getSimpleName();

    public static final int SAMPLE_RATE_IN_HZ = 44100;
    public static final int CHANNEL_CONFIG    = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT      = AudioFormat.ENCODING_PCM_16BIT;

    private Context mContext;
    private AudioRecord mAudioRecord;
    private MediaPlayer mPlayer;
    private SimpleMuxer mSMx;

    private Button  bt_record;
    private Button  bt_play;

    private String mLastRecordFile = null;
    private int mTargetSdkVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate()");
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        mContext  = this;

        bt_record = (Button)findViewById(R.id.bt_record);
        bt_play   = (Button)findViewById(R.id.bt_play);

        bt_record.setOnClickListener(this);
        bt_play  .setOnClickListener(this);

        /*** 권한 체크 *******************/
        mTargetSdkVersion = Build.VERSION_CODES.M;
        try {
            final PackageInfo info = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
            mTargetSdkVersion = info.applicationInfo.targetSdkVersion;
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkAllPermissionsGranted(DANGEROUS_PERMISSIONS, "onCreate")) {
            requestPermissions(this, 100);
            return;
        }

        updateView();
    }

    public static final String[] DANGEROUS_PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
    };

    @TargetApi(Build.VERSION_CODES.M)
    public void requestPermissions(Activity activity, int reqCode) {
        List<String> deniedList = new ArrayList<String>();
        for (String permission : DANGEROUS_PERMISSIONS) {
            if (!isPermissionGranted(permission)) {
                deniedList.add(permission);
            }
        }

        requestPermissions(deniedList.toArray(new String[deniedList.size()]), reqCode);
    }
    private boolean checkAllPermissionsGranted() {
        return false;
    }

    public boolean checkAllPermissionsGranted(String[] permissions, String f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String permission : permissions) {
                if (!isPermissionGranted(permission)) {
                    Log.d(TAG, "checkAllPermissionsGranted() - f: " + f + ", result: " + false);
                    return false;
                }
            }
        }
        Log.d(TAG, "checkAllPermissionsGranted() - f: " + f + ", result: " + true);

        return true;
    }

    public boolean isPermissionGranted(String permission) {
        boolean result = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (mTargetSdkVersion >= Build.VERSION_CODES.M) {
                result = mContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
                Log.d(TAG, "isPermissionGranted(1) - mTargetSdkVersion: " + mTargetSdkVersion + ", " + permission + ": " + result);
            }
            else {
                result = PermissionChecker.checkSelfPermission(mContext, permission) == PermissionChecker.PERMISSION_GRANTED;
                Log.d(TAG, "isPermissionGranted(2) - mTargetSdkVersion: " + mTargetSdkVersion + ", " + permission + ": " + result);
            }
        }

        return result;
    }

    private void updateView() {
        Log.d(TAG, "updateView()");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //녹음 상태
                if (mSMx == null) {
                    bt_record.setText("Record Start");
                }
                else {
                    bt_record.setText("Record Stop");
                }

                //재생 상태
                if (mPlayer == null) {
                    bt_play.setText("Play Start");
                }
                else {
                    bt_play.setText("Play Stop");
                }
            }
        });
    }

    @Override
    public void onClick(View v) {
        Log.d(TAG, "onClick() - v: " + v);

        switch (v.getId()) {
            case R.id.bt_record:
                if (mSMx == null) {
                    startRecordThread();
                }
                else {
                    stopRecordThread();
                }
                break;

            case R.id.bt_play:
                if (mPlayer == null) {
                    startPlayer();
                }
                else {
                    stopPlayer();
                }
                break;
        }
    }

    private void startRecordThread() {
        mRunning = true;
        new Thread(mRecordWorker).start();
    }

    private void stopRecordThread() {
        mRunning = false;
    }

    private boolean mRunning = false;
    private Runnable mRecordWorker = new Runnable() {
        @Override
        public void run() {
            startRecordWav();

            byte[] bytes = new byte[mBufferSize];
            while (mRunning) {
                int n = mAudioRecord.read(bytes, 0, mBufferSize);
                if (AudioRecord.ERROR_INVALID_OPERATION == n) {
                    Log.e(TAG, "mRecordWorker... read() failed!");
                    break;
                }
                try {
                    long pTimeUs = SimpleEncoder.getPTimeUs(mPrevPTimeUs);
                    mSMx.writeSample(SimpleEncoder.ENCODER_TYPE_AUDIO, bytes, 0, n, pTimeUs);
                    mPrevPTimeUs = pTimeUs;
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

            stopRecordWav();
        }
    };

    private int mBufferSize = 0;
    private void startRecordWav() {
        try {
            showToast("start record!!");

            //Recorder
            mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_IN_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    mBufferSize);
            mAudioRecord.startRecording();

            //Muxer
//            mLastRecordFile = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/" + "W_" + System.currentTimeMillis() + ".wav";
//            mSMx = new SimpleWAVMuxer(mContext, mLastRecordFile);
            mLastRecordFile = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/" + "W_" + System.currentTimeMillis() + ".m4a";
            mSMx = new SimpleM4AMuxer(mContext, mLastRecordFile);
            mSMx.addAudioTrack(new SimpleMuxer.AudioConfig(
                    SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, 1
            ));
            mSMx.start();
            mRunning = true;

            //화면을 갱신한다.
            updateView();
            return;
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        //failed!
        showToast("start record!! failed!!!");
        stopRecordWav();

        return;
    }

    private void stopRecordWav() {
        showToast("stop record!!");

        //마이크 중지
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }

        //Muxer 중지
        final SimpleMuxer smx = mSMx;
        if (smx != null && !smx.isStopped()) {
            smx.stop(new SimpleMuxer.StopListener() {
                @Override
                public void onStop() {
                    mSMx = null;

                    //화면을 갱신한다.
                    updateView();
                }
            });
        }

        mRunning = false;

        //화면을 갱신한다.
        updateView();
    }

    private void startPlayer() {
        Log.d(TAG, "startPlayer()");
        try {
            if (mPlayer != null) {
                showToast("Already playing! stop first!");
                return;
            }
            if (mLastRecordFile == null) {
                showToast("No record file found!");
                return;
            }
            mPlayer = new MediaPlayer();
            mPlayer.setDataSource(mLastRecordFile);
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.prepare();
            mPlayer.start();
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlayer();
                }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    stopPlayer();
                    return false;
                }
            });
            showToast("startPlayer() - " + mLastRecordFile);

            //화면을 갱신한다.
            updateView();
            return;
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        stopPlayer();
    }

    private void stopPlayer() {
        Log.d(TAG, "stopPlayer()");
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;

            showToast("stopPlayer() - " + mLastRecordFile);
        }

        //화면을 갱신한다.
        updateView();
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private long mPrevPTimeUs = 0;
}
