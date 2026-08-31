package com.oai.harmonywake;
import android.content.BroadcastReceiver;import android.content.Context;import android.content.Intent;
public class RescheduleReceiver extends BroadcastReceiver { @Override public void onReceive(Context c,Intent i){AlarmScheduler.rescheduleAll(c);} }
