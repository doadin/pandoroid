package com.pandoroid.playback;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.pandoroid.Pandoroid;
import com.pandoroid.pandora.PandoraAudioUrl;
import com.pandoroid.pandora.Song;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

public class ConcurrentSongMediaPlayer {
    private SharedPreferences m_prefs;
    private Equalizer mEqualizer;
    private File tempMp3;
    
    //A constant for setting the percentage of a song's total length that needs
    //to be played before it is determined as being effectively finished.
    public static final double MINIMUM_SONG_COMPLETENESS = 99.9;
    
    public volatile Boolean m_buffer_complete_flag = false;
    
    public ConcurrentSongMediaPlayer(){
        m_player = new MediaPlayer();
        m_alive = false;
        m_buffering_counter = -1;
    }

    /**
     * Description: Overloaded constructor for setting the song upon creation.
     * @param song -The song to initialize to.
     */
    public ConcurrentSongMediaPlayer(Song song){
        m_player = new MediaPlayer();
        setSong(song);
    }
    
    /**
     * Description: Overloaded constructor for setting the initialized song, 
     *  and the MediaPlayer to use.
     * @param song -The Song to initialize to.
     * @param mp -The MediaPlayer to initialize to.
     */
    public ConcurrentSongMediaPlayer(Song song, MediaPlayer mp){
        m_player = mp;
        setSong(song);
    }
    
    /**
     * Description: Copies the contents of another ConcurrentSongMediaPlayer
     *  into itself. 
     * @param other_player -The ConcurrentSongMediaPlayer to copy from.
     */
    public void copy(ConcurrentSongMediaPlayer other_player){
        if (this != other_player){
            release();
            synchronized(this){
                m_player = other_player.getPlayer();
            }
            m_song = other_player.getSong();
            m_buffer_complete_flag = other_player.m_buffer_complete_flag;
            m_alive = other_player.m_alive;
            m_url = other_player.getUrl();
        }
    }
    
    /**
     * Description: Synchronized method that retrieves the audio session id from
     *  the underlying MediaPlayer.
     * @return The audio session id.
     */
    public int getAudioSessionId(){
        synchronized(this){
            try{
                return m_player.getAudioSessionId();
            }
            catch(IllegalStateException e){
                return 0;
            }
        }
    }
    
    /**
     * Description: Synchronized method that retrieves the current position
     *  from the underlying MediaPlayer.
     * @return
     */
    public int getCurrentPosition(){
        synchronized(this){
            return m_player.getCurrentPosition();
        }
    }
    
    /**
     * Description: Synchronized method that retrieves the duration of the 
     *  song from the underlying MediaPlayer.
     * @return int time
     */
    public int getDuration(){
        if (m_alive){
            synchronized(this){
                return m_player.getDuration();
            }
        }
        else{
            return -1; //Signifying an error.
        }
    }
    
    /**
     * Description: Synchronized method that returns the underlying MediaPlayer.
     * @return mediaplayer instance
     */
    public MediaPlayer getPlayer(){
        synchronized(this){
            return m_player;
        }
    }
    
    /**
     * Description: Returns the song.
     * @return song title
     */
    public Song getSong(){
        return m_song;
    }
    
    /**
     * Description: Gets the url.
     * @return url
     */
    public PandoraAudioUrl getUrl(){
        return m_url;
    }
    
