package com.kodeholic.simplerecorder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

public class SimpleEncoder {
    public static final String TAG = SimpleEncoder.class.getSimpleName();

    public static final int TIMEOUT_USEC = 100000; //100[msec]

    //
    public static final int ENCODER_TYPE_AUDIO = 0;
    public static final int ENCODER_TYPE_VIDEO = 1;

    private String __ENCODER_LOCK__ = "L";
    private Context    mContext;
    private String     mMimeType;
    private MediaCodec mCodec;
    private MediaCodec.BufferInfo mBufferInfo;
    private int        mEncoderType;
    //Listener
    private StartListener mStartListener;
    private StopListener  mStopListener;

    //상태 관리
    private boolean mDrainStarted; //중복 실행 방지
    private boolean mDrainRunning; //쓰레이드 시작/종료 관리
    private int     mDrainAgainLaterCount;

    //
    private MediaFormat mRequestedFormat = null;
    private MediaFormat mAppliedFormat = null;

    public interface StartListener {
        public void onStart(SimpleEncoder encoder, MediaFormat outputFormat);
        public void onEncoded(SimpleEncoder encoder, ByteBuffer buffer, MediaCodec.BufferInfo info);
    }

    public interface StopListener {
        public void onStop(SimpleEncoder encoder);
    }

