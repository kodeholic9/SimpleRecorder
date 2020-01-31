package com.kodeholic.simpletest;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;

public abstract class SimpleMuxer {
    public static final int ST_INIT     = 0;
    public static final int ST_PREPARED = 1;
    public static final int ST_STARTED  = 2;
    public static final int ST_STOPPING = 3;
    public static final int ST_STOPPED  = 4;

    public static final String valueOfStatus(int status) {
        switch (status) {
            case ST_INIT     : return "INIT";
            case ST_PREPARED : return "PREPARED";
            case ST_STARTED  : return "STARTED";
            case ST_STOPPING : return "ST_STOPPING";
            case ST_STOPPED  : return "ST_STOPPED";
        }

        return "UNK(" + status + ")";
    }

    /**
     * Audio 포맷 정보
     */
    public static class AudioConfig {
        public int sampleRateInHz; // 44100 ...
        public int channelConfig;  // AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO ...
        public int audioFormat;    // AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT ...
        public int channelCount;   // 1

        public AudioConfig() { }
        public AudioConfig(int sampleRateInHz, int channelConfig, int audioFormat, int channelCount) {
            this.sampleRateInHz = sampleRateInHz;
            this.channelConfig  = channelConfig;
            this.audioFormat    = audioFormat;
            this.channelCount   = channelCount;
        }

        @Override
        public String toString() {
            return "AudioConfig{" +
                    "sampleRateInHz=" + sampleRateInHz +
                    ", channelConfig=" + channelConfig +
                    ", audioFormat=" + audioFormat +
                    ", channelCount=" + channelCount +
                    '}';
        }
    }

    /**
     * Video 포맷 정보
     */
    public class VideoConfig {
        public VideoConfig() { }
    }

    public interface StopListener {
        public void onStop();
    }
    //////////////////////////////////////////////////////////////////
    //
    // Members
    //
    //////////////////////////////////////////////////////////////////
    protected String path;
    protected String TAG;
    protected AudioConfig audioConfig;
    protected VideoConfig videoConfig;

    protected Context mContext;
    protected int mStatus = ST_INIT;

    public SimpleMuxer(Context context, String path, String tag) throws IOException {
        mContext = context;

        this.path = path;
        this.TAG  = tag;
        this.audioConfig = new AudioConfig();
        this.videoConfig = new VideoConfig();
    }
    public boolean isPrepared() { return mStatus == ST_PREPARED; }
    public boolean isStarted()  { return mStatus == ST_STARTED; }
    public boolean isStopped()  { return mStatus == ST_STOPPED; }

    protected void updateStatus(int newStatus) throws IllegalStateException {
        Log.d(TAG, "updateStatus() - oldStatus: " + valueOfStatus(mStatus) + ", newStatus: " + valueOfStatus(newStatus));

        if (newStatus != mStatus) {
            switch (newStatus) {
                case ST_INIT:
                    throw new IllegalStateException("Invalid newStatus: " + valueOfStatus(newStatus)
                            + ", oldStatus: " + valueOfStatus(mStatus));

                case ST_PREPARED:
                    if (mStatus != ST_INIT && mStatus != ST_PREPARED) {
                        throw new IllegalStateException("Invalid newStatus: " + valueOfStatus(newStatus)
                                + ", oldStatus: " + valueOfStatus(mStatus));
                    }
                    break;

                case ST_STARTED:
                    if (mStatus != ST_PREPARED) {
                        throw new IllegalStateException("Invalid newStatus: " + valueOfStatus(newStatus)
                                + ", oldStatus: " + valueOfStatus(mStatus));
                    }
                    break;

                case ST_STOPPING:
                case ST_STOPPED:
                    break;
            }
            mStatus = newStatus;
        }
    }

    protected int channelConfig2Count(int channelConfig) {
        switch (channelConfig) {
            case AudioFormat.CHANNEL_IN_DEFAULT: // AudioFormat.CHANNEL_CONFIGURATION_DEFAULT
            case AudioFormat.CHANNEL_IN_MONO:
            case AudioFormat.CHANNEL_CONFIGURATION_MONO:
                return 1;
            case AudioFormat.CHANNEL_IN_STEREO:
            case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            case (AudioFormat.CHANNEL_IN_FRONT | AudioFormat.CHANNEL_IN_BACK):
                return 2;
            case AudioFormat.CHANNEL_INVALID:
            default:
                return -1;
        }
    }

    //////////////////////////////////////////////////////////////////
    //
    // Implements!
    //
    //////////////////////////////////////////////////////////////////
    public abstract void addAudioTrack(AudioConfig audioConfig) throws Exception;
    public abstract void addVideoTrack(VideoConfig videoConfig) throws Exception;
    public abstract void start() throws Exception;
    public abstract void writeSample(int encoderType, byte[] bytes, int offset, int length, long pTimeUS) throws Exception;
    public abstract void stop(final StopListener stopListener);
}
