package com.rnllama;

import androidx.annotation.NonNull;
import android.util.Log;
import android.os.Build;
import android.os.Handler;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

import java.util.HashMap;
import java.util.Random;
import java.io.File;
import java.io.FileInputStream;
import java.io.PushbackInputStream;

public class RNLlama implements LifecycleEventListener {
  public static final String NAME = "RNLlama";

  private ReactApplicationContext reactContext;
  private static final int MIN_BATTERY_LEVEL = 20; // Minimum battery level for embedding generation
  private static final int HIGH_BATTERY_LEVEL = 50; // High battery level threshold

  public RNLlama(ReactApplicationContext reactContext) {
    reactContext.addLifecycleEventListener(this);
    this.reactContext = reactContext;
  }

  private HashMap<AsyncTask, String> tasks = new HashMap<>();
  
  /**
   * Check if the device is currently charging or has high battery
   */
  private boolean isChargingOrHighBattery() {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
    
    // Are we charging?
    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL;
    
    // How much battery do we have?
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    float batteryPct = level * 100 / (float)scale;
    
    return isCharging || batteryPct >= HIGH_BATTERY_LEVEL;
  }
  
  /**
   * Check if battery level is suitable for computation-heavy tasks
   */
  private boolean isBatterySuitable() {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
    
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    float batteryPct = level * 100 / (float)scale;
    
    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL;
    
    return isCharging || batteryPct >= MIN_BATTERY_LEVEL;
  }
  
  /**
   * Get current battery level as percentage
   */
  private int getBatteryLevel() {
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
    
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    
    return (int)(level * 100 / (float)scale);
  }

  private HashMap<Integer, LlamaContext> contexts = new HashMap<>();

  public void toggleNativeLog(boolean enabled, Promise promise) {
    new AsyncTask<Void, Void, Boolean>() {
      private Exception exception;

      @Override
      protected Boolean doInBackground(Void... voids) {
        try {
          LlamaContext.toggleNativeLog(reactContext, enabled);
          return true;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Boolean result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private int llamaContextLimit = -1;

  public void setContextLimit(double limit, Promise promise) {
    llamaContextLimit = (int) limit;
    promise.resolve(null);
  }

  public void modelInfo(final String model, final ReadableArray skip, final Promise promise) {
    new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          String[] skipArray = new String[skip.size()];
          for (int i = 0; i < skip.size(); i++) {
            skipArray[i] = skip.getString(i);
          }
          return LlamaContext.modelInfo(model, skipArray);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  public void initContext(double id, final ReadableMap params, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context != null) {
            throw new Exception("Context already exists");
          }
          if (llamaContextLimit > -1 && contexts.size() >= llamaContextLimit) {
            throw new Exception("Context limit reached");
          }
          LlamaContext llamaContext = new LlamaContext(contextId, reactContext, params);
          if (llamaContext.getContext() == 0) {
            throw new Exception("Failed to initialize context");
          }
          contexts.put(contextId, llamaContext);
          WritableMap result = Arguments.createMap();
          result.putBoolean("gpu", false);
          result.putString("reasonNoGPU", "Currently not supported");
          result.putMap("model", llamaContext.getModelDetails());
          result.putString("androidLib", llamaContext.getLoadedLibrary());
          return result;
        } catch (Exception e) {
          exception = e;
          return null;
        }
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "initContext");
  }

  public void getFormattedChat(double id, final String messages, final String chatTemplate, final ReadableMap params, Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Object>() {
      private Exception exception;

      @Override
      protected Object doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          if (params.hasKey("jinja") && params.getBoolean("jinja")) {
            ReadableMap result = context.getFormattedChatWithJinja(messages, chatTemplate, params);
            if (result.hasKey("_error")) {
              throw new Exception(result.getString("_error"));
            }
            return result;
          }
          return context.getFormattedChat(messages, chatTemplate);
        } catch (Exception e) {
          exception = e;
          return null;
        }
      }

      @Override
      protected void onPostExecute(Object result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "getFormattedChat-" + contextId);
  }

  public void loadSession(double id, final String path, Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          WritableMap result = context.loadSession(path);
          return result;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "loadSession-" + contextId);
  }

  public void saveSession(double id, final String path, double size, Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Integer>() {
      private Exception exception;

      @Override
      protected Integer doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          Integer count = context.saveSession(path, (int) size);
          return count;
        } catch (Exception e) {
          exception = e;
        }
        return -1;
      }

