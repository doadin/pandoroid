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

import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.pandoroid.PandoraRadioService.ServerAsyncTask;
import com.pandoroid.pandora.RPCException;
import com.pandoroid.pandora.Song;
import com.pandoroid.playback.MediaPlaybackController;
import com.pandoroid.playback.OnNewSongListener;
import com.pandoroid.playback.OnPlaybackContinuedListener;
import com.pandoroid.playback.OnPlaybackHaltedListener;

import java.io.IOException;
import java.util.Objects;

import static androidx.appcompat.R.style.Base_Theme_AppCompat_Light;

public class PandoroidPlayer extends AppCompatActivity {

//  public static final int REQUIRE_SELECT_STATION = 0x10;
//  public static final int REQUIRE_LOGIN_CREDS = 0x20;
    public static final String RATING_BAN = "ban";
    //public static final String RATING_TEMPBAN = "ban";
    public static final String RATING_LOVE = "love";
    public static final String RATING_NONE = null;

    // private static final String STATE_IMAGE = "imageDownloader";


    private static AlertDialog m_alert;
    private static boolean m_alert_active_flag = false;
    private static int m_song_halted_reason = MediaPlaybackController.HALT_STATE_CLEAR;
    private boolean m_is_bound;
    private SharedPreferences m_prefs;
    private PartnerLoginTask m_partner_login_task;
    private static boolean m_partner_login_finished_flag = true;
    private PandoraRadioService m_service;
    private RetrieveStationsTask m_retrieve_stations_task;
    private static boolean m_retrieve_stations_finished_flag = true;
    private UserLoginTask m_user_login_task;
    private static boolean m_user_login_finished_flag = true;
    private static ProgressDialog m_waiting;

    /*
     * Activity start and end specific stuff
     */
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme(Base_Theme_AppCompat_Light);
        setContentView(R.layout.player);

