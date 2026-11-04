package com.pandoroid.pandora;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * @author Dylan Powers <dylan.kyle.powers@gmail.com>
 * Description: Holds data relevant to the URL of a song retrieved from Pandora.
 *
 */
public class PandoraAudioUrl implements Comparable<PandoraAudioUrl>{
    public final String m_type;
    public final int m_bitrate;
    public final String m_url_str;
    
    public PandoraAudioUrl(String type, int bitrate, String url){
        this.m_type = type;
        this.m_bitrate = bitrate;
        this.m_url_str = url;
    }
    
    public PandoraAudioUrl(Map<String, Object> extended_audio_url){
        if (is_AAC_64(extended_audio_url)){
            this.m_type = PandoraRadio.AAC_64;                  
        }
        else if (is_AAC_32(extended_audio_url)){
            this.m_type = PandoraRadio.AAC_32;
        }
        else if (is_MP3_192(extended_audio_url)){
            this.m_type = PandoraRadio.MP3_192;
        }
        else {
            this.m_type = "unknown";
        }

        String bitrateStr = (String) extended_audio_url.get("bitrate");

        if (bitrateStr != null) {
            this.m_bitrate = Integer.parseInt(bitrateStr);
        } else {
            Log.w("Pandoroid", "Missing bitrate in audio URL data");
            this.m_bitrate = 0;
        }
        this.m_url_str = (String) extended_audio_url.get("audioUrl");
        Log.i("pandoroid", "Audio URI: " + extended_audio_url);
    }
    
    @NonNull
    public String toString(){
        return m_url_str;
    }

    private boolean is_AAC_64(Map<String, Object> extended_audio_url){
        return "64".equals(extended_audio_url.get("bitrate"))
                && "aacplus".equals(extended_audio_url.get("encoding"));
    }

    private boolean is_AAC_32(Map<String, Object> extended_audio_url){
        return "32".equals(extended_audio_url.get("bitrate"))
                && "aacplus".equals(extended_audio_url.get("encoding"));
    }

    private boolean is_MP3_192(Map<String, Object> extended_audio_url){
        return "192".equals(extended_audio_url.get("bitrate"))
                && "mp3".equals(extended_audio_url.get("encoding"));
    }
    
    public int compareTo(PandoraAudioUrl comparable){
        //if it's greater
        return Integer.compare(this.m_bitrate, comparable.m_bitrate);
    }
}