      @Override
      protected void onPostExecute(Integer result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "saveSession-" + contextId);
  }

  public void completion(double id, final ReadableMap params, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          if (context.isPredicting()) {
            throw new Exception("Context is busy");
          }
          WritableMap result = context.completion(params);
          return result;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "completion-" + contextId);
  }

  public void stopCompletion(double id, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Void>() {
      private Exception exception;

      @Override
      protected Void doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          context.stopCompletion();
          AsyncTask completionTask = null;
          for (AsyncTask task : tasks.keySet()) {
            if (tasks.get(task).equals("completion-" + contextId)) {
              task.get();
              break;
            }
          }
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "stopCompletion-" + contextId);
  }

  public void tokenize(double id, final String text, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          return context.tokenize(text);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "tokenize-" + contextId);
  }

  public void detokenize(double id, final ReadableArray tokens, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, String>() {
      private Exception exception;

      @Override
      protected String doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          return context.detokenize(tokens);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(String result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "detokenize-" + contextId);
  }

  public void embedding(double id, final String text, final ReadableMap params, final Promise promise) {
    final int contextId = (int) id;
    
    // Check if we should defer embedding generation based on battery status
    if (!isChargingOrHighBattery() && params.hasKey("deferOnLowBattery") && params.getBoolean("deferOnLowBattery")) {
      WritableMap result = Arguments.createMap();
      result.putBoolean("deferred", true);
      result.putInt("batteryLevel", getBatteryLevel());
      promise.resolve(result);
      return;
    }
    
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          // Check battery status again in case it changed
          if (!isBatterySuitable() && params.hasKey("skipOnCriticalBattery") && params.getBoolean("skipOnCriticalBattery")) {
            WritableMap result = Arguments.createMap();
            result.putBoolean("skipped", true);
            result.putInt("batteryLevel", getBatteryLevel());
            return result;
          }
          
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          WritableMap embedding = context.getEmbedding(text, params);
          
          // Add battery metadata if requested
          if (params.hasKey("includeBatteryMeta") && params.getBoolean("includeBatteryMeta")) {
            embedding.putInt("batteryLevel", getBatteryLevel());
          }
          
          return embedding;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "embedding-" + contextId);
  }

  public void bench(double id, final double pp, final double tg, final double pl, final double nr, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, String>() {
      private Exception exception;

      @Override
      protected String doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          return context.bench((int) pp, (int) tg, (int) pl, (int) nr);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(String result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "bench-" + contextId);
  }

  public void applyLoraAdapters(double id, final ReadableArray loraAdapters, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Void>() {
      private Exception exception;

      @Override
      protected Void doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          if (context.isPredicting()) {
            throw new Exception("Context is busy");
          }
          context.applyLoraAdapters(loraAdapters);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "applyLoraAdapters-" + contextId);
  }

  public void removeLoraAdapters(double id, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Void>() {
      private Exception exception;

      @Override
      protected Void doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          if (context.isPredicting()) {
            throw new Exception("Context is busy");
          }
          context.removeLoraAdapters();
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(null);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "removeLoraAdapters-" + contextId);
  }

  public void getLoadedLoraAdapters(double id, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, ReadableArray>() {
      private Exception exception;

      @Override
      protected ReadableArray doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          return context.getLoadedLoraAdapters();
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(ReadableArray result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(result);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "getLoadedLoraAdapters-" + contextId);
  }

  public void releaseContext(double id, Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, Void>() {
      private Exception exception;

      @Override
      protected Void doInBackground(Void... voids) {
        try {
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context " + id + " not found");
          }
          context.interruptLoad();
          context.stopCompletion();
          AsyncTask completionTask = null;
          for (AsyncTask task : tasks.keySet()) {
            if (tasks.get(task).equals("completion-" + contextId)) {
              task.get();
              break;
            }
          }
          context.release();
          contexts.remove(contextId);
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(null);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "releaseContext-" + contextId);
  }

  public void releaseAllContexts(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, Void>() {
      private Exception exception;

      @Override
      protected Void doInBackground(Void... voids) {
        try {
          onHostDestroy();
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (exception != null) {
          promise.reject(exception);
          return;
        }
        promise.resolve(null);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "releaseAllContexts");
  }

  @Override
  public void onHostResume() {
  }

  @Override
  public void onHostPause() {
  }

  @Override
  public void onHostDestroy() {
    for (LlamaContext context : contexts.values()) {
      context.stopCompletion();
    }
    for (AsyncTask task : tasks.keySet()) {
      try {
        task.get();
      } catch (Exception e) {
        Log.e(NAME, "Failed to wait for task", e);
      }
    }
    tasks.clear();
    for (LlamaContext context : contexts.values()) {
      context.release();
    }
    contexts.clear();
  }
  
  /**
   * Check if the device is thermally throttled
   */
  @ReactMethod
  public void isThermallyThrottled(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, Boolean>() {
      @Override
      protected Boolean doInBackground(Void... voids) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            return powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE) <= 0.3f;
          } catch (Exception e) {
            Log.e(NAME, "Failed to check thermal status", e);
          }
        }
        return false;
      }

      @Override
      protected void onPostExecute(Boolean result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "thermal-check");
  }
  /**
   * Get device resource status for RAG operations
   */
  @ReactMethod
  public void getDeviceResourceStatus(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        // Battery status
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level * 100 / (float)scale;
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        result.putInt("batteryLevel", (int)batteryPct);
        result.putBoolean("isCharging", isCharging);
        
        // Memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        result.putDouble("availableMemoryMB", memoryInfo.availMem / (1024.0 * 1024.0));
        result.putDouble("totalMemoryMB", memoryInfo.totalMem / (1024.0 * 1024.0));
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        // Thermal status on newer Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            float headroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            result.putDouble("thermalHeadroom", headroom);
          } catch (Exception e) {
            result.putNull("thermalHeadroom");
          }
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "resource-status");
  }
}
