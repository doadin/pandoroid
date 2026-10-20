package com.pandoroid.playback;

import java.util.LinkedList;

public class TurboTimer {
    public static final int TURBO_TRIGGER_SIZE = 2; //Rebuffer's 2 times before it triggers.
    public static final int TURBO_TRIGGER_TIME_LENGTH = 10 * 60 * 1000; //10 minutes
    
    public boolean isTurbo(){
        cleanTimes();
        return times.size() >= TURBO_TRIGGER_SIZE;
    }
    
    public void updateForBuffer(){
        times.add((Long) System.currentTimeMillis());
    }
    
    private final LinkedList<Long> times = new LinkedList<>();
    
    private void cleanTimes(){
        while(needsCleaned()){
            times.pop();
        }
    }
    
    private boolean needsCleaned(){
        Long time_stamp = times.peek();
        if (time_stamp != null){
            return System.currentTimeMillis() - time_stamp > TURBO_TRIGGER_TIME_LENGTH;
        }
        return false;
    }
}
