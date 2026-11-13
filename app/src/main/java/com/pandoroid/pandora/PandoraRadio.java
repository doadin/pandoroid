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


package com.pandoroid.pandora;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.util.Log;

/**
 * Description: Uses Pandora's JSON v5 API. Documentation of the JSON API
 *  can be found here: <a href="http://pan-do-ra-api.wikia.com/wiki/Json/5">API</a>
 *  A network connection is required before any operation can/should take place.
 *  If a person is bored, and wants to work on something, they could split
 *  off the authentication component from this class while making both this
 *  and the authentication component inherit from the RPC class 
 *  (and also beef up the RPC class a bit). It would also be helpful
 *  to give this class a more meaningful name like PandoraAuthenticationRPC/
 *  PandoraPlaylistRPC/PandoraRPC....
 */
public class PandoraRadio {
    private static final String USER_AGENT = "com.pandoroid.pandora/0.4";
    
    /*
     * JSON API stuff
     */
    public static final String PROTOCOL_VERSION = "5";
    
    // REST API Endpoints
    private static final String ONE_RPC_URL = "www.pandora.com/api/v1/";
    private static final String ONE_DEVICE_ID = "D01";
    private static final String ONE_PARTNER_USERNAME = "pandora one";
    private static final String ONE_PARTNER_PASSWORD = "TVCKIBGS9AO9TSYLNNFUML0743LH82D";
    private static final String ONE_DECRYPT_CIPHER = "U#IO$RZPAB%VX2";
    private static final String ONE_ENCRYPT_CIPHER = "2%3WCL*JU$MP]4";
    
    private static final String AND_RPC_URL = "www.pandora.com/api/v1/";
    private static final String AND_DEVICE_ID = "android-generic";
    private static final String AND_PARTNER_USERNAME = "android";
    private static final String AND_PARTNER_PASSWORD = "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7";
    private static final String AND_DECRYPT_CIPHER = "R=U!LH$O2B#";
    private static final String AND_ENCRYPT_CIPHER = "6#26FRL$ZWD";
    
    private static final String MIME_TYPE = "text/plain"; //This probably isn't important
    /* END API */
    
    public static final long PLAYLIST_VALIDITY_TIME = 3600 * 3;
    public static final String DEFAULT_AUDIO_FORMAT = "aacplus";
    public static final long MIN_TIME_BETWEEN_PLAYLIST_CALLS = 30; //seconds
    
    //Audio quality strings
    public static final String AAC_32 = "HTTP_32_AACPLUS";
    public static final String AAC_64 = "HTTP_64_AACPLUS";
    public static final String MP3_128 = "HTTP_128_MP3";
    public static final String MP3_192 = "HTTP_192_MP3";
    //End Audio

    private RPC pandora_rpc;
    private final PartnerCredentials credentials;
    private Cipher blowfish_encode;
    private Cipher blowfish_decode;
    private String user_auth_token;
    private String listener_token;
    private String partner_auth_token;
    private long sync_time;
    private long sync_obtained_time;
    private long last_acquired_playlist_time;
    private String last_acquired_playlist_station;
    private final Map<String, String> standard_url_params;
    private ArrayList<Station> cached_stations;

    /**
     * 
     */
    public PandoraRadio() {
        standard_url_params = new HashMap<>();
        credentials = new PartnerCredentials();
        last_acquired_playlist_time = 0;
        last_acquired_playlist_station = "";

        pandora_rpc = new RPC(
                "www.pandora.com/api/v1/",
                MIME_TYPE,
                USER_AGENT);
    }

    public void initializeCsrf() throws IOException {
        pandora_rpc.initializeCsrf();
    }
    
    /**
     * Description: Compares two constant audio quality strings as defined in 
     *  PandoraRadio.
     * @param value -The value making the comparison.
     * @param relative_to -The value being compared to.
     * @return An integer that's positive when value is greater than relative_to,
     *  negative when value is less than relative_to, and 0 when they're 
     *  equivalent.
     * @throws Exception when the strings are invalid to be making comparisons
     *  against (One or both is not one of the defined constants).
     */
    public static int audioQualityCompare(String value, String relative_to) throws Exception{
        int str1_magnitude = getRelativeAudioQualityMagnitude(value);
        int str2_magnitude = getRelativeAudioQualityMagnitude(relative_to);
        if (str1_magnitude != -1 && str2_magnitude != -1){
            return (str1_magnitude - str2_magnitude);
        }       
        else{
            throw new Exception("Invalid strings to compare");
        }
    }
    
