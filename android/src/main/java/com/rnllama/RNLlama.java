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
    
    // Get current device resource status
    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
    
    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    float batteryPct = level * 100 / (float)scale;
    int batteryLevel = (int)batteryPct;
    
    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL;
    
    float thermalHeadroom = 1.0f;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        android.os.PowerManager powerManager = (android.os.PowerManager) 
            reactContext.getSystemService(Context.POWER_SERVICE);
        thermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
      } catch (Exception e) {
        Log.e(NAME, "Failed to check thermal status", e);
      }
    }
    
    // Get memory info
    android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
    android.app.ActivityManager activityManager = 
        (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
    activityManager.getMemoryInfo(memoryInfo);
    double availableMemoryMB = memoryInfo.availMem / (1024.0 * 1024.0);
    
    // Check if we should defer embedding generation based on battery status
    if (params.hasKey("deferOnLowBattery") && params.getBoolean("deferOnLowBattery")) {
      if (batteryLevel < 20 && !isCharging) {
        WritableMap result = Arguments.createMap();
        result.putBoolean("deferred", true);
        result.putInt("batteryLevel", batteryLevel);
        result.putFloat("thermalHeadroom", thermalHeadroom);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        promise.resolve(result);
        return;
      }
    }
    
    // Determine if this is a high-priority embedding
    boolean isHighPriority = params.hasKey("highPriority") && params.getBoolean("highPriority");
    
    // Determine if we should create a shadow vector (lower quality backup)
    boolean createShadowVector = params.hasKey("createShadowVector") && params.getBoolean("createShadowVector");
    
    // Get target dimension if specified
    int targetDimension = params.hasKey("targetDimension") ? params.getInt("targetDimension") : -1;
    
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          // Check battery status again in case it changed
          if (params.hasKey("skipOnCriticalBattery") && params.getBoolean("skipOnCriticalBattery")) {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
            
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int currentBatteryLevel = (int)(level * 100 / (float)scale);
            
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCurrentlyCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                          status == BatteryManager.BATTERY_STATUS_FULL;
                                          
            if (currentBatteryLevel < 10 && !isCurrentlyCharging && !isHighPriority) {
              WritableMap result = Arguments.createMap();
              result.putBoolean("skipped", true);
              result.putInt("batteryLevel", currentBatteryLevel);
              return result;
            }
          }
          
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          // Add resource parameters to embedding request
          ReadableMap embeddingParams = params;
          if (!params.hasKey("resourceParams")) {
            WritableMap resourceParams = Arguments.createMap();
            resourceParams.putInt("batteryLevel", batteryLevel);
            resourceParams.putBoolean("isCharging", isCharging);
            resourceParams.putDouble("thermalHeadroom", thermalHeadroom);
            resourceParams.putDouble("availableMemoryMB", availableMemoryMB);
            resourceParams.putInt("targetDimension", targetDimension);
            resourceParams.putBoolean("createShadowVector", createShadowVector);
            
            // Create a new params object with the resource parameters
            WritableMap newParams = Arguments.createMap();
            for (Iterator<Map.Entry<String, Object>> it = params.getEntryMap().entrySet().iterator(); it.hasNext();) {
              Map.Entry<String, Object> entry = it.next();
              newParams.putString(entry.getKey(), entry.getValue().toString());
            }
            newParams.putMap("resourceParams", resourceParams);
            embeddingParams = newParams;
          }
          
          WritableMap embedding = context.getEmbedding(text, embeddingParams);
          
          // Add device resource metadata
          if (params.hasKey("includeResourceMeta") && params.getBoolean("includeResourceMeta")) {
            embedding.putInt("batteryLevel", batteryLevel);
            embedding.putBoolean("isCharging", isCharging);
            embedding.putFloat("thermalHeadroom", thermalHeadroom);
            embedding.putDouble("availableMemoryMB", availableMemoryMB);
            embedding.putBoolean("lowMemory", memoryInfo.lowMemory);
            
            // Add timestamp for expiration tracking
            embedding.putDouble("timestamp", System.currentTimeMillis());
            
            // Add shadow vector status
            if (embedding.hasKey("isShadowVector")) {
              embedding.putBoolean("isShadowVector", embedding.getBoolean("isShadowVector"));
            } else {
              embedding.putBoolean("isShadowVector", false);
            }
            
            // Add dimension information
            if (embedding.hasKey("originalDimension") && embedding.hasKey("storedDimension")) {
              embedding.putInt("dimensionReduction", 
                  embedding.getInt("originalDimension") - embedding.getInt("storedDimension"));
            }
          } else if (params.hasKey("includeBatteryMeta") && params.getBoolean("includeBatteryMeta")) {
            // For backward compatibility
            embedding.putInt("batteryLevel", batteryLevel);
          }
          
          // Add quantization info if requested
          if (params.hasKey("quantized") && params.getBoolean("quantized")) {
            embedding.putBoolean("quantized", true);
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
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            float headroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            boolean isThrottled = headroom <= 0.3f;
            
            result.putBoolean("isThrottled", isThrottled);
            result.putDouble("thermalHeadroom", headroom);
            
            // Get thermal status
            int thermalStatus = powerManager.getCurrentThermalStatus();
            result.putInt("thermalStatus", thermalStatus);
            
            return result;
          } catch (Exception e) {
            Log.e(NAME, "Failed to check thermal status", e);
            result.putBoolean("isThrottled", false);
            result.putString("error", e.getMessage());
            return result;
          }
        }
        
        result.putBoolean("isThrottled", false);
        result.putString("reason", "API level too low");
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "thermal-check");
  }
  
  /**
   * Check if vector search should be used based on device conditions
   */
  @ReactMethod
  public void shouldUseVectorSearch(ReadableMap options, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        // Get battery status
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (int)(level * 100 / (float)scale);
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        // Get thermal status
        float thermalHeadroom = 1.0f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            thermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
          } catch (Exception e) {
            // Default to 0.5 if we can't get the thermal headroom
            thermalHeadroom = 0.5f;
          }
        }
        
        // Get memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        double availableMemoryMB = memoryInfo.availMem / (1024.0 * 1024.0);
        
        // Check if this is a high-priority query
        boolean isHighPriority = false;
        if (options != null && options.hasKey("highPriority")) {
          isHighPriority = options.getBoolean("highPriority");
        }
        
        // Determine if we should use vector search
        boolean shouldUse = true;
        String reason = "";
        
        // Skip vector search if battery is critically low and not charging
        if (batteryLevel < 15 && !isCharging) {
          shouldUse = false;
          reason = "Battery critically low (" + batteryLevel + "%)";
        }
        // Use reduced vector search if battery is low but not critical
        else if (batteryLevel < 20 && !isCharging && !isHighPriority) {
          shouldUse = false;
          reason = "Battery low (" + batteryLevel + "%), not a high-priority query";
        }
        // Skip if device is thermally throttled
        else if (thermalHeadroom < 0.2f) {
          shouldUse = false;
          reason = "Device is thermally throttled (headroom: " + thermalHeadroom + ")";
        }
        // Skip if memory is severely constrained
        else if (availableMemoryMB < 100) {
          shouldUse = false;
          reason = "Memory critically low (" + (int)availableMemoryMB + " MB available)";
        }
        
        result.putBoolean("shouldUseVectorSearch", shouldUse);
        result.putString("reason", reason);
        
        // Add resource metrics
        result.putInt("batteryLevel", batteryLevel);
        result.putBoolean("isCharging", isCharging);
        result.putDouble("thermalHeadroom", thermalHeadroom);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        // Add recommendation for vector search limit if memory is somewhat constrained
        if (shouldUse && availableMemoryMB < 200) {
          result.putBoolean("shouldLimitResults", true);
          result.putInt("recommendedLimit", 20); // Recommend limiting to 20 results
        } else {
          result.putBoolean("shouldLimitResults", false);
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "vector-search-check");
  }
  
  /**
   * Perform hybrid search using both text and vector search
   */
  @ReactMethod
  public void hybridSearch(double id, final String query, final ReadableArray embeddings, final ReadableMap options, final Promise promise) {
    final int contextId = (int) id;
    
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          // Get device resource status
          IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
          Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
          
          int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
          int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
          int batteryLevel = (int)(level * 100 / (float)scale);
          
          int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
          boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                               status == BatteryManager.BATTERY_STATUS_FULL;
          
          // Get thermal status
          float thermalHeadroom = 1.0f;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
              android.os.PowerManager powerManager = (android.os.PowerManager) 
                  reactContext.getSystemService(Context.POWER_SERVICE);
              thermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            } catch (Exception e) {
              thermalHeadroom = 0.5f;
            }
          }
          
          // Get memory status
          android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
          android.app.ActivityManager activityManager = 
              (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
          activityManager.getMemoryInfo(memoryInfo);
          double availableMemoryMB = memoryInfo.availMem / (1024.0 * 1024.0);
          
          // Check if this is a high-priority query
          boolean isHighPriority = options != null && options.hasKey("highPriority") && 
                                  options.getBoolean("highPriority");
          
          // Check if we should use vector search
          boolean useVectorSearch = isHighPriority || 
              (batteryLevel >= 15 || isCharging) && 
              thermalHeadroom >= 0.2f && 
              availableMemoryMB >= 100;
          
          // Get the context
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          // Generate query embedding if vector search is enabled
          WritableMap queryEmbedding = null;
          if (useVectorSearch) {
            ReadableMap embParams = Arguments.createMap();
            ((WritableMap)embParams).putBoolean("skipOnCriticalBattery", true);
            ((WritableMap)embParams).putBoolean("highPriority", isHighPriority);
            queryEmbedding = context.getEmbedding(query, embParams);
            
            // If embedding generation was skipped due to battery, fall back to text search
            if (queryEmbedding.hasKey("skipped") && queryEmbedding.getBoolean("skipped")) {
              useVectorSearch = false;
            }
          }
          
          // Prepare result
          WritableMap result = Arguments.createMap();
          WritableArray searchResults = Arguments.createArray();
          
          // Add resource metrics to result
          result.putInt("batteryLevel", batteryLevel);
          result.putBoolean("isCharging", isCharging);
          result.putDouble("thermalHeadroom", thermalHeadroom);
          result.putDouble("availableMemoryMB", availableMemoryMB);
          result.putBoolean("lowMemory", memoryInfo.lowMemory);
          result.putBoolean("vectorSearchUsed", useVectorSearch);
          
          // Perform hybrid search if vector search is enabled
          if (useVectorSearch && queryEmbedding != null && !queryEmbedding.hasKey("skipped")) {
            // Convert embeddings to format expected by C++ code
            // This would call into the C++ hybrid_search function
            
            // For now, we'll implement a simple version in Java
            // In a real implementation, this would call the C++ hybrid_search function
            
            // Calculate result limit based on available memory
            int resultLimit = availableMemoryMB < 200 ? 20 : 50;
            if (options != null && options.hasKey("limit")) {
              resultLimit = Math.min(resultLimit, options.getInt("limit"));
            }
            
            // Get query embedding as array
            ReadableArray queryEmbeddingArray = queryEmbedding.getArray("embedding");
            
            // Process each embedding
            for (int i = 0; i < embeddings.size(); i++) {
              ReadableMap embedding = embeddings.getMap(i);
              
              // Skip if embedding doesn't have required fields
              if (!embedding.hasKey("embedding") || !embedding.hasKey("text")) {
                continue;
              }
              
              // Skip entries that are too old (7 days = 604800000 ms)
              if (embedding.hasKey("timestamp")) {
                long timestamp = (long)embedding.getDouble("timestamp");
                if (System.currentTimeMillis() - timestamp > 604800000) {
                  continue;
                }
              }
              
              // Calculate similarity
              ReadableArray embArray = embedding.getArray("embedding");
              double similarity = calculateCosineSimilarity(queryEmbeddingArray, embArray);
              
              // Add to results if similarity is above threshold
              // Use a lower threshold when battery is low
              double threshold = batteryLevel < 30 ? 0.6 : 0.7;
              if (similarity > threshold) {
                WritableMap searchResult = Arguments.createMap();
                searchResult.putString("text", embedding.getString("text"));
                searchResult.putDouble("score", similarity);
                if (embedding.hasKey("timestamp")) {
                  searchResult.putDouble("timestamp", embedding.getDouble("timestamp"));
                }
                searchResult.putInt("sourceType", 1); // 1 = vector search
                searchResults.pushMap(searchResult);
              }
            }
            
            // Sort results by score (descending)
            // This is a simple bubble sort - in production code, use a more efficient sort
            for (int i = 0; i < searchResults.size() - 1; i++) {
              for (int j = 0; j < searchResults.size() - i - 1; j++) {
                ReadableMap result1 = searchResults.getMap(j);
                ReadableMap result2 = searchResults.getMap(j + 1);
                if (result1.getDouble("score") < result2.getDouble("score")) {
                  // Swap
                  WritableArray tempArray = Arguments.createArray();
                  tempArray.pushMap(result2);
                  ((WritableArray)searchResults).pushMap(result1, j);
                  ((WritableArray)searchResults).pushMap(tempArray.getMap(0), j + 1);
                }
              }
            }
            
            // Limit results
            if (searchResults.size() > resultLimit) {
              WritableArray limitedResults = Arguments.createArray();
              for (int i = 0; i < resultLimit; i++) {
                limitedResults.pushMap(searchResults.getMap(i));
              }
              searchResults = limitedResults;
            }
          }
          
          result.putArray("results", searchResults);
          return result;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }
      
      // Helper method to calculate cosine similarity
      private double calculateCosineSimilarity(ReadableArray a, ReadableArray b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        int length = Math.min(a.size(), b.size());
        
        for (int i = 0; i < length; i++) {
          double valA = a.getDouble(i);
          double valB = b.getDouble(i);
          
          dotProduct += valA * valB;
          normA += valA * valA;
          normB += valB * valB;
        }
        
        if (normA == 0 || normB == 0) {
          return 0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
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
    tasks.put(task, "hybrid-search-" + contextId);
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
        
        // Get battery temperature
        int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        float batteryTemp = temperature / 10.0f; // Temperature is in tenths of a degree
        
        result.putInt("batteryLevel", (int)batteryPct);
        result.putBoolean("isCharging", isCharging);
        result.putDouble("batteryTemperature", batteryTemp);
        
        // Memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        result.putDouble("availableMemoryMB", memoryInfo.availMem / (1024.0 * 1024.0));
        result.putDouble("totalMemoryMB", memoryInfo.totalMem / (1024.0 * 1024.0));
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        // Calculate memory usage percentage
        double memoryUsagePercent = 100.0 * (1.0 - (memoryInfo.availMem / (double)memoryInfo.totalMem));
        result.putDouble("memoryUsagePercent", memoryUsagePercent);
        
        // Thermal status on newer Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            float headroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            result.putDouble("thermalHeadroom", headroom);
            
            // Get thermal status
            int thermalStatus = powerManager.getCurrentThermalStatus();
            result.putInt("thermalStatus", thermalStatus);
            
            // Add human-readable thermal status
            String thermalStatusString;
            switch (thermalStatus) {
              case android.os.PowerManager.THERMAL_STATUS_NONE:
                thermalStatusString = "NONE";
                break;
              case android.os.PowerManager.THERMAL_STATUS_LIGHT:
                thermalStatusString = "LIGHT";
                break;
              case android.os.PowerManager.THERMAL_STATUS_MODERATE:
                thermalStatusString = "MODERATE";
                break;
              case android.os.PowerManager.THERMAL_STATUS_SEVERE:
                thermalStatusString = "SEVERE";
                break;
              case android.os.PowerManager.THERMAL_STATUS_CRITICAL:
                thermalStatusString = "CRITICAL";
                break;
              case android.os.PowerManager.THERMAL_STATUS_EMERGENCY:
                thermalStatusString = "EMERGENCY";
                break;
              case android.os.PowerManager.THERMAL_STATUS_SHUTDOWN:
                thermalStatusString = "SHUTDOWN";
                break;
              default:
                thermalStatusString = "UNKNOWN";
            }
            result.putString("thermalStatusString", thermalStatusString);
          } catch (Exception e) {
            result.putNull("thermalHeadroom");
            result.putString("thermalError", e.getMessage());
          }
        }
        
        // Add timestamp
        result.putDouble("timestamp", System.currentTimeMillis());
        
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
  
  /**
   * Check if embedding generation should be deferred based on current device conditions
   */
  @ReactMethod
  public void shouldDeferEmbeddingGeneration(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        // Get battery status
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (int)(level * 100 / (float)scale);
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        // Get thermal status
        float thermalHeadroom = 1.0f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            thermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
          } catch (Exception e) {
            // Default to 0.5 if we can't get the thermal headroom
            thermalHeadroom = 0.5f;
          }
        }
        
        // Determine if we should defer
        boolean shouldDefer = false;
        String reason = "";
        
        if (batteryLevel < 20 && !isCharging) {
          shouldDefer = true;
          reason = "Low battery (" + batteryLevel + "%) and not charging";
        } else if (thermalHeadroom < 0.15f) {
          shouldDefer = true;
          reason = "Device is thermally throttled (headroom: " + thermalHeadroom + ")";
        }
        
        // Get memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        if (memoryInfo.lowMemory) {
          shouldDefer = true;
          reason = "Device is low on memory";
        }
        
        result.putBoolean("shouldDefer", shouldDefer);
        result.putString("reason", reason);
        result.putInt("batteryLevel", batteryLevel);
        result.putBoolean("isCharging", isCharging);
        result.putFloat("thermalHeadroom", thermalHeadroom);
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "defer-check");
  }
  
  /**
   * Manage embedding expiration and cleanup
   */
  @ReactMethod
  public void manageEmbeddingStorage(ReadableMap options, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        // Get device resource status
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = reactContext.getApplicationContext().registerReceiver(null, ifilter);
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryLevel = (int)(level * 100 / (float)scale);
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        // Get memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        double availableMemoryMB = memoryInfo.availMem / (1024.0 * 1024.0);
        
        // Default expiration time (7 days in milliseconds)
        long defaultExpirationTime = 7 * 24 * 60 * 60 * 1000;
        
        // Get expiration time from options or use default
        long expirationTime = options != null && options.hasKey("expirationTime") ? 
            (long)options.getDouble("expirationTime") : defaultExpirationTime;
        
        // Check if we should perform emergency cleanup
        boolean emergencyCleanup = batteryLevel < 5 && !isCharging;
        
        // Check if we should perform memory-pressure cleanup
        boolean memoryPressureCleanup = memoryInfo.lowMemory || availableMemoryMB < 100;
        
        // Current time
        long currentTime = System.currentTimeMillis();
        
        // Counters for statistics
        int expiredCount = 0;
        int emergencyCount = 0;
        int shadowCount = 0;
        int retainedCount = 0;
        
        // Process embeddings
        // In a real implementation, this would interact with a database
        // For now, we'll just return the statistics
        
        result.putInt("expiredCount", expiredCount);
        result.putInt("emergencyCount", emergencyCount);
        result.putInt("shadowCount", shadowCount);
        result.putInt("retainedCount", retainedCount);
        result.putBoolean("emergencyCleanupPerformed", emergencyCleanup);
        result.putBoolean("memoryPressureCleanupPerformed", memoryPressureCleanup);
        result.putInt("batteryLevel", batteryLevel);
        result.putBoolean("isCharging", isCharging);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "manage-embeddings");
  }
}
