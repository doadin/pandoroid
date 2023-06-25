/* Pandoroid Radio - open source pandora.com client for android
 * Copyright (C) 2011  Andrew Regner <andrew@aregner.com>
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.pandoroid;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
//import android.graphics.BitmapFactory;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.Manifest;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.pandoroid.pandora.PandoraRadio;
import com.pandoroid.pandora.RPCException;
import com.pandoroid.pandora.Song;
import com.pandoroid.pandora.Station;
import com.pandoroid.pandora.SubscriberTypeException;
import com.pandoroid.playback.MediaPlaybackController;
import com.pandoroid.playback.OnNewSongListener;
import com.pandoroid.playback.OnPlaybackContinuedListener;
import com.pandoroid.playback.OnPlaybackHaltedListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

/**
 * Description: Someone really needs to give this class some loving, document
 *  it up, organize it, and make it thread safe.
 */
public class PandoraRadioService extends Service {

    private static final int NOTIFICATION_SONG_PLAYING = 1;
    

    
    // tools this service uses
    private PandoraRadio m_pandora_remote;
    public MediaPlaybackController m_song_playback;
    public ImageDownloader image_downloader;
    
    private TelephonyManager telephonyManager;
    private ConnectivityManager connectivity_manager;
    private MusicIntentReceiver m_music_intent_receiver;
    private SharedPreferences m_prefs;
    
    // tracking/organizing what we are doing
    private Station m_current_station;
    private String m_audio_quality;
    private boolean m_paused;
    
    //We'll use this for now as the database implementation is garbage.
    private ArrayList<Station> m_stations; 
    private final HashMap<Class<?>,Object> listeners = new HashMap<>();

    protected PandoraDB db;

    
    // static usefullness
    private static Object lock = new Object();
    private static Object pandora_lock = new Object();

    private MediaSessionCompat.Token mSessionToken;
    public static final String ACTION_PAUSE = "action_pause";
    public static final String ACTION_PLAY = "action_play";
    public static final String ACTION_NEXT = "action_next";
    private MediaSession mSession;
    private PowerManager.WakeLock mWakeLock;

    //public MediaMetadataCompat updateMetadata() {
    //    Song tmp_song;
    //    tmp_song = m_song_playback.getSong();
    //    Log.i("Pandoroid", "setNotification:" + tmp_song.getTitle() + " By " + tmp_song.getArtist());
    //    MediaMetadataCompat.Builder metaData = new MediaMetadataCompat.Builder()
    //    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, tmp_song.getTitle())
    //    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, tmp_song.getArtist())
    //    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, tmp_song.getAlbum())
    //    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, tmp_song.getArtist());
    //    //.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, tmp_song.get)
    //    //.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, tmp_song.)
//
    //    //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
    //    //    metaData.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, getCount());
    //    //}
    //    //mSession.setMetadata(metaData);
    //    return metaData.build();
    //    this.
    //}