    /**
     * Description: Disabled
     */
    public void bookmarkArtist(Station station, Song song) {
        //Vector<Object> args = new Vector<Object>(1);
        //args.add(song.getArtistMusicId());
        
        //doCall("station.createArtistBookmark", args, null);
    }
    
    /**
     * Description: Disabled
     */
    public void bookmarkSong(Station station, Song song) {
//      Vector<Object> args = new Vector<Object>(2);
//      args.add(String.valueOf(station.getId())); 
//      args.add(song.getId());
        
        //doCall("station.createBookmark", args, null);
    }
    
    /**
     * Description: Keeps track of the remote server's sync time.
     * @return An integer for the current sync time.
     */
    private int calcSync(){
        return (int) (sync_time 
                      + ((System.currentTimeMillis() / 1000L) 
                         - this.sync_obtained_time));
    }
    
    /**
     * Description: Logs a user in.
     * @param user -The user's username.
     * @param password -The user's password.
     * @throws RPCException when a Pandora RPC error occurs.
     * @throws SubscriberTypeException when a Pandora user is identified as not
     *  being the expected subscriber type.
     * @throws IOException when Pandora's servers can't be reached.
     * @throws Exception when an improper call to connect has been made.
     */
    public void connect(String user, String password)
            throws Exception {

        Map<String, Object> request_args = new HashMap<>();

        request_args.put("username", user);
        request_args.put("password", password);
        request_args.put("keepLoggedIn", true);
        request_args.put("existingAuthToken", JSONObject.NULL);

        JSONObject result = this.doCall(
                "auth/login",
                request_args);
        Log.i(
                "Pandoroid",
                "LOGIN RESPONSE = " + result.toString()
        );

        this.user_auth_token =
                result.getString("authToken");
        this.listener_token =
                result.getString("listenerToken");
        clearStationsCache();
    }
    
    /**
     * Effectively logs a user off.
     */
    public void disconnect() {
        this.standard_url_params.remove("user_id");
        this.standard_url_params.put("auth_token", this.partner_auth_token);
        user_auth_token = null;
        last_acquired_playlist_station = "";
        last_acquired_playlist_time = 0;
        clearStationsCache();
    }
    