    /**
     * Description: If this player is in fact buffering, then for every 5 calls,
     *  it will return true;
     * @return true/false if we are buffering
     */
    public boolean isBuffering(){
        synchronized(buffer_lock){
            if (m_buffering_counter > 0){
                --m_buffering_counter;
            }
            else if(m_buffering_counter == 0){
                m_buffering_counter = 4;
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Description: Checks to see if playback can be counted as complete.
     * @return A boolean of true if it's complete, else false.
     */
    public boolean isPlaybackComplete() {

        // The Android API's are borked in KitKat. It's better to simply not 
        // bother with it.
        if (!isPlaying()) {
            return true;
        }
        //int song_length = 0;
        //int current_pos = 0;
        //if (m_player.isPlaying()) {
        //    song_length = getDuration();
        //    current_pos = getCurrentPosition();
        //}
        ////int song_length = getDuration();
        ////int current_pos = getCurrentPosition();
        //if (song_length == 0 && current_pos == 0 ){
        //    return true;
        //}
        //double end_song_position = song_length * (MINIMUM_SONG_COMPLETENESS / 100F);
        //Log.i("Pandoroid", "isPlaybackComplete: " + "song length: " + song_length + " current pos: " + current_pos + " end_song_position: " + end_song_position);
        ////return !(current_pos < end_song_position);
        //if (current_pos >= song_length){
        //    return true;
        //}
        return false;
    }
    
    /**
     * Description: Synchronized method that determines if the underlying 
     *  MediaPlayer is playing.
     * @return true/false is media play instance playing
     */
    public boolean isPlaying(){
        synchronized(this){
            return m_player.isPlaying();
        }
    }
    
    public boolean isSeeking(){
        return m_seeking_flag;
    }

    public void setupeq() {
        Context context = Pandoroid.getContext();
        m_prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (isEqualizerSupported()) {
            mEqualizer = new Equalizer(0, m_player.getAudioSessionId());
            short CurrentPreset = mEqualizer.getCurrentPreset();
            short NumberOfPresets = mEqualizer.getNumberOfPresets();
            final Equalizer.Settings settings = new Equalizer.Settings();
            settings.curPreset = CurrentPreset;
            if (m_prefs.getBoolean("player_equalizer", true)) {
                mEqualizer.setEnabled(true);
                Log.i("Pandoroid", "ConcurrentSongMediaPlayer: eq IS enabled");
            }
            else{
                mEqualizer.setEnabled(false);
                Log.i("Pandoroid", "ConcurrentSongMediaPlayer: eq IS disabled");
            }
            String preset = m_prefs.getString("player_preset", "0");
            int presetset = Integer.parseInt(preset);
            if (presetset >= 0 && presetset < NumberOfPresets){

                mEqualizer.usePreset((short) presetset);
                Log.i("Pandoroid", "ConcurrentSongMediaPlayer: num of presets: " + NumberOfPresets);
                Log.i("Pandoroid", "ConcurrentSongMediaPlayer: current preset: " + presetset + " " + CurrentPreset + " " + mEqualizer.getPresetName((short) CurrentPreset));
                //Number of supported = 10 aka 0-9
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 0));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 1));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 2));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 3));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 4));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 5));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 6));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 7));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 8));
                //Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset" + mEqualizer.getPresetName((short) 9));
            }
            else{
                Log.i("Pandoroid", "ConcurrentSongMediaPlayer: preset invalid reverting to normal");
                mEqualizer.usePreset((short) 0);
            }
            Log.i("Pandoroid", "ConcurrentSongMediaPlayer: eq IS supported");
        }
        else{
            Log.i("Pandoroid", "ConcurrentSongMediaPlayer: eq NOT supported");
        }
    }
    
    /**
     * Description: Synchronized method that pauses the underlying MediaPlayer.
     */
    public void pause(){
        synchronized(this){
            m_player.pause();
            mEqualizer.release();
        }
    }
    
    /**
     * Description: This is an all in one function that resets, sets the data 
     *  source, prepares, and if appropriate seeks to the appropriate position 
     *  for the MediaPlayer. After a successful call, start can be called.
     * @param url -The url to set the player to use.
     * @throws IllegalArgumentException
     * @throws SecurityException
     * @throws IllegalStateException
     * @throws IOException
     */
    public void prepare(PandoraAudioUrl url) throws IllegalArgumentException,
                                                    SecurityException,
                                                    IllegalStateException,
                                                    IOException{
        m_url = url;
        int prev_playback_pos = m_alive ? getCurrentPosition() : -1;
        reset();

        synchronized(this) {
            final OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(60000, TimeUnit.MILLISECONDS)
                    .writeTimeout(60000, TimeUnit.MILLISECONDS)
                    .connectTimeout(60000, TimeUnit.MILLISECONDS)
                    .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                    .retryOnConnectionFailure(true)
                    .build();

            Request request = new Request.Builder()
                    .addHeader("Connection", "close")
                    .url(String.valueOf(url))
                    .addHeader("User-Agent", "Pithos")
                    .build();
            Log.i("Pandoroid", String.valueOf(url));

            tempMp3 = File.createTempFile("pandoroid", ".mp4");
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected response code: " + response.code());
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new IOException("Response body is null");
                }
                try (BufferedSink sink = Okio.buffer(Okio.sink(tempMp3))) {
                    responseBody.source().readAll(sink);
                    sink.flush();
                }
            }

            m_player.setDataSource(tempMp3.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                m_player.setAudioAttributes(aa);
            } else {
                m_player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            m_player.prepare();
        }
        m_alive = true;
        setBuffering(false);

        if (prev_playback_pos > 0) {
            seekTo(prev_playback_pos);
        }
    }

    public void startPlayAfterDownload() throws IOException {
        synchronized(this){
            if (tempMp3 != null && tempMp3.exists()) {
                m_player.setDataSource(tempMp3.getAbsolutePath());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    m_player.setAudioAttributes(aa);
                } else {
                    m_player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                }
                m_player.prepare();
            }
        }
        //m_alive = true;
        //setBuffering(false);
    }


    //public void prepare(PandoraAudioUrl url) throws IOException {
    //    m_url = url;
    //    int prev_playback_pos;