    //Taken straight from the Android service reference
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class PandoraRadioBinder extends Binder {
        PandoraRadioService getService() {
            return PandoraRadioService.this;
        }
    }
    
    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    // This is the object that receives interactions from clients. 
    private final IBinder mBinder = new PandoraRadioBinder();
    //End service reference
    
    
    @Override
    public void onCreate() {
        m_paused = false;
        m_pandora_remote = new PandoraRadio();
        image_downloader = new ImageDownloader();
        m_stations = new ArrayList<>();


        connectivity_manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        m_prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        //m_player.setAudioStreamType(AudioManager.STREAM_MUSIC);

        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Pandoroid:PandoraRadioServiceWakelock");
        try {
            if (!mWakeLock.isHeld()) {
                Log.i("Pandoroid", "onCreate Acquiring WakeLock");
                mWakeLock.acquire();
            }
        } catch (final Exception e) {
            Log.e("Pandoroid", "Error Acquiring WakeLock");
        }

        //if(ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            //if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            //    TelephonyManager telephony = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            //    TelephonyCallback MyPhoneStateListener = new TelephonyCallback(){
            //        @Override
            //        public void callStateChanged(int state) {
            //            int myState=state;
            //        }
            //    };
            //    telephony.registerTelephonyCallback(this.getMainExecutor(), MyPhoneStateListener);
            //}
        //}

        // Register the listener with the telephony manager
        //telephonyManager.listen(new PhoneStateListener() {
        //    boolean pausedForRing = false;
//
        //    //@Override
        //    public void onCallStateChanged(final int state) {
        //        Log.i("pandoroid telephony", "State changed: " + state);
        //        switch (state) {
//
        //            case TelephonyManager.CALL_STATE_IDLE:
        //                Log.d("DEBUG", "***********IDLE********");
        //                if (pausedForRing && m_song_playback != null) {
        //                    if (m_prefs.getBoolean("behave_resumeOnHangup", true)) {
        //                        if (m_song_playback != null && !m_paused) {
        //                            m_song_playback.play();
        //                        }
        //                    }
        //                }
//
        //                pausedForRing = false;
        //                break;
//
        //            case TelephonyManager.CALL_STATE_OFFHOOK:
        //            case TelephonyManager.CALL_STATE_RINGING:
        //                Log.d("DEBUG", "***********RINGING********");
        //                if (m_song_playback != null) {
        //                    m_song_playback.pause();
        //                }
//
        //                pausedForRing = true;
        //                break;
        //        }
        //    }
        //}, PhoneStateListener.LISTEN_CALL_STATE);
        m_music_intent_receiver = new MusicIntentReceiver();
        this.registerReceiver(m_music_intent_receiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        return START_STICKY;
    }
    
    public void onDestroy() {
        if (m_song_playback != null){
            m_song_playback.stop();
        }
        this.unregisterReceiver(m_music_intent_receiver);
        stopForeground(true);
        final NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(1);
        if (mWakeLock.isHeld()) {
            Log.i("Pandoroid", "onTaskRemoved WakeLock is held, releasing");
            mWakeLock.release();
        }
        final Intent resultIntent = new Intent(this, PandoraRadioService.class);
        onTaskRemoved(resultIntent);
        return;
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        final NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(1);
        if (mWakeLock.isHeld()) {
            Log.i("Pandoroid", "onTaskRemoved WakeLock is held, releasing");
            mWakeLock.release();
        }
    }
    
    public Song getCurrentSong() throws Exception{
        return m_song_playback.getSong();
    }
    
    public Station getCurrentStation() {
        return m_current_station;
    }
    
    public ArrayList<Station> getStations(){
        return m_stations;
    }
    
    public boolean isPartnerAuthorized(){
        return m_pandora_remote.isPartnerAuthorized();
    }
    
    public boolean isUserAuthorized(){
        return m_pandora_remote.isUserAuthorized();
    }
    
    public void runPartnerLogin(final boolean pandora_one_subscriber_flag) throws RPCException, 
                                                                            IOException,
                                                                            Exception{
        Log.i("Pandoroid", 
              "Running a partner login for a " +
              (pandora_one_subscriber_flag ? "Pandora One": "standard Pandora") +
                  " subscriber.");
        m_pandora_remote.runPartnerLogin(pandora_one_subscriber_flag);
    }
    
    public void runUserLogin(final String user, final String password) throws RPCException,
                                                                  IOException, 
                                                                  Exception{
        boolean needs_partner_login = false;
        boolean is_pandora_one_user = m_pandora_remote.isPandoraOneCredentials();
        boolean failure_but_not_epic_failure = true;
        while (failure_but_not_epic_failure){
            try{
                if (needs_partner_login){
                    m_prefs.edit().putBoolean("pandora_one_flag", is_pandora_one_user).apply();
                    runPartnerLogin(is_pandora_one_user);
                    needs_partner_login = false;
                }
                
                if (is_pandora_one_user){
                    m_audio_quality = PandoraRadio.AAC_64;
                }
                else {
                    m_audio_quality = PandoraRadio.AAC_32;
                }
                Log.i("Pandoroid", "Running a user login.");
                m_pandora_remote.connect(user, password);
                failure_but_not_epic_failure = false; //Or any type of fail for that matter.
            }
            catch (final SubscriberTypeException e){
                needs_partner_login = true;
                is_pandora_one_user = e.is_pandora_one;
                Log.i("Pandoroid", 
                      "Wrong subscriber type. User is a " +
                      (is_pandora_one_user? "Pandora One": "standard Pandora") +
                      " subscriber.");
            }
            catch (final RPCException e){
                if (e.code == RPCException.INVALID_AUTH_TOKEN){
                    needs_partner_login = true;
                    Log.e("Pandoroid", e.getMessage());
                }
                else{
                    throw e;
                }
            }
        }
    }
    
    public void setListener(final Class<?> klass, final Object listener) {
        listeners.put(klass, listener);
    }
    public Activity getActivity(Context context)
    {
        if (context == null)
        {
            return null;
        }
        else if (context instanceof ContextWrapper)
        {
            if (context instanceof Activity)
            {
                return (Activity) context;
            }
            else
            {
                return getActivity(((ContextWrapper) context).getBaseContext());
            }
        }

        return null;
    }
    public void setNotification() {
        try {
            final int mNotificationId = 0;
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(this), new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
                }
            }

            // The id of the group.
            String groupId = "pandoroid_01";
            // The user-visible name of the group.
            CharSequence groupName = "Pandoroid Notification Group";
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(new NotificationChannel("default", groupName, NotificationManager.IMPORTANCE_LOW));
            }