    /**
     * Description: Here we are making our remote procedure call specifically
     *  using Pandora's JSON protocol. This will return a JSONObject holding
     *  the contents of the results key in the response. If an error occurs
     *  (ie "stat":"fail") an exception with the message body will be thrown.
     * Caution: When debugging, be sure to note that most data that flows 
     *  through here is time-sensitive, and if stopped in the wrong places,
     *  it will cause "stat":"fail" responses from the remote server.
     *      private JSONObject doCall(String method,
     *                               Map<String, Object> json_params,
     *                               boolean http_secure_flag,
     *                               boolean encrypt,
     *                               Map<String, String> opt_url_params)
     */
    private JSONObject doCall(String endpoint,
                              Map<String, Object> jsonParams)
            throws Exception {

        JSONObject request =
                jsonParams != null
                        ? new JSONObject(jsonParams)
                        : new JSONObject();

        String responseString =
                pandora_rpc.callEndpoint(
                        endpoint,
                        request.toString(),
                        user_auth_token);
        Log.i("Pandoroid", "Endpoint = " + endpoint);

        return new JSONObject(responseString);
    }
    
    
    /**
     * Description: Gets a list of songs to be played. This function should not
     *  be called more frequently than MIN_TIME_BETWEEN_PLAYLIST_CALLS allows
     *  or an error will result.
     * @param station_token -A string representing the station's unique 
     *  identification token.
     * @throws RPCException when a Pandora RPC error has occurred.
     * @throws IOException when Pandora's remote servers could not be reached.
     * @throws Exception when an improper call has been made, or an unexpected 
     *  error occurs.
     */
    @SuppressWarnings("unchecked")
    public Vector<Song> getPlaylist(String stationId)
            throws RPCException,
            IOException,
            Exception {

        // Protect against excessive playlist requests
        if (!isGetPlaylistCallValid(stationId)) {
            throw new Exception("Playlist calls are too frequent");
        }

        if (!this.isUserAuthorized()) {
            throw new Exception(
                    "Improper call to getPlaylist(), " +
                            "the user has not been logged in yet.");
        }

        Vector<Song> songs = new Vector<>();

        Map<String, Object> requestArgs = new HashMap<>();

        requestArgs.put("stationId", stationId);
        requestArgs.put("isStationStart", true);
        requestArgs.put("fragmentRequestReason", "Normal");
        requestArgs.put("audioFormat", "aacplus");
        requestArgs.put("startingAtTrackId", JSONObject.NULL);

        JSONObject response =
                this.doCall(
                        "playlist/getFragment",
                        requestArgs);

        Log.i(
                "Pandoroid",
                "PLAYLIST RESPONSE:\n"
                        + response.toString(2));

        if (!response.has("tracks")) {
            Log.e(
                    "Pandoroid",
                    "No tracks array found.");
            return songs;
        }

        JSONArray tracks =
                response.getJSONArray("tracks");

        for (int i = 0; i < tracks.length(); i++) {

            JSONObject track = tracks.getJSONObject(i);

            try {

                Map<String, Object> songData =
                        JSONHelper.toMap(track);

                //
                // Convert new Pandora API fields to legacy Song fields
                //
                songData.put(
                        "songName",
                        track.optString("songTitle"));

                songData.put(
                        "albumName",
                        track.optString("albumTitle"));

                songData.put(
                        "trackGain",
                        track.optString("fileGain"));

                songData.put(
                        "songRating",
                        track.optInt("rating", 0));

                //
                // Album art
                //
                if (track.has("albumArt")) {

                    JSONArray albumArt =
                            track.getJSONArray("albumArt");

                    String bestArtUrl = null;
                    int bestSize = -1;

                    for (int j = 0; j < albumArt.length(); j++) {

                        JSONObject art =
                                albumArt.getJSONObject(j);

                        int size =
                                art.optInt("size", 0);

                        if (size > bestSize) {

                            bestSize = size;
                            bestArtUrl =
                                    art.optString("url");
                        }
                    }

                    if (bestArtUrl != null) {

                        songData.put(
                                "albumArtUrl",
                                bestArtUrl);
                    }
                }

                //
                // Build playable audio URL list
                //
                ArrayList<PandoraAudioUrl> audioUrls =
                        new ArrayList<>();

                String audioUrl =
                        track.optString(
                                "audioURL",
                                null);

                if (audioUrl != null &&
                        !audioUrl.isEmpty()) {

                    String quality =
                            track.optString(
                                    "audioEncoding",
                                    "aacplus");

                    int bitrate = 64;

                    audioUrls.add(
                            new PandoraAudioUrl(
                                    PandoraRadio.AAC_64,
                                    bitrate,
                                    audioUrl));
                }

                Song song =
                        new Song(
                                songData,
                                audioUrls);

                songs.add(song);

                Log.i(
                        "Pandoroid",
                        "Added song: "
                                + song.getTitle()
                                + " by "
                                + song.getArtist());

            } catch (Exception e) {

                Log.e(
                        "Pandoroid",
                        "Failed to parse track",
                        e);
            }
        }

        this.last_acquired_playlist_time =
                System.currentTimeMillis() / 1000L;

        this.last_acquired_playlist_station =
                stationId;

        return songs;
    }
    
    
    public static int getRelativeAudioQualityMagnitude(String quality_string){
        if (quality_string.compareTo(MP3_192) == 0){
            return 4;
        }
        if (quality_string.compareTo(MP3_128) == 0){
            return 3;
        }
        if (quality_string.compareTo(AAC_64) == 0){
            return 2;
        }
        if (quality_string.compareTo(AAC_32) == 0){
            return 1;
        }
        return -1;
    }
    
    
    /**
     * Clears the cached stations list.
     */
    public void clearStationsCache() {
        this.cached_stations = null;
    }

