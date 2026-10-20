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

import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;

public class Song {
    private final String album;
    private final String artist;
    private final String fileGain;
    private final String musicId;
    private final Integer rating;
    private final String stationId;
    private final String title;
    private final String songDetailURL;
    private final String albumDetailURL;
    private final String album_art_url;
    private final boolean tired;
    private final String message;
    private final Object startTime;
    private final boolean finished;
    private final long time_acquired;

    private final LinkedList<PandoraAudioUrl> audio_urls;
    
    private static final int MAX_TIME_ALIVE = 60 * 60 * 1000; //60 minutes

    public Song(){
        album = "";
        artist = "";
        fileGain = "";
        musicId = "";
        rating = 0;
        stationId = "";
        title = "";
        songDetailURL = "";
        albumDetailURL = "";
        album_art_url = "";         
        
        audio_urls = new LinkedList<>();
        tired = false;
        message = "";
        startTime = null;
        finished = false;
        time_acquired = System.currentTimeMillis();
    }

    public Song(Map<String,Object> d, List<PandoraAudioUrl> audio_urls_in) {        
        album = (String) d.get("albumName");
        artist = (String) d.get("artistName");
        fileGain = (String) d.get("trackGain");
        musicId = (String) d.get("trackToken");
        rating = (Integer) d.get("songRating");
        stationId = (String) d.get("stationId");
        title = (String) d.get("songName");
        songDetailURL = (String) d.get("songDetailURL");
        albumDetailURL = (String) d.get("albumDetailURL");
        album_art_url = (String) d.get("albumArtUrl");          
        
        audio_urls = new LinkedList<>();
        
        //Let's sort the audio_urls from highest to lowest;
        Collections.sort(audio_urls_in);
        int i = audio_urls_in.size();
        while (i > 0){
            --i;
            audio_urls.add(audio_urls_in.get(i));
        }
        
        tired = false;
        message = "";
        startTime = null;
        finished = false;
        time_acquired = System.currentTimeMillis();
    }

    public String getId() {
        return musicId;
    }

    public boolean isStillValid() {
        return (System.currentTimeMillis() - time_acquired) < MAX_TIME_ALIVE;
    }


    
    public String getAlbumCoverUrl() {
        return album_art_url;
    }
    
    /**
     * Description: Returns a linked list of PandoraAudioUrls sorted from highest
     *  to lowest audio quality.
     * @return
     */
    public LinkedList<PandoraAudioUrl> getSortedAudioUrls(){
        return this.audio_urls;
    }
    
    public String getAudioUrl(String audio_quality) {
        for (PandoraAudioUrl next : audio_urls) {
            if (audio_quality.compareTo(next.m_type) == 0) {
                return next.toString();
            }
        }
        return null;
    }
    
    public String getTitle() {
        return title;
    }
    public String getArtist() {
        return artist;
    }
    public String getAlbum() {
        return album;
    }
    public Integer getRating() {
        return rating;
    }
    public String getFileGain() {
        return fileGain;
    }
    public String getStationId() {
        return stationId;
    }
    public String getSongDetailURL() {
        return songDetailURL;
    }
    public String getAlbumDetailURL() {
        return albumDetailURL;
    }
    public boolean isTired() {
        return tired;
    }
    public String getMessage() {
        return message;
    }
    public Object getStartTime() {
        return startTime;
    }
    public boolean isFinished() {
        return finished;
    }
}