//
    //    if (m_alive) {
    //        prev_playback_pos = getCurrentPosition();
    //    } else {
    //        prev_playback_pos = -1;
    //    }
//
    //    if (m_player != null) {
    //        if (m_player.isPlaying() || m_player.getCurrentPosition() > 0) {
    //            Log.i("Pandoroid", "MediaPlayer already prepared, resuming...");
    //            m_player.start();  // Resume playback if already prepared
    //            return;
    //        }
    //        m_player.reset();
    //    } else {
    //        m_player = new MediaPlayer();
    //    }
//
    //    synchronized (this) {
    //        final OkHttpClient client = new OkHttpClient.Builder()
    //                .readTimeout(60000, TimeUnit.MILLISECONDS)
    //                .writeTimeout(60000, TimeUnit.MILLISECONDS)
    //                .connectTimeout(60000, TimeUnit.MILLISECONDS)
    //                .retryOnConnectionFailure(true)
    //                .build();
//
    //        File tempMp3 = null;
    //        int maxRetries = 3;
    //        int attempt = 0;
    //        long totalBytesRead = 0;
    //        long contentLength = 0;
    //        boolean downloadComplete = false;
//
    //        while (attempt < maxRetries) {
    //            try {
    //                tempMp3 = (tempMp3 == null) ? File.createTempFile("pandoroid", ".mp4") : tempMp3;
    //                Log.i("Pandoroid", "Attempt #" + (attempt + 1));
//
    //                // Create request with Range header for resuming
    //                Request.Builder requestBuilder = new Request.Builder()
    //                        .url(String.valueOf(url))
    //                        .addHeader("User-Agent", "Pithos");
//
    //                if (totalBytesRead > 0) {
    //                    requestBuilder.addHeader("Range", "bytes=" + totalBytesRead + "-");
    //                    Log.i("Pandoroid", "Resuming from byte " + totalBytesRead);
    //                }
//
    //                Request request = requestBuilder.build();
//
    //                try (Response response = client.newCall(request).execute()) {
    //                    if (!response.isSuccessful()) {
    //                        Log.e("Pandoroid", "Request failed: " + response.code());
    //                        return;
    //                    }
//
    //                    ResponseBody responseBody = response.body();
    //                    if (responseBody == null) {
    //                        Log.e("Pandoroid", "Response body is null.");
    //                        return;
    //                    }
//
    //                    contentLength = responseBody.contentLength();
    //                    Log.i("Pandoroid", "Expected Content-Length: " + contentLength);
//
    //                    try (BufferedSink sink = Okio.buffer(Okio.sink(new FileOutputStream(tempMp3, totalBytesRead > 0)));
    //                         BufferedSource source = responseBody.source()) {
//
    //                        long bytesRead;
    //                        Buffer buffer = new Buffer();
//
    //                        while ((bytesRead = source.read(buffer, 8192)) != -1) {
    //                            sink.write(buffer, bytesRead);
    //                            totalBytesRead += bytesRead;
    //                            Log.i("Pandoroid", "Downloaded file size: " + totalBytesRead + " bytes");
    //                        }
//
    //                        sink.flush();
    //                        Log.i("Pandoroid", "Final downloaded size: " + totalBytesRead + " bytes");
//
    //                        if (totalBytesRead >= contentLength) {
    //                            downloadComplete = true;
    //                            break; // Exit retry loop
    //                        }
    //                    }
    //                }
    //            } catch (IOException e) {
    //                Log.e("Pandoroid", "Download failed, retrying... Attempt: " + (attempt + 1));
    //                attempt++;
//
    //                if (attempt == maxRetries) {
    //                    Log.e("Pandoroid", "Max retries reached. Download failed.");
    //                }
    //            }
    //        }
//
    //        if (!downloadComplete || tempMp3 == null || !tempMp3.exists() || tempMp3.length() == 0) {
    //            Log.e("Pandoroid", "Download unsuccessful or file does not exist.");
    //            return;
    //        }
//
    //        if (m_player == null) {
    //            Log.e("Pandoroid", "MediaPlayer is null, cannot proceed.");
    //            return;
    //        }