    /**
     * Stub method to create a station on Pandora.
     * Clears station cache so next fetch gets updated stations.
     * API implementation will be added later.
     */
    public void createStation(String musicId) throws Exception {
        // TODO: Implement API call to create station on Pandora server
        clearStationsCache();
    }

    /**
     * Stub method to delete a station on Pandora.
     * Clears station cache so next fetch gets updated stations.
     * API implementation will be added later.
     */
    public void deleteStation(String stationId) throws Exception {
        // TODO: Implement API call to delete station on Pandora server
        clearStationsCache();
    }

    /**
     * Description: Retrieves the available stations, using the cache if available.
     */
    public ArrayList<Station> getStations() throws RPCException,
                                                   IOException,
                                                   Exception {
        return getStations(false);
    }

    /**
     * Description: Retrieves the available stations, optionally forcing a refresh from Pandora API.
     * @param forceRefresh if true, bypasses cache and fetches fresh station list.
     */
    public ArrayList<Station> getStations(boolean forceRefresh) throws RPCException,
                                                                       IOException,
                                                                       Exception {
        if (!forceRefresh && this.cached_stations != null) {
            return this.cached_stations;
        }

        if (!this.isUserAuthorized()){
            throw new Exception("Improper call to getStations(), " +
                                        "the user has not been logged in yet.");
        }

        //Map<String, Object> request_args = new HashMap<>();
        //request_args.put("returnAllStations", "True");
        //JSONObject result = doCall("user.getStationList", request_args,
        //        false, true, null);

        Map<String, Object> requestArgs = new HashMap<>();
        JSONObject result = doCall("station/getStations", requestArgs);
        
        //Our stations come in a JSONArray within the JSONObject
        JSONArray result_stations = result.getJSONArray("stations");
        ArrayList<Station> stations = new ArrayList<>();

        //Run through the stations within the array, and pick out some of the
        //properties we want.
        for (int i = 0; i < result_stations.length(); ++i) {

            JSONObject single_station =
                    result_stations.getJSONObject(i);

            HashMap<String, Object> station_prep =
                    new HashMap<>();

            // New Pandora API fields
            station_prep.put(
                    "stationId",
                    single_station.optString(
                            "stationId",
                            ""));

            // Save pandoraId separately
            station_prep.put(
                    "pandoraId",
                    single_station.optString(
                            "pandoraId",
                            ""));

            // Backward compatibility with old code
            station_prep.put(
                    "stationIdToken",
                    single_station.optString(
                            "pandoraId",
                            ""));

            // Name is now "name", not "stationName"
            station_prep.put(
                    "stationName",
                    single_station.optString(
                            "name",
                            single_station.optString(
                                    "stationName",
                                    "")));

            station_prep.put(
                    "isQuickMix",
                    single_station.optBoolean(
                            "isQuickMix",
                            false));

            stations.add(
                    new Station(
                            station_prep,
                            this));
        }
        
        this.cached_stations = stations;
        return stations;
    }
    

    /**
     * Description: Self explanatory function that converts from a hex string
     *  to a plain string. One complicated portion of this to mention is that
     *  String types can do something rather odd when conversions are made
     *  from byte arrays to Strings and back again. They don't like to work 
     *  out perfectly.
     */
    private byte[] fromHex(String hex_text) {
        int hex_len = hex_text.length();
        byte[] raw = new byte[hex_len / 2];
        for (int i = 0; i < hex_len; i += 2){
            raw[i / 2] = (byte) ((Character.digit(hex_text.charAt(i), 16) * 16)
                                 + Character.digit(hex_text.charAt(i + 1), 16));
        }
        return raw;
    }
    
    /**
     * Description: I had to look far and wide to find this implementation.
     *  Java's builtin Integer.toHexString() function is absolutely atrocious.
     *  A person can't depend on it for any kind of formatting predictability.
     *  For speed's sake, having the HEX_CHARS constant is a necessity.
     */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private String toHex(byte[] clean_text){
        char[] chars = new char[2 * clean_text.length];
        for (int i = 0; i < clean_text.length; ++i){
            chars[2 * i] = HEX_CHARS[(clean_text[i] & 0xF0) >>> 4];
            chars[2 * i + 1] = HEX_CHARS[clean_text[i] & 0x0F];
        }
        
        return new String(chars);
    }

    
    public boolean isAlive() {
        return isUserAuthorized();
    }
    
