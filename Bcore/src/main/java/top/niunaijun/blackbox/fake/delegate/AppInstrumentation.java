package top.niunaijun.blackbox.fake.delegate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Fragment;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {
    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final String ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String EXTRA_REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) synchronized (AppInstrumentation.class) {
            if (sAppInstrumentation == null) sAppInstrumentation = new AppInstrumentation();
        }
        return sAppInstrumentation;
    }

    @Override public void injectHook() {
        try {
            Instrumentation current = getCurrInstrumentation();
            if (current == this || checkInstrumentation(current)) return;
            mBaseInstrumentation = current;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Throwable e) { Log.e(TAG, "Unable to inject instrumentation", e); }
    }
    private Instrumentation getCurrInstrumentation() { return BRActivityThread.get(BlackBoxCore.mainThread()).mInstrumentation(); }
    @Override public boolean isBadEnv() { return !checkInstrumentation(getCurrInstrumentation()); }
    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation == null) return false;
        if (instrumentation instanceof AppInstrumentation) return true;
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) return false;
        while (clazz != null && !Instrumentation.class.equals(clazz)) {
            for (Field field : clazz.getDeclaredFields()) if (Instrumentation.class.isAssignableFrom(field.getType())) {
                try { field.setAccessible(true); if (field.get(instrumentation) instanceof AppInstrumentation) return true; }
                catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
    private void checkHCallback() { HookManager.get().checkEnv(HCallbackProxy.class); }
    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback(); HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity); ActivityCompat.fix(activity);
        if (info != null) {
            if (info.theme != 0) activity.getTheme().applyStyle(info.theme, true);
            ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
        }
    }
    @Override public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException { ContextCompat.fix(context); return super.newApplication(cl, className, context); }
    @Override public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle state) { checkActivity(activity); super.callActivityOnCreate(activity, icicle, state); }
    @Override public void callActivityOnCreate(Activity activity, Bundle icicle) { checkActivity(activity); super.callActivityOnCreate(activity, icicle); }
    @Override public void callApplicationOnCreate(Application app) { checkHCallback(); super.callApplicationOnCreate(app); }
    @Override public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException { try { return super.newActivity(cl, className, intent); } catch (ClassNotFoundException e) { return mBaseInstrumentation.newActivity(cl, className, intent); } }

    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, Activity a, Intent i, int rc, Bundle o) throws Throwable { if (handleVirtualPermissionRequest(a,i,rc)) return null; return super.execStartActivity(c,ct,token,a,i,rc,o); }
    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, Activity a, Intent i, int rc) throws Throwable { if (handleVirtualPermissionRequest(a,i,rc)) return null; return super.execStartActivity(c,ct,token,a,i,rc); }
    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, Fragment f, Intent i, int rc) throws Throwable { Activity a=f!=null?f.getActivity():null; if(handleVirtualPermissionRequest(a,i,rc))return null; return super.execStartActivity(c,ct,token,f,i,rc); }
    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, Fragment f, Intent i, int rc, Bundle o) throws Throwable { Activity a=f!=null?f.getActivity():null; if(handleVirtualPermissionRequest(a,i,rc))return null; return super.execStartActivity(c,ct,token,f,i,rc,o); }
    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, String target, Intent i, int rc, Bundle o) throws Throwable { Activity a=c instanceof Activity?(Activity)c:null; if(handleVirtualPermissionRequest(a,i,rc))return null; return super.execStartActivity(c,ct,token,target,i,rc,o); }
    public ActivityResult execStartActivity(Context c, IBinder ct, IBinder token, Activity a, Intent i, int rc, Bundle o, UserHandle u) throws Throwable { if(handleVirtualPermissionRequest(a,i,rc))return null; return super.execStartActivity(c,ct,token,a,i,rc,o,u); }

    private boolean activityUsable(Activity a) { return a != null && !a.isFinishing() && (Build.VERSION.SDK_INT < 17 || !a.isDestroyed()); }

    private boolean handleVirtualPermissionRequest(Activity activity, Intent intent, int requestCode) {
        if (!activityUsable(activity) || intent == null || !ACTION_REQUEST_PERMISSIONS.equals(intent.getAction())) return false;
        String[] permissions = intent.getStringArrayExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (permissions == null || permissions.length == 0) return false;
        for (String p : permissions) if (!VirtualPermissionManager.isManagedRuntimePermission(p)) return false;
        final String pkg=BActivityThread.getAppPackageName(); final int uid=BActivityThread.getUserId();
        if(pkg==null)return false;
        List<String> promptable=new ArrayList<>();
        for(String p:permissions){int s=VirtualPermissionManager.getPermissionState(pkg,uid,p); if(s==VirtualPermissionManager.STATE_DEFAULT||s==VirtualPermissionManager.STATE_DENIED)promptable.add(p);}
        final String[] callback=permissions.clone();
        if(promptable.isEmpty()) deliverPermissionResult(activity,pkg,uid,requestCode,callback);
        else activity.runOnUiThread(()->{ if(activityUsable(activity)) showVirtualPermissionSequence(activity,pkg,uid,requestCode,callback,promptable.toArray(new String[0])); else deliverPermissionResult(activity,pkg,uid,requestCode,callback); });
        return true;
    }

    private void showVirtualPermissionSequence(Activity a,String pkg,int uid,int rc,String[] callback,String[] prompt){
        boolean bg=containsPermission(prompt,Manifest.permission.ACCESS_BACKGROUND_LOCATION); List<String> first=new ArrayList<>();
        for(String p:prompt)if(!Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(p))first.add(p);
        Runnable finish=()->deliverPermissionResult(a,pkg,uid,rc,callback);
        if(!bg){showVirtualPermissionGroups(a,pkg,uid,first.toArray(new String[0]),finish);return;}
        Runnable background=()->{if(hasForegroundLocation(pkg,uid))showVirtualPermissionGroups(a,pkg,uid,new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},finish);else{VirtualPermissionManager.setPermissionState(pkg,uid,Manifest.permission.ACCESS_BACKGROUND_LOCATION,VirtualPermissionManager.STATE_DENIED);finish.run();}};
        if(first.isEmpty())background.run();else showVirtualPermissionGroups(a,pkg,uid,first.toArray(new String[0]),background);
    }
    private boolean hasForegroundLocation(String p,int u){return VirtualPermissionManager.isPermissionGranted(p,u,Manifest.permission.ACCESS_FINE_LOCATION)||VirtualPermissionManager.isPermissionGranted(p,u,Manifest.permission.ACCESS_COARSE_LOCATION);}
    private boolean containsPermission(String[] ps,String w){if(ps==null)return false;for(String p:ps)if(w.equals(p))return true;return false;}
    private void showVirtualPermissionGroups(Activity a,String p,int u,String[] ps,Runnable done){
        if(ps==null||ps.length==0){done.run();return;} LinkedHashMap<String,List<String>> grouped=new LinkedHashMap<>();
        for(String x:ps)grouped.computeIfAbsent(VirtualPermissionManager.getPermissionGroup(x),k->new ArrayList<>()).add(x);
        showVirtualPermissionGroupAt(a,p,u,new ArrayList<>(grouped.entrySet()),0,done);
    }
    private void showVirtualPermissionGroupAt(Activity a,String p,int u,List<Map.Entry<String,List<String>>> groups,int index,Runnable done){
        if(!activityUsable(a)){done.run();return;} if(index>=groups.size()){done.run();return;}
        Map.Entry<String,List<String>> e=groups.get(index); showVirtualPermissionPrompt(a,p,u,VirtualPermissionManager.getPermissionGroupLabel(e.getKey()),e.getValue().toArray(new String[0]),()->showVirtualPermissionGroupAt(a,p,u,groups,index+1,done));
    }
    private void showVirtualPermissionPrompt(Activity a,String p,int u,String label,String[] ps,Runnable done){
        if(!activityUsable(a)){done.run();return;} final boolean[] handled={false}; StringBuilder m=new StringBuilder();
        for(String x:ps){if(m.length()>0)m.append('\n');int dot=x.lastIndexOf('.');m.append("• ").append(dot>=0?x.substring(dot+1):x);}
        try{
            AlertDialog d=new AlertDialog.Builder(a).setTitle("权限请求 · "+label).setMessage(m.toString())
                    .setPositiveButton("允许",(x,w)->{handled[0]=true;setPermissionStates(p,u,ps,VirtualPermissionManager.STATE_GRANTED);done.run();})
                    .setNegativeButton("拒绝",(x,w)->{handled[0]=true;setPermissionStates(p,u,ps,VirtualPermissionManager.STATE_DENIED);done.run();})
                    .setNeutralButton("拒绝且不再询问",(x,w)->{handled[0]=true;setPermissionStates(p,u,ps,VirtualPermissionManager.STATE_DENIED_FIXED);done.run();}).create();
            d.setOnCancelListener(x->{if(!handled[0]){handled[0]=true;setPermissionStates(p,u,ps,VirtualPermissionManager.STATE_DENIED);done.run();}});
            d.setOnDismissListener(x->handled[0]=true);
            if(activityUsable(a))d.show();else done.run();
        }catch(Throwable e){Log.e(TAG,"Unable to show virtual permission prompt",e);done.run();}
    }
    private void setPermissionStates(String p,int u,String[] ps,int s){for(String x:ps)VirtualPermissionManager.setPermissionState(p,u,x,s);}
    private void deliverPermissionResult(Activity a,String p,int u,int rc,String[] ps){
        if(a==null)return; int[] rs=new int[ps.length]; for(int i=0;i<ps.length;i++)rs[i]=VirtualPermissionManager.isPermissionGranted(p,u,ps[i])?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;
        Log.d(TAG,"Virtual runtime permission result: "+ Arrays.toString(ps)+" => "+Arrays.toString(rs));
        a.runOnUiThread(()->{if(!activityUsable(a))return;try{a.onRequestPermissionsResult(rc,ps,rs);}catch(Throwable e){Log.e(TAG,"Failed to deliver virtual permission result",e);}});
    }
}