            Song tmp_song;
            tmp_song = m_song_playback.getSong();
            Log.i("Pandoroid", "setNotification:" + " By Station ID " + tmp_song.getStationId());
            Log.i("Pandoroid", "setNotification:" + tmp_song.getTitle() + " By " + tmp_song.getArtist());
            final NotificationCompat.Builder mBuilder =
                    (NotificationCompat.Builder) new NotificationCompat.Builder(Pandoroid.getContext(), "default");
            //Bitmap largeIcon = BitmapFactory.decodeResource(getResources(), R.id.player_image);
            //Bitmap largeIcon = ImageDownloader.getBitmapFromCachee(tmp_song.getAlbumCoverUrl());
            Log.i("Pandoroid", "setNotification:" + " By " + tmp_song.getAlbumCoverUrl());
            if (m_paused)
                mBuilder
                        //.setLargeIcon(BitmapFactory.decodeStream(image_downloader)
                        //.setLargeIcon(largeIcon)
                        .setSmallIcon(R.drawable.notification_icon)
                        .setOngoing(false)
                        .setShowWhen(false)
                        //.addAction(android.R.drawable.ic_media_play, "Play", pendingIntentYes) // #0
                        .addAction(generateAction(android.R.drawable.ic_media_play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY))
                        //.addAction(android.R.drawable.ic_media_next, "next", pendingIntentYes) // #1
                        .addAction(generateAction(android.R.drawable.ic_media_next, "Next", KeyEvent.KEYCODE_MEDIA_NEXT))
                        .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                                .setShowActionsInCompactView(0,1 /* #1: pause button */)
                                .setMediaSession(mSessionToken)
                                .setShowCancelButton(true)
                                .setCancelButtonIntent(generateActionIntent(getApplicationContext(), KeyEvent.KEYCODE_MEDIA_STOP))
                        )
                        .setContentText(tmp_song.getArtist())
                        .setContentInfo(tmp_song.getAlbum())
                        .setContentTitle(tmp_song.getTitle())
                        //.setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(),
                        //        PlaybackStateCompat.ACTION_STOP))
                        //.addAction(new NotificationCompat.Action(
                        //        android.R.drawable.ic_media_play, "Play",
                        //        MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(),
                        //                PlaybackStateCompat.ACTION_PLAY_PAUSE)))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            if (!m_paused)
                mBuilder
                        //.setLargeIcon(m_service.image_downloader.download(song.getAlbumCoverUrl(), image))
                        //.setLargeIcon(largeIcon)
                        .setSmallIcon(R.drawable.notification_icon)
                        .setOngoing(false)
                        .setShowWhen(false)
                        //.addAction(android.R.drawable.ic_media_pause, "Pause", pendingIntentNo) // #0
                        .addAction(generateAction(android.R.drawable.ic_media_pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE))
                        //.addAction(android.R.drawable.ic_media_next, "next", pendingIntentYes) // #1
                        .addAction(generateAction(android.R.drawable.ic_media_next, "Next", KeyEvent.KEYCODE_MEDIA_NEXT))
                        .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                                .setShowActionsInCompactView(0,1 /* #1: pause button */)
                                .setMediaSession(mSessionToken))
                        .setContentText(tmp_song.getArtist())
                        .setContentInfo(tmp_song.getAlbum())
                        .setContentTitle(tmp_song.getTitle())
                        //.setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(),
                        //        PlaybackStateCompat.ACTION_STOP))
                        //.addAction(new NotificationCompat.Action(
                        //        android.R.drawable.ic_media_pause, "Pause",
                        //        MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(),
                        //                PlaybackStateCompat.ACTION_PLAY_PAUSE)))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            notificationManager.notify(mNotificationId, mBuilder.build());
        } catch (final Exception e) {
            Log.i("Pandoroid", "Error Setting Notification");
        }
        //}else{
        //    NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        //    mNotifyMgr.cancel(001);
    }

    private NotificationCompat.Action generateAction( final int icon, final String title, final int mediaKeyEvent) {
        final PendingIntent pendingIntent = generateActionIntent(getApplicationContext(), mediaKeyEvent);
        return new NotificationCompat.Action.Builder( icon, title, pendingIntent ).build();
    }


    private static PendingIntent generateActionIntent(final Context context, final int mediaKeyEvent)
    {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setPackage(context.getPackageName());
        intent.putExtra(Intent.EXTRA_KEY_EVENT,
                new KeyEvent(KeyEvent.ACTION_DOWN, mediaKeyEvent));
        return PendingIntent.getBroadcast(context, mediaKeyEvent, intent, FLAG_IMMUTABLE);
    }

    public void signOut() {
        if(m_song_playback != null) {
            stopForeground(true);
            m_song_playback.stop();
        }

        if(m_pandora_remote != null) {
            m_pandora_remote.disconnect();
        }
        
        if (m_current_station != null){
            m_current_station = null;
        }
    }
    
    public boolean isAlive() {
        return m_pandora_remote.isAlive();
    }
    
    public void updateStations() throws RPCException,
                                        IOException, 
                                        Exception {
        m_stations = m_pandora_remote.getStations();
    }
    
    public boolean setCurrentStation(final String station_id) {
        for(int i = 0; i < m_stations.size(); ++i){
            final Station tmp_station = m_stations.get(i);
            Log.i("Pandoroid", "Station" + m_stations.get(i));
            if (tmp_station.compareTo(station_id) == 0){
                m_current_station = tmp_station;
                stopForeground(true);
                setPlaybackController();
                m_prefs.edit().putString("lastStationId", station_id).apply();
                return true;
            }
        }
        
        return false;
    }
    

    
    public boolean playPause(){
        if (m_song_playback != null){
            if (!m_paused){
                pause();
                return false;
            }
            else{
                play();
                return true;
            }
        }
        return false;
    }

    private void play() {
        m_paused = false;
        m_song_playback.play();
        setNotification();
        if (mWakeLock.isHeld()) {
            Log.i("Pandoroid", "WakeLock Already Active");
        } else {
            mWakeLock.acquire();
            Log.i("Pandoroid", "WakeLock Acquired");
        }
    }
    
    private void pause() {
        m_song_playback.pause();            
        m_paused = true;
        //stopForeground(true);
        setNotification();
        if (mWakeLock.isHeld()) {
            Log.i("Pandoroid", "WakeLock Active...releasing");
            mWakeLock.release();
        }
    }
    
    public void rate(final String rating) {
        if(rating == PandoroidPlayer.RATING_NONE) {
            // cannot set rating to none
            return;
        }
        
        (new RateTask()).execute(rating);
    }
    
    public void resetPlaybackListeners(){
        if (m_song_playback != null){
            try {
                m_song_playback.setOnNewSongListener(
                        (OnNewSongListener) listeners.get(OnNewSongListener.class)
                                                  );
                m_song_playback.setOnPlaybackContinuedListener(
                        (OnPlaybackContinuedListener) listeners.get(OnPlaybackContinuedListener.class)
                                                               );
                m_song_playback.setOnPlaybackHaltedListener(
                        (OnPlaybackHaltedListener) listeners.get(OnPlaybackHaltedListener.class)
                                                           );
                m_song_playback.setOnErrorListener(new PlaybackOnErrorListener());

            } 
            catch (final Exception e) {
                Log.e("Pandoroid", e.getMessage(), e);
            }
        }
    }
    
    private void setPlaybackController(){
        try{    
            if (m_song_playback == null){       
                m_song_playback = new MediaPlaybackController(m_current_station.getStationIdToken(),
                                                            PandoraRadio.AAC_32,
                                                            m_audio_quality,
                                                            m_pandora_remote,
                                                            connectivity_manager);

                
            }
            else{
                m_song_playback.reset(m_current_station.getStationIdToken(), m_pandora_remote);
                
            }
            resetPlaybackListeners();
        } 
        catch (final Exception e) {
            Log.e("Pandoroid", e.getMessage(), e);
            m_song_playback = null;
        }
    }
    
    public void skip(){
        m_song_playback.skip();
    }
    
    public void startPlayback(){        
        if (m_song_playback != null){
            final Thread t = new Thread(m_song_playback);
            t.start();
        }       
    }
    
    public void stopPlayback(){
        if (m_song_playback != null){
            m_song_playback.stop();
        }
        stopForeground(true);
    }
    
    public abstract static class OnInvalidAuthListener{
        public abstract void onInvalidAuth();
    }
    
    public class PlaybackOnErrorListener extends com.pandoroid.playback.OnErrorListener{
        public void onError(final String error_message, 
                            final Throwable e, 
                            final boolean remote_error_flag,
                            final int rpc_error_code){
            if (remote_error_flag){
                if (rpc_error_code == RPCException.INVALID_AUTH_TOKEN){
                    m_pandora_remote.disconnect();
                    final OnInvalidAuthListener 
                        listener = (OnInvalidAuthListener) listeners.get(OnInvalidAuthListener.class);
                    if (listener != null){
                        listener.onInvalidAuth();
                    }
                }
            }           
        }
    }
    public class AppConstant
    {
        public static final String YES_ACTION = "YES_ACTION";
        public static final String NO_ACTION = "NO_ACTION";
    }

    public class MusicIntentReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(final Context ctx, final Intent intent){
            final String action = intent.getAction();
            if (AppConstant.YES_ACTION.equals(action)) {
                Log.i("Pandoroid", "Play Pressed");
                play();
            }
            if (AppConstant.NO_ACTION.equals(action)) {
                Log.i("Pandoroid", "Pause Pressed");
                pause();
            }
        }
    }
    
    public class RateTask extends AsyncTask<String, Void, Void>{
        public void onPreExecute(){
            try {
                this.m_song = m_song_playback.getSong();
            } catch (final Exception e) {
                Log.e("Pandoroid", "No song to rate.");
            }
        }
        public Void doInBackground(final String... ratings){
            if (m_song != null){
                final String rating = ratings[0];             
                final boolean rating_bool = rating.equals(PandoroidPlayer.RATING_LOVE) ? true : false;
                try {
                    m_pandora_remote.rate(this.m_song, rating_bool);
                    Log.i("Pandoroid", "A " + 
                                       (rating_bool ? "thumbs up" : "thumbs down") +
                                       " rating for the song " +
                                       this.m_song.getTitle() +
                                       " was successfully sent.");
                //We'll have to do more later, but this works for now.
//              } catch (HttpResponseException e) {
//              } catch (RPCException e) {
//              } catch (IOException e) {
                } 
                catch (final Exception e) {
                    Log.e("Pandoroid", "Exception while sending a song rating.", e);
                }
            }
            return null;
        }
        
        private Song m_song;
    }

    //public abstract static class ServerAsyncTask<Params>{
    //    protected static final int ERROR_UNSUPPORTED_API = 0;
    //    protected static final int ERROR_NETWORK = 1;
    //    protected static final int ERROR_UNKNOWN = 2;
    //    protected static final int ERROR_REMOTE_SERVER = 3;
    //    public static final String TAG = "AsyncTaskRunner";