        m_prefs = PreferenceManager.getDefaultSharedPreferences(this);
        doBindService();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (m_is_bound) {
            startup();
        } else {
            m_waiting = ProgressDialog.show(PandoroidPlayer.this, "",
                                          getString(R.string.loading));
        }
    }

    protected void onPause() {
        super.onPause();
    }

    protected void onStop() {
        super.onStop();
        if (m_alert_active_flag) {
            m_alert.dismiss();
        }
        dismissWaiting();
    }

    protected void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    /* End Activity */

    /*
     * Service connection specific stuff.
     */
    private final ServiceConnection m_connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service. Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            m_service = ((PandoraRadioService.PandoraRadioBinder) service)
                    .getService();
            m_is_bound = true;
            startup();

            m_service.setListener(OnNewSongListener.class, new OnNewSongListener() {
                public void onNewSong(Song song) {
                    updateForNewSong(song);
                }
            });
            m_service.setListener(OnPlaybackContinuedListener.class,
                                  new OnPlaybackContinuedListener(){
                public void onPlaybackContinued(){
                    dismissSongHaltedProgress();
                }
            });
            m_service.setListener(OnPlaybackHaltedListener.class,
                                  new OnPlaybackHaltedListener(){
                public void onPlaybackHalted(int reason){
                    showSongHaltedProgress(reason);
                }
            });
            m_service.setListener(PandoraRadioService.OnInvalidAuthListener.class,
                                  new PandoraRadioService.OnInvalidAuthListener() {

                                    @Override
                                    public void onInvalidAuth() {
                                        partnerLogin();
                                    }
                                }
            );
            m_service.resetPlaybackListeners();
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            m_service = null;
            m_is_bound = false;
        }
    };

    void doBindService() {

        // This is the master service start
        startService(new Intent(this, PandoraRadioService.class));

        // Establish a connection with the service. We use an explicit
        // class name because we want a specific service implementation that
        // we know will be running in our own process (and thus won't be
        // supporting component replacement by other applications).
        bindService(new Intent(this, PandoraRadioService.class), m_connection,
                    Context.BIND_AUTO_CREATE);

    }

    void doUnbindService() {
        if (m_is_bound) {
            // Detach our existing connection.
            unbindService(m_connection);
            m_is_bound = false;
        }
    }
    /* End Service */


    public void controlButtonPressed(View button) {
        int id = button.getId();
        if (id == R.id.player_ban) {
            m_service.rate(RATING_BAN);
            Toast.makeText(getApplicationContext(),
                    getString(R.string.baned_song), Toast.LENGTH_SHORT).show();
            if (m_prefs.getBoolean("behave_nextOnBan", true)) {
                m_service.skip();
            }
        } else if (id == R.id.player_love) {
            m_service.rate(RATING_LOVE);
            Toast.makeText(getApplicationContext(),
                    getString(R.string.loved_song), Toast.LENGTH_SHORT).show();
        } else if (id == R.id.player_pause) {
            // Pause or play the song and update the button respectively
            ImageView pauseButton = (ImageView) findViewById(R.id.player_pause);
            if (m_service.playPause())
                pauseButton.setImageResource(android.R.drawable.ic_media_pause);
            else
                pauseButton.setImageResource(android.R.drawable.ic_media_play);
        } else if (id == R.id.player_next) {
            m_service.skip();
        }
    }

    /**
     * Description: Removes alert dialogs.
     */
    private void dismissAlert() {
        if (m_alert_active_flag) {
            m_alert.dismiss();
            m_alert_active_flag = false;
            m_alert = null;
        }
    }

    private void dismissSongHaltedProgress(){
        m_song_halted_reason = MediaPlaybackController.HALT_STATE_CLEAR;
        View progress = findViewById(R.id.progressUpdate);
        progress.setVisibility(View.INVISIBLE);
    }

    /**
     * Description: Removes waiting prompts.
     */
    private static void dismissWaiting() {
        if (m_waiting != null && m_waiting.isShowing()) {
            m_waiting.dismiss();
            m_waiting = null;
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        SubMenu sub = menu.addSubMenu("Options");
        sub.add(0, R.id.menu_stations, Menu.NONE, R.string.menu_stations);
        sub.add(0, R.id.menu_settings, Menu.NONE, R.string.menu_settings);
        sub.add(0, R.id.menu_logout, Menu.NONE, R.string.menu_logout);
        sub.add(0, R.id.menu_about, Menu.NONE, R.string.menu_about);

        MenuItem subMenu = sub.getItem();
        subMenu.setIcon(android.R.drawable.ic_menu_preferences);
        subMenu.setShowAsAction(2);
        return super.onCreateOptionsMenu(menu);
    }


    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_stations) {
            spawnStationActivity();
            return true;
        } else if (itemId == R.id.menu_logout) {
            m_service.signOut();
            SharedPreferences.Editor prefs_edit = m_prefs.edit();
            prefs_edit.remove("pandora_username");
            prefs_edit.remove("pandora_password");
            prefs_edit.remove("lastStationId");
            prefs_edit.remove("pandora_one_flag");
            prefs_edit.apply();
            dismissSongHaltedProgress();
            userLogin();
            return true;
        } else if (itemId == R.id.menu_settings) {
            startActivity(new Intent(getApplicationContext(),
                    PandoroidSettings.class));
            return true;
        } else if (itemId == R.id.menu_about) {
            startActivity(new Intent(getApplicationContext(), AboutDialog.class));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Description: Executes a PartnerLoginTask.
     */
    private void partnerLogin() {
        if (m_partner_login_finished_flag) {
            m_partner_login_finished_flag = false;
            m_partner_login_task = new PartnerLoginTask();
            boolean pandora_one_flag = m_prefs.getBoolean("pandora_one_flag", false);
            m_partner_login_task.execute(pandora_one_flag);
        }
        else{
            showWaiting("Authorizing the app...");
        }
    }

    /**
     * Description: Closes down the app as much as is possible with android.
     */
    private void quit() {
        doUnbindService();
        stopService(new Intent(PandoroidPlayer.this, PandoraRadioService.class));
        m_alert_active_flag = false;
        finish();
    }

    /**
     * Description: Executes the RetrieveStationsTask.
     */
    private void setStation(){
        if (m_retrieve_stations_finished_flag) {
            m_retrieve_stations_finished_flag = false;
            m_retrieve_stations_task = new RetrieveStationsTask();
            m_retrieve_stations_task.execute();
        }
        else{
            showWaiting("Acquiring a station...");
        }
    }

    /**
     * Description: Shows an alert with the specified alert.
     * @param new_alert -The AlertDialog to show.
     */
    private void showAlert(AlertDialog new_alert) {
        dismissAlert();
        m_alert = new_alert;
        m_alert.show();
        m_alert_active_flag = true;
    }

    private void showSongHaltedProgress(int reason){
        m_song_halted_reason = reason;
        View progress = findViewById(R.id.progressUpdate);
        TextView view_text = (TextView) findViewById(R.id.progressText);

        int reason_str;
        switch(reason){
            case MediaPlaybackController.HALT_STATE_NO_NETWORK:
                reason_str = R.string.no_network;
                break;
            case MediaPlaybackController.HALT_STATE_NO_INTERNET:
                reason_str = R.string.no_internet;
                break;
            case MediaPlaybackController.HALT_STATE_BUFFERING:
                reason_str = R.string.buffering;
                break;
            case MediaPlaybackController.HALT_STATE_PREPARING:
                reason_str = R.string.preparing;
                break;
            case MediaPlaybackController.HALT_STATE_NO_SONGS:
                reason_str = R.string.no_songs;
                break;
            default:
                reason_str = R.string.buffering;
        }

        view_text.setText(reason_str);
        view_text.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        progress.setVisibility(View.VISIBLE);

    }

    /**
     * Description: Shows a waiting/ProgressDialog with the specified message.
     * @param message -The String to show in the ProgressDialog.
     */
    private void showWaiting(String message) {
        dismissWaiting();
        m_waiting = ProgressDialog.show(this, "", message);
        m_waiting.show();
    }

    /**
     * Description: Refreshes the view with the specified song. If song is null
     *  it will reset to the default configuration.
     * @param song -The song to set the view to show.
     */
    private void songRefresh(Song song) {
        TextView top = (TextView) findViewById(R.id.player_topText);
        ImageView image = (ImageView) findViewById(R.id.player_image);

        if (song != null){
            Objects.requireNonNull(getSupportActionBar()).setTitle("" + song.getTitle());
            m_service.image_downloader.download(song.getAlbumCoverUrl(), image);
            top.setText(String.format("%s\n%s\n%s", "Artist: " + song.getArtist(), "Album: " + song.getAlbum(), "Station: " + m_service.getCurrentStation().getName()));
        }
        else{
            image.setImageResource(R.drawable.transparent);
            top.setText(R.string.loading);
            Objects.requireNonNull(getSupportActionBar()).setTitle(R.string.app_name);
        }

    }

    /**
     * Description: Starts a login activity.
     */
    private void spawnLoginActivity() {
        m_prefs.edit().remove("pandora_password").apply();
        startActivity(new Intent(this, PandoroidLogin.class));
    }

    /**
     * Description: Starts a new station selection activity.
     */
    private void spawnStationActivity(){
        startActivity(new Intent(this, PandoroidStationSelect.class));
    }

    /**
     * Description: This gets executed whenever the activity has to be
     *  restarted/resumed.
     */
    private void startup() {
        if (m_alert_active_flag) {
            m_alert.show();
        } else if (!m_service.isPartnerAuthorized()) {
            partnerLogin();
        } else if (!m_service.isUserAuthorized()) {
            userLogin();
        }
        else if (m_service.getCurrentStation() == null){
            setStation();
        }
        else {
            if (m_song_halted_reason != MediaPlaybackController.HALT_STATE_CLEAR){
                showSongHaltedProgress(m_song_halted_reason);
            }
            try{
                songRefresh(m_service.getCurrentSong());
            }
            catch(Exception e){
                songRefresh(null);
            }
            dismissWaiting();
        }
    }

    /**
     * Description: Updates a new song. This is mainly for OnNewSongListener
     *  purposes.
     * @param song -The new Song.
     */
    private void updateForNewSong(Song song) {
        m_service.setNotification();
        songRefresh(song);
    }

    /**
     * Description: Executes the UserLoginTask.
     */
    private void userLogin() {
        if (m_user_login_finished_flag) {
            String username = m_prefs.getString("pandora_username", null);
            String password = m_prefs.getString("pandora_password", null);

            if (username == null || password == null) {
                spawnLoginActivity();
            } else {
                m_user_login_finished_flag = false;
                m_user_login_task = new UserLoginTask();
                m_user_login_task.execute(username, password);
            }
        }
        else{
            showWaiting(getString(R.string.signing_in));
        }
    }

    /**
     * Description: An abstract class of ServerAsyncTask that's specific to this
     *  activity.
     * @param <Params> -Parameters specific to the doInBackground() execution.
     */
    private abstract class PandoroidPlayerServerTask<Params> extends ServerAsyncTask<Params>{

        protected AlertDialog.Builder buildErrorDialog(int error){
            return super.buildErrorDialog(error, PandoroidPlayer.this);
        }

        protected void quit(){
            PandoroidPlayer.this.quit();
        }

        protected void reportAction(){
            String issue_url = "https://github.com/doadin/pandoroid/issues";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(issue_url));
            startActivity(i);
        }

        protected void showAlert(AlertDialog alert){
            PandoroidPlayer.this.showAlert(alert);
        }
    }

    /**
     * Description: A login async task specific to authorizing the app itself
     *  for further communication with Pandora's servers.
     */
    private class PartnerLoginTask extends PandoroidPlayerServerTask<Boolean> {
        protected void onPreExecute() {
            showWaiting("Authorizing the app...");
        }

        protected Integer doInBackground(Boolean... subscriber_type) {
            Integer success_flag = -1;
            try {
                m_service.runPartnerLogin(subscriber_type[0]);
            } catch (RPCException e) {
                Log.e("Pandoroid", "Error running partner login.", e);
                if (e.code == RPCException.INVALID_PARTNER_CREDENTIALS) {
                    success_flag = ERROR_UNSUPPORTED_API;
                } else {
                    success_flag = rpcExceptionHandler(e);
                }
            } catch (IOException e) {
                Log.e("Pandoroid", "Error running partner login.", e);
                success_flag = ioExceptionHandler();
            } catch (Exception e) {
                Log.e("Pandoroid", "Error running partner login.", e);
                success_flag = generalExceptionHandler();
            }

            return success_flag;
        }

        protected void onPostExecute(Integer success_int) {
            int success = success_int;
            if (success >= 0) {
                AlertDialog.Builder alert_builder = buildErrorDialog(success);
                showAlert(alert_builder.create());
            } else {
                userLogin();
            }

            m_partner_login_finished_flag = true;
        }

        protected void retryAction() {
            m_alert_active_flag = false;
            partnerLogin();
        }

    }

    /**
     * Description: A retrieving stations asynchronous task.
     * @author Dylan Powers <dylan.kyle.powers@gmail.com>
     *
     */
    private class RetrieveStationsTask extends PandoroidPlayerServerTask<Void>{
        protected void onPreExecute(){
            showWaiting("Acquiring a station...");

        }

        protected Integer doInBackground(Void... massive_void){
            Integer success_flag = -1;
            try{
                m_service.updateStations();
            } catch (RPCException e) {
                Log.e("Pandoroid", "Error fetching stations.", e);
                success_flag = rpcExceptionHandler(e);
            } catch (IOException e) {
                Log.e("Pandoroid", "Error fetching stations.", e);
                success_flag = ioExceptionHandler();
            } catch (Exception e) {
                Log.e("Pandoroid", "Error fetching stations.", e);
                success_flag = generalExceptionHandler();
            }

            return success_flag;
        }

        protected void onPostExecute(Integer success_int){
            if (success_int < 0){
                dismissWaiting();
                String last_station_id = m_prefs.getString("lastStationId", null);
                if (last_station_id != null && m_service.setCurrentStation(last_station_id)){
                    m_service.startPlayback();
                }
                else{
                    spawnStationActivity();
                }
            }
            else {
                AlertDialog.Builder alert_builder = buildErrorDialog(success_int);
                showAlert(alert_builder.create());
            }

            m_retrieve_stations_finished_flag = true;
        }

        protected void retryAction(){
            m_alert_active_flag = false;
            setStation();
        }
    }

    /**
     * Description: A login async task specific to logging in a user.
     */
    private class UserLoginTask extends PandoroidPlayerServerTask<String> {
        private static final int ERROR_BAD_CREDENTIALS = 10;

        protected void onPreExecute() {
            showWaiting(getString(R.string.signing_in));
        }

        protected Integer doInBackground(String... strings) {
            Integer success_flag = -1;
            try {
                m_service.runUserLogin(strings[0], strings[1]);
            } catch (RPCException e) {
                Log.e("Pandoroid", "RPC error running user login. " + e.getMessage());
                if (e.code == RPCException.INVALID_USER_CREDENTIALS) {
                    success_flag = ERROR_BAD_CREDENTIALS;
                } else {
                    success_flag = rpcExceptionHandler(e);
                }
            } catch (IOException e) {
                Log.e("Pandoroid", "Error running user login.", e);
                success_flag = ioExceptionHandler();
            } catch (Exception e) {
                Log.e("Pandoroid", "Error running user login.", e);
                success_flag = generalExceptionHandler();
            }

            return success_flag;
        }

        protected void onPostExecute(Integer success_int) {
            int success = success_int;
            if (success == ERROR_BAD_CREDENTIALS) {
                int len = Toast.LENGTH_LONG;
                CharSequence text = "Wrong username and/or password!";
                Toast toasty = Toast.makeText(PandoroidPlayer.this, text, len);
                toasty.show();
                spawnLoginActivity();
            } else if (success >= 0) {
                AlertDialog.Builder alert_builder = buildErrorDialog(success);
                showAlert(alert_builder.create());
            } else {
                setStation();
            }

            m_user_login_finished_flag = true;
        }

        protected void retryAction() {
            m_alert_active_flag = false;
            userLogin();
        }
    }
}