    private boolean isGetPlaylistCallValid(String station_token){
        return (station_token.compareTo(last_acquired_playlist_station) != 0)
                ||
                (this.last_acquired_playlist_time <= (
                        (System.currentTimeMillis() / 1000L) - MIN_TIME_BETWEEN_PLAYLIST_CALLS
                ));
    }
    
    public boolean isPandoraOneCredentials(){
        return (/*credentials.device_model.equals(ONE_DEVICE_ID)*/
                Objects.equals(credentials.device_model, ONE_DEVICE_ID));
    }

    public boolean isPartnerAuthorized(){
        return (this.partner_auth_token != null);
    }

    public boolean isUserAuthorized(){
        return (this.user_auth_token != null);
    }
    

    
    /**
     * Description: This takes a Blowfish encrypted, hexadecimal string, and 
     *  decrypts it to a plain string form.
     */
    public String pandoraDecrypt(String s) 
            throws GeneralSecurityException, BadPaddingException {
        byte[] bytes = fromHex(s);
        byte[] raw_decoded = blowfish_decode.doFinal(bytes);
        return new String(raw_decoded);
    }
    
    
    /**
     * Description: This function encrypts a string using a Blowfish cipher, 
     *  and returns the hexadecimal representation of the encryption.
     */
    public String pandoraEncrypt(String s) 
            throws GeneralSecurityException, BadPaddingException {
        byte[] byte_s = s.getBytes();
        
        if (byte_s.length % 8 != 0){            
            int padding_size = 8 - (byte_s.length % 8);
            byte[] tmp_bytes = new byte[byte_s.length + padding_size];
            System.arraycopy(byte_s, 0, tmp_bytes, 0, byte_s.length);
            byte_s = new byte[tmp_bytes.length];
            System.arraycopy(tmp_bytes, 0, byte_s, 0, tmp_bytes.length);
        }
        byte[] encode_raw = blowfish_encode.doFinal(byte_s);
        String result_string = new String(encode_raw);
        result_string = toHex(encode_raw);
        return result_string;
    }

    /**
     * Description: This is the authorization for the app itself.
     */
    private void partnerLogin() throws RPCException,
                                       IOException,
                                       Exception{
        Map<String, Object> partner_params = new HashMap<>(4);
        partner_params.put("username", credentials.username);
        partner_params.put("password", credentials.password);
        partner_params.put("deviceModel", credentials.device_model);
        partner_params.put("version", PROTOCOL_VERSION);
        JSONObject partner_return = null;
        
        partner_return = this.doCall("auth.partnerLogin", partner_params);
        partner_auth_token = partner_return.getString("partnerAuthToken");
        standard_url_params.put("partner_id", 
                                partner_return.getString("partnerId"));
        setSync(partner_return.getString("syncTime"));
        standard_url_params.put("auth_token", partner_auth_token);
    }
    


    /**
     * Description: Sends a song rating to the remote server.
     */
    public void rate(Song song, boolean rating)
            throws Exception {

        Map<String, Object> feedbackParams =
                new HashMap<>();

        feedbackParams.put(
                "trackToken",
                song.getId());

        feedbackParams.put(
                "isPositive",
                rating);

        JSONObject response =
                this.doCall(
                        "station/addFeedback",
                        feedbackParams);

        Log.i(
                "Pandoroid",
                "Feedback response: "
                        + response.toString());
    }
    
    /**
     * Description: This will run a partner login with the proper partner
     *  credentials as specified by the is_pandora_one variable.
     */
    public void runPartnerLogin(boolean is_pandora_one) throws RPCException,
                                                               IOException,
                                                               Exception{
        setCredentials(is_pandora_one);
        this.partnerLogin();
    }
    