//
    //    private static final Executor THREAD_POOL_EXECUTOR =
    //            new ThreadPoolExecutor(5, 128, 1,
    //                    TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
//
    //    private final Handler mHandler = new Handler(Looper.getMainLooper());
    //    private boolean mIsInterrupted = false;
//
    //    protected void onPreExecute(){}
    //    protected abstract Integer doInBackground(Params... params);
    //    protected abstract void onPostExecute();
    //    protected abstract void quit();
//
    //    protected abstract void reportAction();
    //    protected abstract void retryAction();
//
    //    protected abstract void showAlert(AlertDialog new_alert);
    //    protected AlertDialog.Builder buildErrorDialog(final int error, final Context context) {
    //        final AlertDialog.Builder alert_builder = new AlertDialog.Builder(context);
    //        alert_builder.setCancelable(false);
    //        alert_builder.setPositiveButton("Quit",
    //                (dialog, which) -> quit());
//
    //        switch (error) {
    //            case ERROR_NETWORK:
    //            case ERROR_UNKNOWN:
    //            case ERROR_REMOTE_SERVER:
    //                alert_builder.setNeutralButton("Retry",
    //                        (dialog, which) -> retryAction());
    //        }
//
    //        switch (error) {
    //            case ERROR_UNSUPPORTED_API:
    //                alert_builder.setNeutralButton("Report",
    //                        (dialog, which) -> reportAction());
    //                alert_builder.setMessage("Please update the app. "
    //                        + "The current Pandora API is unsupported.");
    //                break;
    //            case ERROR_NETWORK:
    //                alert_builder.setMessage("A network error has occurred.");
    //                break;
    //            case ERROR_UNKNOWN:
    //                alert_builder.setMessage("An unknown error has occurred.");
    //                break;
    //            case ERROR_REMOTE_SERVER:
    //                alert_builder
    //                        .setMessage("Pandora's servers are having troubles. "
    //                                + "Try again later.");
    //                break;
    //        }
