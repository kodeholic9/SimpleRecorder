package com.kodeholic.simpletest;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class SimpleM4AMuxer extends SimpleMuxer {
    public static final String MIME_TYPE = "audio/mp4a-latm";
    public static final int    BIT_RATE  = 64000;

    //waiting to stop..
    private final String  __STOP_WAITER__ = "W";

    private MediaMuxer    mMx;
    private int           mTrackIndex;
    private SimpleEncoder mAudioEncoder;

    public SimpleM4AMuxer(Context context, String path) throws IOException {
        super(context, path, SimpleM4AMuxer.class.getSimpleName());
        mMx = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mTrackIndex = -1;
    }

    @Override
    public void addAudioTrack(AudioConfig audioConfig) throws Exception {
        Log.d(TAG, "addAudioTrack(enter) - audioConfig: " + audioConfig);

        if (mStatus != ST_INIT) {
            throw new IllegalStateException("Invalid mStatus: " + valueOfStatus(mStatus));
        }
        updateStatus(ST_PREPARED);

        //파라미터를 설정한다.
        this.audioConfig.sampleRateInHz = audioConfig.sampleRateInHz; //format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        this.audioConfig.channelCount   = audioConfig.channelCount;   //format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        this.audioConfig.channelConfig  = audioConfig.channelConfig;  //AudioFormat.CHANNEL_IN_MONO;
        this.audioConfig.audioFormat    = audioConfig.audioFormat;    //AudioFormat.ENCODING_PCM_16BIT;

        //Encoder를 생성한다.
        final MediaFormat audioFormat = MediaFormat.createAudioFormat(
                MIME_TYPE,
                audioConfig.sampleRateInHz,
                audioConfig.channelCount);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE  , MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK , audioConfig.channelConfig);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE     , BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioConfig.channelCount);
        mAudioEncoder = new SimpleEncoder(mContext, SimpleEncoder.ENCODER_TYPE_AUDIO, MIME_TYPE, audioFormat);

        Log.d(TAG, "addAudioTrack(leave)");

        return;
    }

    @Override
    public void addVideoTrack(VideoConfig videoConfig) throws Exception { ; }

    private void release() {
        Log.d(TAG, "release(enter)");

        ///////////////////////////////////////////////////
        // Muxer stop
        //////////////////////////////////////////////////
        try {
            mMx.stop();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        ///////////////////////////////////////////////////
        // Muxer release
        //////////////////////////////////////////////////
        try {
            mMx.release();
            mMx = null;
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        Log.d(TAG, "release(leave)");
    }

    @Override
    public void start() throws Exception {
        Log.d(TAG, "start(enter)");
        updateStatus(ST_STARTED);
        if (mAudioEncoder == null) {
            throw new Exception("encoder not prepared!");
        }

        //Encoder를 시작한다.
        mAudioEncoder.startEncoder(mEncoderStartListener);

        Log.d(TAG, "start(leave)");
        return;
    }

    @Override
    public void stop(final StopListener stopListener) {
        Log.d(TAG, "stop(enter)");
        updateStatus(ST_STOPPED);

        SimpleEncoder encoder = mAudioEncoder;
        if (encoder != null) {
            encoder.stopEncoder(mEncoderStopListener);
            mAudioEncoder = null;

            //wait...
            Log.d(TAG, "stop(waiting)");
            synchronized (__STOP_WAITER__) {
                try {
                    __STOP_WAITER__.wait(1000);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        //release all the resource
        Log.d(TAG, "stop(release)");
        release();

        //call the stop callback
        if (stopListener != null) {
            try {
                stopListener.onStop();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        Log.d(TAG, "stop(leave)");

        return;
    }

    @Override
    public void writeSample(int encoderType, byte[] bytes, int offset, int length, long pTimeUS) throws Exception {
        Log.d(TAG, "writeSample(enter) - encoderType: " + encoderType + ", length: " + length + ", pTimeUS: " + pTimeUS + ", trackIndex: " + mTrackIndex);
        SimpleEncoder encoder = mAudioEncoder;
        if (encoder != null) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.position(offset).limit(length);
            encoder.encode(buffer, length, pTimeUS, "writeSample");
        }

        Log.d(TAG, "writeSample(leave) - encoderType: " + encoderType + ", trackIndex: " + mTrackIndex);

        return;
    }

    /**
     * StartLister를 정의한다.
     */
    private SimpleEncoder.StartListener mEncoderStartListener = new SimpleEncoder.StartListener() {
        @Override
        public void onStart(SimpleEncoder encoder, MediaFormat outputFormat) {
            Log.i(TAG, "onStart() - encoder: " + encoder.getMimeType() + ", outputFormat: " + outputFormat + ", trackIndex: " + mTrackIndex);
            if (mTrackIndex != -1) {
                Log.e(TAG, "mAudioEncoder.onStart() - Already Started!");
                return;
            }

            ///////////////////////////////////////////////////
            // Muxer addTrack
            //////////////////////////////////////////////////
            try {
                mTrackIndex = mMx.addTrack(outputFormat);
                mMx.start();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onEncoded(SimpleEncoder encoder, ByteBuffer buffer, MediaCodec.BufferInfo info) {
            Log.i(TAG, "onEncoded() - encoder: " + encoder.getMimeType() + ", buffer: " + buffer + ", info: " + info + ", trackIndex: " + mTrackIndex);
            if (mTrackIndex == -1) {
                Log.e(TAG, "mAudioEncoder.onEncoded() - Not Started!");
                return;
            }

            ///////////////////////////////////////////////////
            // Muxer writeSampleData
            //////////////////////////////////////////////////
            try {
                mMx.writeSampleData(mTrackIndex, buffer, info);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * StopListener를 정의한다.
     */
    private SimpleEncoder.StopListener mEncoderStopListener = new SimpleEncoder.StopListener() {
        @Override
        public void onStop(SimpleEncoder encoder) {
            Log.i(TAG, "onStop() - encoder: " + encoder.getMimeType() + ", trackIndex: " + mTrackIndex);

            synchronized (__STOP_WAITER__) {
                __STOP_WAITER__.notifyAll();
            }
        }
    };
}