    public SimpleEncoder(Context context, int encoderType, String mimeType, MediaFormat format) throws Exception {
        Log.d(TAG, "SimpleEncoder() - encoderType: " + encoderType + ", mimeType: " + mimeType + ", format: " + format);
        mContext    = context;
        mEncoderType= encoderType;
        mMimeType   = mimeType;
        mRequestedFormat = format;

        mDrainRunning = false;
        mDrainAgainLaterCount = 0;
        mDrainStarted = false;

        mBufferInfo = new MediaCodec.BufferInfo();
        mCodec = MediaCodec.createEncoderByType(mMimeType);
        mCodec.configure(mRequestedFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mCodec.start();
    }

    public boolean isAudio() { return (mEncoderType == ENCODER_TYPE_AUDIO); }
    public boolean isVideo() { return (mEncoderType == ENCODER_TYPE_VIDEO); }

    public String getMimeType() {
        return mMimeType;
    }

    public MediaFormat getMediaFormat() {
        return mAppliedFormat;
    }

    /**
     * Encoder를 시작한다.
     * @param l
     */
    public void startEncoder(StartListener l) {
        synchronized (__ENCODER_LOCK__) {
            if (mDrainStarted) {
                Log.d(TAG, "startEncoder() - Already started!");
                return;
            }
            mDrainStarted = true;
            mDrainRunning = true;
            mStartListener= l;
        }
        Thread runner = new Thread(new DrainRunnable());
        runner.start();
    }

    /**
     * Encoder를 종료한다.
     * @param l
     */
    public void stopEncoder(StopListener l) {
        synchronized (__ENCODER_LOCK__) {
            mDrainRunning = false;
            mStopListener = l;
        }
    }

    /**
     * 추출기...
     */
    public class DrainRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "DrainRunnable() - STARTED, running: " + isDrainRunning());
            while (isDrainRunning()) {
                try {
                    int drainAgainLaterCount = getDrainAgainLaterCount();
                    Log.d(TAG, "DrainRunnable() - drain(enter) - drainAgainLaterCount: " + drainAgainLaterCount);
                    drain(0);
                    drainAgainLaterCount = getDrainAgainLaterCount();
                    Log.d(TAG, "DrainRunnable() - drain(leave) - drainAgainLaterCount: " + drainAgainLaterCount);

                    //drain again이 너무 많이 반복...
                    if (drainAgainLaterCount >= 2) {
                        Log.w(TAG, "drain(2) - Too many tries!! drainAgainLaterCount: " + drainAgainLaterCount);
                        initDrainAgainLaterCount();

                        synchronized (__ENCODER_LOCK__) {
                            try {
                                if (isDrainRunning()) {
                                    __ENCODER_LOCK__.wait(200);
                                }
                            }
                            catch (Exception ignore) { ; }
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

            //
            long current = System.currentTimeMillis();
            long limit   = current + 1000; //1초 정보 확인 사살
            while (limit > current && drain(200 * 1000)) {
                current = System.currentTimeMillis();
            }
            Log.d(TAG, "DrainRunnable() - STOPPED, running: " + isDrainRunning());

            //종료되었음을 알린다.
            if (mStopListener != null) {
                mStopListener.onStop(SimpleEncoder.this);
            }
        }
    }

    /**
     * 원시 데이타를 encode 요청한다.
     * @param buffer
     * @param length
     * @param pTimeUs
     * @param f
     */
    protected void encode(final ByteBuffer buffer, final int length, final long pTimeUs, String f) {
        Log.d(TAG, "encode(enter) - f: " + f + ", buffer: " + buffer + ", length: " + length + ", pTimeUs: " + pTimeUs + ", running: " + isDrainRunning());

        boolean result = false;
        while (isDrainRunning()) {
            final int inputIndex = mCodec.dequeueInputBuffer(TIMEOUT_USEC);
            Log.d(TAG, "encode() - dequeueInputBuffer inputIndex: " + inputIndex);

            //무언가 문제가 있다!!
            if (inputIndex < 0) {
                switch (inputIndex) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.w(TAG, "encode() - INFO_TRY_AGAIN_LATER");
                        break;

                    default:
                        Log.w(TAG, "encode() - unexpected result: " + inputIndex);
                        break;
                }
                continue;
            }

            //정상
            final ByteBuffer inputBuffer = mCodec.getInputBuffer(inputIndex);
            inputBuffer.clear();
            if (buffer != null) {
                inputBuffer.put(buffer);
            }
            if (length <= 0) {
                setDrainRunning(false, "encode() - length <= 0");
                Log.w(TAG, "encode() - queueInputBuffer(EOS) - inputIndex: " + inputIndex + ", length: " + length);
                mCodec.queueInputBuffer(inputIndex, 0, 0, pTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            else {
                Log.d(TAG, "encode() - queueInputBuffer() - inputIndex: " + inputIndex + ", length: " + length + ", pTimeUs: " + pTimeUs);
                mCodec.queueInputBuffer(inputIndex, 0, length, pTimeUs, 0);
                result = true;
            }

            //drain에게 알린다.
            synchronized (__ENCODER_LOCK__) {
                __ENCODER_LOCK__.notifyAll();
            }
            break;
        }

        Log.d(TAG, "encode(leave) - f: " + f + ", running: " + isDrainRunning() + ", result: " + result);
    }

    /**
     * encode된 데이타를 추츨한다.
     * @param _timeoutUs
     * @return
     */
    protected boolean drain(long _timeoutUs) {
        if (mCodec == null) {
            return false;
        }
        final long timeoutUs   = Math.max(_timeoutUs, TIMEOUT_USEC);
        final int  outputIndex = mCodec.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        Log.d(TAG, "drain() - dequeueOutputBuffer outputIndex: " + outputIndex + ", timeoutUs: " + timeoutUs);

        //무언가 문제가 있다!!
        if (outputIndex < 0) {
            switch (outputIndex) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    //Log.d(TAG, "drain() - INFO_TRY_AGAIN_LATER");
                    addDrainAgainLaterCount();
                    break;

                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.w(TAG, "drain() - INFO_OUTPUT_FORMAT_CHANGED");
                    //실제 적용된 format을 저장한다.
                    mAppliedFormat = mCodec.getOutputFormat();

                    //addTrack() - 중복 시작 여부 체크 필요
                    if (mStartListener != null) {
                        mStartListener.onStart(this, mAppliedFormat);
                    }
                    break;

                default:
                    Log.w(TAG, "drain() - unexpected result: " + outputIndex);
                    break;
            }

            return false;
        }

        final ByteBuffer encodedData = mCodec.getOutputBuffer(outputIndex);
        if (encodedData == null) {
            Log.w(TAG, "drain() - getOutputBuffer() returns null. outputIndex: " + outputIndex);
            return false;
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            Log.w(TAG, "drain() - BUFFER_FLAG_CODEC_CONFIG");
            mBufferInfo.size = 0;
        }

        if (mBufferInfo.size != 0) {
            //set a drain-try-count zero
            initDrainAgainLaterCount();
            //calculate pTime...
            mBufferInfo.presentationTimeUs = getPTimeUs(mPrevPTimeUs);
            if (mStartListener != null) {
                mStartListener.onEncoded(this, encodedData, mBufferInfo);
            }
            mPrevPTimeUs = mBufferInfo.presentationTimeUs;
        }

        //release the output buffer
        mCodec.releaseOutputBuffer(outputIndex, false);

        return (mBufferInfo.size != 0);
    }

    private int getDrainAgainLaterCount() {
        synchronized (__ENCODER_LOCK__) {
            return mDrainAgainLaterCount;
        }
    }

    private int addDrainAgainLaterCount() {
        synchronized (__ENCODER_LOCK__) {
            return ++mDrainAgainLaterCount;
        }
    }

    private void initDrainAgainLaterCount() {
        synchronized (__ENCODER_LOCK__) {
            mDrainAgainLaterCount = 0;
        }
    }

    private void setDrainRunning(boolean running, String f) {
        Log.d(TAG, "setRunning() - f: " + f + ", running: " + mDrainRunning + " --> " + running);
        synchronized (__ENCODER_LOCK__) {
            mDrainRunning = running;
        }
    }

    protected boolean isDrainRunning() {
        synchronized (__ENCODER_LOCK__) {
            return mDrainRunning;
        }
    }

    //////////////////////////////////////////////////////////
    // pTimeUs를 산출한다.
    //////////////////////////////////////////////////////////
    private long mPrevPTimeUs = 0;
    public static long getPTimeUs(long prevPTimeUs) {
        long current = System.nanoTime() / 1000L;
        if (current < prevPTimeUs) {
            current = (prevPTimeUs - current) + current;
        }
        return current;
    }
}