//
    //        return alert_builder;
    //    }
//
    //    /**
    //     * Description: A test to show off different exceptions.
    //     * @throws RPCException
    //     * @throws IOException
    //     * @throws Exception
    //     */
    //    public void exceptionTest() throws RPCException,
    //            IOException, Exception {
    //        switch (1) {
    //            case 0:
    //                throw new RPCException(
    //                        RPCException.API_VERSION_NOT_SUPPORTED,
    //                        "Invalid API test");
    //            case 2:
    //                throw new IOException("IO exception test");
    //            case 3:
    //                throw new Exception("Generic exception test");
    //        }
    //    }
//
    //    /**
    //     * Description: A handler that must be called when an RPCException
    //     *  has occurred.
    //     * @param e
    //     * @return
    //     */
    //    protected int rpcExceptionHandler(final RPCException e) {
    //        int success_flag = ERROR_UNKNOWN;
    //        if (RPCException.URL_PARAM_MISSING_METHOD <= e.code
    //                && e.code <= RPCException.API_VERSION_NOT_SUPPORTED) {
    //            success_flag = ERROR_UNSUPPORTED_API;
    //        } else if (e.code == RPCException.INTERNAL
    //                || e.code == RPCException.MAINTENANCE_MODE) {
    //            success_flag = ERROR_REMOTE_SERVER;
    //        }
