package com.kodeholic.simpletest;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class SimpleWAVMuxer extends SimpleMuxer {
    public static final String TAG = SimpleWAVMuxer.class.getSimpleName();

    private static final int HEADER_SIZE = 44;

    private int bitsPerSample; //16
    private int byteRate;      //sampleRateInHz * channelCount * (bitsPerSample / 8)
    private int blockAlign;    //                 channelCount * (bitsPerSample / 8)

    private File         mTargetFile = null;
    private OutputStream mOStream = null;
    private int          mTotalPCMLength = 0;

    public SimpleWAVMuxer(Context context, String path) throws IOException {
        super(context, path, SimpleWAVMuxer.class.getSimpleName());

        mTargetFile = new File(path);
        mTotalPCMLength = 0;
    }

    /**
     * generate a WAV header
     * @return
     */
    private byte[] getWAVHeader() {
        byte[] h = new byte[HEADER_SIZE];
        int fileLength   = HEADER_SIZE + mTotalPCMLength;
        int headChunkSize = fileLength - 6;
        int fmt_ChunkSize = 16;
        int dataChunkSize = fileLength - HEADER_SIZE;

        Log.d(TAG, "getWAVHeader() - audioConfig: " + audioConfig
                + ", bitsPerSample: " + bitsPerSample
                + ", byteRate: " + byteRate
                + ", blockAlign: " + blockAlign
                + ", headChunkSize: " + headChunkSize
                + ", fmt_ChunkSize: " + fmt_ChunkSize
                + ", dataChunkSize: " + dataChunkSize
        );

        //'RIFF' chunk -------------------------------------
        h[0]  = 'R'; h[1]  = 'I'; h[2]  = 'F'; h[3]  = 'F';
        //--------------------------------------------------
        h[4]  = (byte)((headChunkSize >>  0) & 0xff);
        h[5]  = (byte)((headChunkSize >>  8) & 0xff);
        h[6]  = (byte)((headChunkSize >> 16) & 0xff);
        h[7]  = (byte)((headChunkSize >> 24) & 0xff);
        h[8]  = 'W';
        h[9]  = 'A';
        h[10] = 'V';
        h[11] = 'E';

        //'fmt ' sub-chunk ---------------------------------
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        //--------------------------------------------------
        h[16] = (byte)((fmt_ChunkSize >>  0) & 0xff);
        h[17] = (byte)((fmt_ChunkSize >>  8) & 0xff);
        h[18] = (byte)((fmt_ChunkSize >> 16) & 0xff);
        h[19] = (byte)((fmt_ChunkSize >> 24) & 0xff);
        h[20] = (byte)1;  //format: 1 (==PCM)
        h[21] = 0;
        h[22] = (byte)(audioConfig.channelCount & 0xff);
        h[23] = 0;
        h[24] = (byte)((audioConfig.sampleRateInHz >>  0) & 0xff);
        h[25] = (byte)((audioConfig.sampleRateInHz >>  8) & 0xff);
        h[26] = (byte)((audioConfig.sampleRateInHz >> 16) & 0xff);
        h[27] = (byte)((audioConfig.sampleRateInHz >> 24) & 0xff);
        h[28] = (byte)((byteRate      >>  0) & 0xff);
        h[29] = (byte)((byteRate      >>  8) & 0xff);
        h[30] = (byte)((byteRate      >> 16) & 0xff);
        h[31] = (byte)((byteRate      >> 24) & 0xff);
        h[32] = (byte)((blockAlign    >>  0) & 0xff);
        h[33] = (byte)((blockAlign    >>  8) & 0xff);
        h[34] = (byte)((bitsPerSample >>  0) & 0xff);
        h[35] = (byte)((bitsPerSample >>  8) & 0xff);

        //'data' sub-chunk ---------------------------------
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        //--------------------------------------------------
        h[40] = (byte)((dataChunkSize >>  0) & 0xff);
        h[41] = (byte)((dataChunkSize >>  8) & 0xff);
        h[42] = (byte)((dataChunkSize >> 16) & 0xff);
        h[43] = (byte)((dataChunkSize >> 24) & 0xff);

        return h;
    }

    @Override
    public void addAudioTrack(AudioConfig audioConfig) throws Exception {
        Log.d(TAG, "addAudioTrack() - audioConfig: " + audioConfig);

        if (mStatus != ST_INIT) {
            throw new IllegalStateException("Invalid mStatus: " + valueOfStatus(mStatus));
        }
        updateStatus(ST_PREPARED);

        //파라미터를 설정한다.
        this.audioConfig.sampleRateInHz = audioConfig.sampleRateInHz; //format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        this.audioConfig.channelCount   = audioConfig.channelCount;   //format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        this.audioConfig.channelConfig  = audioConfig.channelConfig;  //AudioFormat.CHANNEL_IN_MONO;
        this.audioConfig.audioFormat    = audioConfig.audioFormat;    //AudioFormat.ENCODING_PCM_16BIT;
        //
        bitsPerSample  = 16;
        byteRate       = this.audioConfig.sampleRateInHz * this.audioConfig.channelCount * bitsPerSample / 8;
        blockAlign     = this.audioConfig.channelCount * bitsPerSample / 8;

        //로깅한다.
        Log.d(TAG, "addAudioTrack() - audioConfig: " + this.audioConfig
                + ", bitsPerSample: " + bitsPerSample
                + ", byteRate: " + byteRate
                + ", blockAlign: " + blockAlign
        );

        return;
    }

    @Override
    public void addVideoTrack(VideoConfig videoConfig) throws Exception { ; }

    private void release() {
        Log.d(TAG, "release()");

        //OStream을 닫는다.
        try {
            if (mOStream != null) {
                mOStream.close();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        mOStream = null;
    }

    @Override
    public void start() throws Exception {
        Log.d(TAG, "start()");
        updateStatus(ST_STARTED);

        //output stream 생성
        try {
            mOStream = new FileOutputStream(mTargetFile);
        }
        catch (IOException e) {
            throw e;
        }

        //헤더 44바이트를 write한다.
        mOStream.write(getWAVHeader());
    }

    @Override
    public void stop(StopListener stopListener) {
        Log.d(TAG, "stop()");
        updateStatus(ST_STOPPED);

        //RAF를 열고, header를 write한다.
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(mTargetFile, "rw");
            raf.write(getWAVHeader());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            if (stopListener != null) {
                stopListener.onStop();
            }
            if (raf != null) {
                try {
                    raf.close();
                }
                catch (Exception ignore) {
                }
            }

            //free all the resource
            release();
        }
    }

    @Override
    public void writeSample(int encoderType, byte[] bytes, int offset, int length, long pTimeUS) throws Exception {
        Log.d(TAG, "writeSample() - encoderType: " + encoderType + ", length: " + length + ", pTimeUS: " + pTimeUS);

        //현재 상태 체크!
        if (mStatus != ST_STARTED) {
            Log.d(TAG, "writeSampleData() - Invalid mStatus: " + valueOfStatus(mStatus));
            return;
        }
        //전체 길이를 갱신한다.
        mTotalPCMLength += bytes.length;
        //파일에 write한다.
        mOStream.write(bytes, 0, bytes.length);
    }
}