//
    //        // Ensure MediaPlayer is in a valid state
    //        try {
    //            m_player.setDataSource(tempMp3.getAbsolutePath());
//
    //            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
    //                AudioAttributes aa = new AudioAttributes.Builder()
    //                        .setUsage(AudioAttributes.USAGE_MEDIA)
    //                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    //                        .build();
    //                m_player.setAudioAttributes(aa);
    //            } else {
    //                m_player.setAudioStreamType(AudioManager.STREAM_MUSIC);
    //            }
//
    //            m_player.setOnPreparedListener(mp -> {
    //                Log.i("Pandoroid", "MediaPlayer prepared, starting playback...");
    //                m_player.start();
    //                m_alive = true;
    //                setBuffering(false);
//
    //                if (prev_playback_pos > 0) {
    //                    Log.i("Pandoroid", "Seeking to previous position: " + prev_playback_pos);
    //                    m_player.seekTo(prev_playback_pos);
    //                }
    //            });
//
    //            Log.i("Pandoroid", "Calling prepareAsync()");
    //            m_alive = true;
    //            setBuffering(false);
    //            m_player.prepare(); // Ensure async preparation
//
    //        } catch (IOException e) {
    //            Log.e("Pandoroid", "Error setting MediaPlayer data source: " + e.getMessage());
    //        }
    //    }
    //}

    /**
     * Description: Synchronized method that releases the underlying MediaPlayer.
     */
    public void release(){
        synchronized(this){
            //if (tempMp3 != null && tempMp3.exists()){
            //    Log.i("Pandoroid", "Deleteing Temp File");
            //    tempMp3.delete();
            //}

            m_player.release();
        }
        m_alive = false;
    }
    
    /**
     * Description: Synchronized method that resets the underlying MediaPlayer.
     */
    public void reset(){
        synchronized(this){
            m_player.reset();
        }
        m_alive = false;
    }
    
    /**
     * Description: Synchronized method that sends a seekTo() command to the
     *  underlying MediaPlayer.
     * @param msec -The milliseconds to seek to.
     */
    public void seekTo(int msec){
        synchronized(this){
            m_player.seekTo(msec);
        }
    }
    
    /**
     * Description: Sets an internal buffer flag.
     * @param bool true flase buffering
     */
    public void setBuffering(boolean bool){
        synchronized(buffer_lock){
            if (bool){
                m_buffering_counter = 0;
            }
            else{
                m_buffering_counter = -1;
            }
        }
    }
    
    public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener listener){
        synchronized(this){
            m_player.setOnBufferingUpdateListener(listener);
        }
    }
    
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener){
        synchronized(this){
            m_player.setOnCompletionListener(listener);
        }
    }
    
    public void setOnErrorListener(MediaPlayer.OnErrorListener listener){
        synchronized(this){
            m_player.setOnErrorListener(listener);
        }
    }
    
    public void setOnInfoListener(MediaPlayer.OnInfoListener listener){
        synchronized(this){
            m_player.setOnInfoListener(listener);
        }
    }

    //FIXME
    //public void setOnParameterChangeListener(Equalizer.OnParameterChangeListener listener){
    //    synchronized(this){
    //        m_player.setOnParameterChangeListener(listener);
    //    }
    //}
    
    /**
     * Description: Resets the player with the specified song.
     * @param song -The Song to set to.
     */
    public void setSong(Song song){
        m_song = song;
        m_buffer_complete_flag = false;
        m_buffering_counter = -1;
        reset();
    }
    
    /**
     * Description: Synchronized method that starts the underlying media player.
     */
    public void start(){
        synchronized(this){
            m_player.start();
            setupeq();
        }
    }

    public boolean isEqualizerSupported() {
        int noOfBands = 0;
        int noOfPresents = 0;
        try {
            Equalizer equalizer = new Equalizer(0, m_player.getAudioSessionId());
            noOfBands = equalizer.getNumberOfBands();
            noOfPresents = equalizer.getNumberOfPresets();
            equalizer.release();
            equalizer = null;
		    return true;
        } catch (Exception e) {
        Log.e("pandoroid", "isequalizersupported: false" + e);
		return false;
        }    
    }
    
    private final Object buffer_lock = new Object();
    
    private Boolean m_alive;    
    private volatile int m_buffering_counter;
    private volatile boolean m_seeking_flag;
    private volatile Song m_song;
    private volatile PandoraAudioUrl m_url;
    private MediaPlayer m_player;
    
    private void setOnSeekCompleteListener(){
        m_player.setOnSeekCompleteListener(mp -> m_seeking_flag = false);
    }
}