//
    //        return success_flag;
    //    }
//
    //    /**
    //     * Description: A handler that must be called when an IOException
    //     *  has been encountered.
    //     * @return
    //     */
    //    protected int ioExceptionHandler() {
    //        return ERROR_NETWORK;
    //    }
//
    //    /**
    //     * Description: A handler that must be called when a generic Exception has
    //     *  been encountered.
    //     * @return
    //     */
    //    protected int generalExceptionHandler() {
    //        return ERROR_UNKNOWN;
    //    }
    //    protected boolean getStatus(){
    //        return true;
    //    }
    //    protected void onCancelled() {}
//
    //    @SafeVarargs
    //    public final void execute(Params... params) {
    //        THREAD_POOL_EXECUTOR.execute(() -> {
    //            try {
    //                checkInterrupted();
    //                mHandler.post(this::onPreExecute);
//
    //                checkInterrupted();
    //                doInBackground(params);
//
    //                checkInterrupted();
    //                onPostExecute();
    //                //mHandler.post(this::onPostExecute);
    //            } catch (InterruptedException ex) {
    //                mHandler.post(this::onCancelled);
    //            } catch (Exception ex) {
    //                Log.e(TAG, "executeAsync: " + ex.getMessage() + "\n");
    //            }
    //        });
    //    }
//
    //    private void checkInterrupted() throws InterruptedException {
    //        if (isInterrupted()){
    //            throw new InterruptedException();
    //        }
    //    }