    /**
     * Description: Sets the cipher keys up.
     */
    @SuppressLint("GetInstance")
    private void setCipher() throws NoSuchAlgorithmException,
                                    NoSuchPaddingException,
                                    InvalidKeyException {
        //We're using the built-in Blowfish cipher here.
        blowfish_encode = Cipher.getInstance("Blowfish/ECB/NoPadding");
        byte[] encrypt_key_data = this.credentials.e_cipher.getBytes();
        SecretKeySpec key_spec = new SecretKeySpec(encrypt_key_data, "Blowfish");
        blowfish_encode.init(Cipher.ENCRYPT_MODE, key_spec);
        
        blowfish_decode = Cipher.getInstance("Blowfish/ECB/NoPadding");
        byte[] decrypt_key_data = this.credentials.d_cipher.getBytes();
        key_spec = new SecretKeySpec(decrypt_key_data, "Blowfish");
        blowfish_decode.init(Cipher.DECRYPT_MODE, key_spec);
    }
    
    /**
     * Description: This sets or resets (depending on how you look at it) the
     *  credentials for the app's authentication. 
     * Postcondition: partnerLogin() will need to be called.
     * @param is_pandora_one boolean
     * @throws Exception error
     */
    private void setCredentials(boolean is_pandora_one) throws Exception{
        if (is_pandora_one){
            credentials.rpc_url = ONE_RPC_URL;
            credentials.device_model = ONE_DEVICE_ID;
            credentials.username = ONE_PARTNER_USERNAME;
            credentials.password = ONE_PARTNER_PASSWORD;
            credentials.d_cipher = ONE_DECRYPT_CIPHER;
            credentials.e_cipher = ONE_ENCRYPT_CIPHER;
        }
        else{
            credentials.rpc_url = AND_RPC_URL;
            credentials.device_model = AND_DEVICE_ID;
            credentials.username = AND_PARTNER_USERNAME;
            credentials.password = AND_PARTNER_PASSWORD;
            credentials.d_cipher = AND_DECRYPT_CIPHER;
            credentials.e_cipher = AND_ENCRYPT_CIPHER;
        }       
        this.sync_time = 0;
        this.pandora_rpc = new RPC(this.credentials.rpc_url, MIME_TYPE, USER_AGENT);
        try {
            this.setCipher();
        }
        catch (Exception e){
            Log.e("Pandoroid", "Fatal error in cipher", e);
            throw new Exception("Cipher error", e);
        }
    }
    
    /**
     * Description: The sync time from the remote server is rather special (or maybe not).
     *  It comes in hexadecimal form from which it must be dehexed to byte form,
     *  then it must be decrypted with the Blowfish decryption. From there,
     *  it's hidden inside a string with 4 bytes of junk characters at the 
     *  beginning, and two white space characters at the end.
     *  Unfortunately Java is screwing with me in obtaining this time.
     */
    private void setSync(String encoded_sync_time) throws Exception{
        this.sync_obtained_time = System.currentTimeMillis() / 1000L; 
        
//      //This time stamp contains a lot of junk in the string it's in.
//      String junk = pandoraDecrypt(encoded_sync_time); //First decrypt the hex
//      
//      //There is a problem with this algorithm and I suspect it's happening here
//      //in regards to the junk.substring() function. 
//      junk = junk.substring(4); //Remove the first 4 bytes of junk.
//      junk = junk.trim(); //Trim off the predictable white space chunks at the end.
//      this.sync_time = Long.parseLong(junk);
        
        //As long as our system clocks are accurate, using the system clock
        //is a suitable (and potentially long term) solution to this issue.
        this.sync_time = this.sync_obtained_time;
    }
    
    /**
     * Description: Disabled
     */
    public void tired(Song song)
            throws Exception {

        HashMap<String, Object> params =
                new HashMap<>();

        params.put(
                "trackToken",
                song.getId());

        try {

            JSONObject response =
                    doCall(
                            "listener/addTiredSong",
                            params);

            Log.i(
                    "Pandoroid",
                    "Tired response: "
                            + response.toString());

        } catch (Exception e) {

            Log.w(
                    "Pandoroid",
                    "Tired endpoint appears unavailable",
                    e);

            throw e;
        }
    }
}