//
    //    public void cancel(boolean mayInterruptIfRunning){
    //        setInterrupted(mayInterruptIfRunning);
    //    }
//
    //    public boolean isInterrupted() {
    //        return mIsInterrupted;
    //    }
//
    //    public void setInterrupted(boolean interrupted) {
    //        mIsInterrupted = interrupted;
    //    }
    //}
    
    /**
     * Description: An abstract asynchronous task for doing a generic login. 
     * @param <Params> -Parameters specific for the doInBackground() execution.
     */
    public abstract static class ServerAsyncTask<Params> extends AsyncTask<Params,
                                                                           Void,
                                                                           Integer> {
        protected static final int ERROR_UNSUPPORTED_API = 0;
        protected static final int ERROR_NETWORK = 1;
        protected static final int ERROR_UNKNOWN = 2;
        protected static final int ERROR_REMOTE_SERVER = 3;


        /**
         * Description: The required AsyncTask.doInBackground() function.
         */
        protected abstract Integer doInBackground(Params... args);

        protected abstract void quit();

        protected abstract void reportAction();

        /**
         * Description: A function that specifies the action to be taken
         *  when a user clicks the retry button in an alert dialog.
         */
        protected abstract void retryAction();

        protected abstract void showAlert(AlertDialog new_alert);

        /**
         * Description: Builds an alert dialog necessary for all login tasks.
         * @param error -The error code.
         * @return An alert dialog builder that can be converted into an alert
         *  dialog.
         */
        protected AlertDialog.Builder buildErrorDialog(final int error, final Context context) {
            final AlertDialog.Builder alert_builder = new AlertDialog.Builder(context);
            alert_builder.setCancelable(false);
            alert_builder.setPositiveButton("Quit",
                    (dialog, which) -> quit());

            switch (error) {
            case ERROR_NETWORK:
            case ERROR_UNKNOWN:
            case ERROR_REMOTE_SERVER:
                alert_builder.setNeutralButton("Retry",
                        (dialog, which) -> retryAction());
            }

            switch (error) {
            case ERROR_UNSUPPORTED_API:
                alert_builder.setNeutralButton("Report",
                        (dialog, which) -> reportAction());
                alert_builder.setMessage("Please update the app. "
                        + "The current Pandora API is unsupported.");
                break;
            case ERROR_NETWORK:
                alert_builder.setMessage("A network error has occurred.");
                break;
            case ERROR_UNKNOWN:
                alert_builder.setMessage("An unknown error has occurred.");
                break;
            case ERROR_REMOTE_SERVER:
                alert_builder
                        .setMessage("Pandora's servers are having troubles. "
                                + "Try again later.");
                break;
            }

            return alert_builder;
        }

        /**
         * Description: A test to show off different exceptions.
         * @throws RPCException
         * @throws IOException
         * @throws Exception
         */
        public void exceptionTest() throws RPCException,
                IOException, Exception {
            switch (1) {
                case 0:
                    throw new RPCException(
                            RPCException.API_VERSION_NOT_SUPPORTED,
                            "Invalid API test");
                case 2:
                    throw new IOException("IO exception test");
                case 3:
                    throw new Exception("Generic exception test");
            }
        }

        /**
         * Description: A handler that must be called when an RPCException
         *  has occurred.
         * @param e
         * @return
         */
        protected int rpcExceptionHandler(final RPCException e) {
            int success_flag = ERROR_UNKNOWN;
            if (RPCException.URL_PARAM_MISSING_METHOD <= e.code
                    && e.code <= RPCException.API_VERSION_NOT_SUPPORTED) {
                success_flag = ERROR_UNSUPPORTED_API;
            } else if (e.code == RPCException.INTERNAL
                    || e.code == RPCException.MAINTENANCE_MODE) {
                success_flag = ERROR_REMOTE_SERVER;
            }

            return success_flag;
        }

        /**
         * Description: A handler that must be called when an IOException
         *  has been encountered.
         * @return
         */
        protected int ioExceptionHandler() {
            return ERROR_NETWORK;
        }

        /**
         * Description: A handler that must be called when a generic Exception has
         *  been encountered.
         * @return
         */
        protected int generalExceptionHandler() {
            return ERROR_UNKNOWN;
        }
    }
}
