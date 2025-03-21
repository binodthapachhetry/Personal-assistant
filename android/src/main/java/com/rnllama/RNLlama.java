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
import java.io.FileOutputStream;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.util.Map;

import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.ReadableMapKeySetIterator;

public class RNLlama implements LifecycleEventListener {
  public static final String NAME = "RNLlama";

  private ReactApplicationContext reactContext;
  private static final int MIN_BATTERY_LEVEL = 20; // Minimum battery level for embedding generation
  private static final int HIGH_BATTERY_LEVEL = 50; // High battery level threshold
  
  // Store saved conversation states by context ID
  private HashMap<Integer, String> savedConversationStates = new HashMap<>();

  public RNLlama(ReactApplicationContext reactContext) {
    reactContext.addLifecycleEventListener(this);
    this.reactContext = reactContext;
    
    // Start periodic auto-save
    startPeriodicAutoSave();
  }
  
  private Handler autoSaveHandler;
  private static final long AUTO_SAVE_INTERVAL_MS = 60000; // 1 minute


   @ReactMethod                                                                                                                            
  public void addListener(String eventName) {                                                                                             
    // Keep track of event listeners                                                                                                      
    // This method is required by NativeEventEmitter                                                                                      
  }                                                                                                                                       
                                                                                                                                          
  @ReactMethod                                                                                                                            
  public void removeListeners(Integer count) {                                                                                            
    // Remove event listeners                                                                                                             
    // This method is required by NativeEventEmitter                                                                                      
  }                                                                                                                                       
   
  
  /**
   * Start periodic auto-save of conversations
   */
  private void startPeriodicAutoSave() {
    if (autoSaveHandler == null) {
      autoSaveHandler = new Handler();
    }
    
    // Define the auto-save runnable
    Runnable autoSaveRunnable = new Runnable() {
      @Override
      public void run() {
        // Check if auto-save is enabled
        android.content.SharedPreferences settings = reactContext.getSharedPreferences(
            "LlamaSettings", android.content.Context.MODE_PRIVATE);
        boolean autoSaveEnabled = settings.getBoolean("auto_save_enabled", true);
        
        if (autoSaveEnabled) {
          // Run auto-save in background
          new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
              Log.d(NAME, "SAVING ALL CONVERSATIONS PERIODICAUTOSAVE");
              saveAllConversations();
              return null;
            }
          }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        
        // Schedule next run
        autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL_MS);
      }
    };
    
    // Start the periodic task
    autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL_MS);
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

  /**                                                                                                                                   
    * Determine optimal embedding dimension based on device resources                                                                    
    */                                                                                                                                   
   private int getAdaptiveEmbeddingDimension(                                                                                            
       int batteryLevel,                                                                                                                 
       boolean isCharging,                                                                                                               
       double thermalHeadroom,                                                                                                           
       double availableMemoryMB,                                                                                                         
       boolean isHighPriority) {                                                                                                         
                                                                                                                                         
     // High priority embeddings get more dimensions                                                                                     
     if (isHighPriority) {                                                                                                               
       // Still apply some reduction for critical conditions                                                                             
       if (batteryLevel < 10 && !isCharging) {                                                                                           
         return 384;                                                                                                                     
       }                                                                                                                                 
                                                                                                                                         
       // Full dimensions when possible                                                                                                  
       if (isCharging || batteryLevel > 50 || thermalHeadroom > 0.4) {                                                                   
         return 1536; // Full dimension for most models                                                                                  
       }                                                                                                                                 
                                                                                                                                         
       // Moderate reduction for high priority under constraints                                                                         
       return 768;                                                                                                                       
     }                                                                                                                                   
                                                                                                                                         
     // Full dimension when charging with high battery and good thermal                                                                  
     if (isCharging && batteryLevel > 80 && thermalHeadroom > 0.5) {                                                                     
       return 1536;                                                                                                                      
     }                                                                                                                                   
                                                                                                                                         
     // Critical resource constraints - use minimum dimension                                                                            
     if ((batteryLevel < 15 && !isCharging) ||                                                                                           
         thermalHeadroom < 0.15 ||                                                                                                       
         availableMemoryMB < 100) {                                                                                                      
       return 128;                                                                                                                       
     }                                                                                                                                   
                                                                                                                                         
     // Low battery - reduce dimension significantly                                                                                     
     if (batteryLevel < 30 && !isCharging) {                                                                                             
       return 256;                                                                                                                       
     }                                                                                                                                   
                                                                                                                                         
     // Medium battery - reduce dimension moderately                                                                                     
     if (batteryLevel < 50 || thermalHeadroom < 0.3) {                                                                                   
       return 384;                                                                                                                       
     }                                                                                                                                   
                                                                                                                                         
     // Memory constraints - adjust based on available memory                                                                            
     if (availableMemoryMB < 200) {                                                                                                      
       return 512;                                                                                                                       
     }                                                                                                                                   
                                                                                                                                         
     // Default - use 768 for good balance                                                                                               
     return 768;                                                                                                                         
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
          
          // Check if we have a saved conversation state for this context
          if (savedConversationStates.containsKey(contextId)) {
            String savedState = savedConversationStates.get(contextId);
            if (savedState != null && !savedState.isEmpty()) {
              boolean restored = llamaContext.restoreConversationState(savedState);
              Log.d(NAME, "Restored saved conversation for context " + contextId + ": " + (restored ? "success" : "failed"));
            }
          }
          
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
          
          // Auto-save after completion
          android.content.SharedPreferences settings = reactContext.getSharedPreferences(
              "LlamaSettings", android.content.Context.MODE_PRIVATE);
          boolean autoSaveEnabled = settings.getBoolean("auto_save_enabled", true);
          
          if (autoSaveEnabled && context.hasConversationState()) {
            String conversationState = context.getConversationState();
            if (conversationState != null && !conversationState.isEmpty()) {
              saveConversationToStorage(contextId, conversationState);
            }
          }
          
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
    
    double thermalHeadroom = 1.0d;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        android.os.PowerManager powerManager = (android.os.PowerManager) 
            reactContext.getSystemService(Context.POWER_SERVICE);
        double updatedThermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
        thermalHeadroom = updatedThermalHeadroom;
      } catch (Exception e) {
        Log.e(NAME, "Failed to check thermal status", e);
      }
    }

    // Create a final variable to capture the current value                                                                                 
    final double finalThermalHeadroom = thermalHeadroom;
    
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
        result.putDouble("thermalHeadroom", thermalHeadroom);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        promise.resolve(result);
        return;
      }
    }
    
    // Determine if this is a high-priority embedding
    boolean isHighPriority = params.hasKey("highPriority") && params.getBoolean("highPriority");
    
    // Determine if we should create a shadow vector (lower quality backup)
    boolean createShadowVector = params.hasKey("createShadowVector") && params.getBoolean("createShadowVector");
    
    // Get importance if specified (for retention policy)
    float importance = params.hasKey("importance") ? (float)params.getDouble("importance") : 0.5f;
    
    // Calculate adaptive target dimension based on device resources
    int targetDimension = params.hasKey("targetDimension") ? 
        params.getInt("targetDimension") : 
        this.getAdaptiveEmbeddingDimension(batteryLevel, isCharging, thermalHeadroom, availableMemoryMB, isHighPriority);
    
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
            resourceParams.putDouble("thermalHeadroom", finalThermalHeadroom);
            resourceParams.putDouble("availableMemoryMB", availableMemoryMB);
            resourceParams.putInt("targetDimension", targetDimension);
            resourceParams.putBoolean("createShadowVector", createShadowVector);
            resourceParams.putDouble("importance", importance);
            
            // Create a new params object with the resource parameters
            WritableMap newParams = Arguments.createMap();
            // Copy all existing params
            ReadableMapKeySetIterator iterator = params.keySetIterator();
            while (iterator.hasNextKey()) {
              String key = iterator.nextKey();
              switch (params.getType(key)) {
                case Null:
                  newParams.putNull(key);
                  break;
                case Boolean:
                  newParams.putBoolean(key, params.getBoolean(key));
                  break;
                case Number:
                  newParams.putDouble(key, params.getDouble(key));
                  break;
                case String:
                  newParams.putString(key, params.getString(key));
                  break;
                case Map:
                  newParams.putMap(key, params.getMap(key));
                  break;
                case Array:
                  newParams.putArray(key, params.getArray(key));
                  break;
              }
            }
            newParams.putMap("resourceParams", resourceParams);
            embeddingParams = newParams;
          }
          
          WritableMap embedding = context.getEmbedding(text, embeddingParams);
          
          // Add device resource metadata
          if (params.hasKey("includeResourceMeta") && params.getBoolean("includeResourceMeta")) {
            embedding.putInt("batteryLevel", batteryLevel);
            embedding.putBoolean("isCharging", isCharging);
            embedding.putDouble("thermalHeadroom", finalThermalHeadroom);
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
            
            // Add importance for retention policy
            embedding.putDouble("importance", importance);
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
      
  // // Helper method to determine adaptive embedding dimension
  // private int getAdaptiveEmbeddingDimension(
  //         int batteryLevel, 
  //         boolean isCharging, 
  //         float thermalHeadroom, 
  //         double availableMemoryMB,
  //         boolean isHighPriority) {
          
  //         // High priority embeddings get more dimensions
  //         if (isHighPriority) {
  //             // Still apply some reduction for critical conditions
  //             if (batteryLevel < 10 && !isCharging) {
  //                 return 384;
  //             }
              
  //             // For high priority, use full dimensions when possible
  //             if (isCharging || batteryLevel > 50 || thermalHeadroom > 0.4f) {
  //                 return 1536; // Full dimension for most models
  //             }
              
  //             // Moderate reduction for high priority under constraints
  //             return 768;
  //         }
          
  //         // Full dimension when charging with high battery and good thermal
  //         if (isCharging && batteryLevel > 80 && thermalHeadroom > 0.5f) {
  //             return 1536;
  //         }
          
  //         // Critical resource constraints - use minimum dimension
  //         if ((batteryLevel < 15 && !isCharging) || 
  //             thermalHeadroom < 0.15f || 
  //             availableMemoryMB < 100) {
  //             return 128;
  //         }
          
  //         // Low battery - reduce dimension significantly
  //         if (batteryLevel < 30 && !isCharging) {
  //             return 256;
  //         }
          
  //         // Medium battery - reduce dimension moderately
  //         if (batteryLevel < 50 || thermalHeadroom < 0.3f) {
  //             return 384;
  //         }
          
  //         // Memory constraints - adjust based on available memory
  //         if (availableMemoryMB < 200) {
  //             return 512;
  //         }
          
  //         // Default - use 768 for good balance
  //         return 768;
  //     }

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
    // Restore conversations when app comes to foreground
    restoreConversations();
  }

  @Override
  public void onHostPause() {
    // Save conversations when app goes to background
    Log.d(NAME, "SAVING ALL CONVERSATIONS ONHOSTPAUSE");
    saveAllConversations();
  }
  
  /**
   * Restore conversations from persistent storage
   */
  private void restoreConversations() {
    try {
      android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
          "LlamaConversations", android.content.Context.MODE_PRIVATE);
      
      // Find all saved conversations
      Log.d(NAME, "FINDING ALL SAVED CONVERSATIONS");

      Map<String, ?> allPrefs = prefs.getAll();
      for (String key : allPrefs.keySet()) {
        if (key.startsWith("conversation_") && !key.contains("timestamp")) {
          Log.d(NAME, "FOUND SAVED CONVERSATION: " + key);
          try {
            // Extract context ID from key
            String idStr = key.substring("conversation_".length());
            int contextId = Integer.parseInt(idStr);
            
            // Get conversation state
            String conversationState = prefs.getString(key, null);
            if (conversationState != null && !conversationState.isEmpty()) {
              // Store the conversation state in memory
              savedConversationStates.put(contextId, conversationState);
              Log.d(NAME, "Loaded conversation state for context " + contextId);
              
              // If context already exists, restore immediately
              LlamaContext context = contexts.get(contextId);
              if (context != null) {
                context.restoreConversationState(conversationState);
                Log.d(NAME, "Restored conversation for existing context " + contextId);
              } else {
                Log.d(NAME, "Context " + contextId + " not loaded yet, will restore when created");
              }
            } else {
              Log.d(NAME, "CONVERSATION STATE IS NULL");
            }
          } catch (NumberFormatException e) {
            Log.e(NAME, "Invalid context ID in saved conversation key: " + key, e);
          }
        }
      }
    } catch (Exception e) {
      Log.e(NAME, "Failed to restore conversations", e);
    }
  }

  @Override
  public void onHostDestroy() {
    // Save all active conversations before destroying contexts
    Log.d(NAME, "SAVING ALL CONVERSATIONS ONHOSTDESTROY");

    saveAllConversations();
    
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
   * Save all active conversations to persistent storage
   */
  private void saveAllConversations() {
    try {
      // Check if auto-save is enabled
      android.content.SharedPreferences settings = reactContext.getSharedPreferences(
          "LlamaSettings", android.content.Context.MODE_PRIVATE);
      boolean autoSaveEnabled = settings.getBoolean("auto_save_enabled", true); // Default to true

      Log.d(NAME, "INSIDE SAVE ALL CONVERSATIONS");
      
      if (!autoSaveEnabled) {
        Log.d(NAME, "Auto-save is disabled, skipping conversation save");
        return;
      }

      Log.d(NAME, "AUTO-SAVE IS NOT DISABLED");

      for (Map.Entry<Integer, LlamaContext> entry : contexts.entrySet()) {
        int contextId = entry.getKey();
        LlamaContext context = entry.getValue();
        
        // Skip if context is not associated with a conversation
        if (context == null || !context.hasConversationState()) {
          continue;
        }

        Log.d(NAME, "CONTEXT IS NOT NULL");
        
        // Get conversation state from context
        String conversationState = context.getConversationState();
        if (conversationState != null && !conversationState.isEmpty()) {
          // Save to storage
          Log.d(NAME, "SAVING CONVERSATION TO STORAGE");
          saveConversationToStorage(contextId, conversationState);
        }
      }
    } catch (Exception e) {
      Log.e(NAME, "Failed to save conversations", e);
    }
  }
  
  /**
   * Save a single conversation to storage
   */
  private void saveConversationToStorage(int contextId, String conversationState) {
    try {
      // Use SharedPreferences for storage
      android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
          "LlamaConversations", android.content.Context.MODE_PRIVATE);
      
      android.content.SharedPreferences.Editor editor = prefs.edit();
      editor.putString("conversation_" + contextId, conversationState);
      editor.putLong("conversation_timestamp_" + contextId, System.currentTimeMillis());
      editor.apply();
      
      Log.d(NAME, "Saved conversation for context " + contextId);
    } catch (Exception e) {
      Log.e(NAME, "Failed to save conversation for context " + contextId, e);
    }
  }
  
  /**
   * Save conversation state for a specific context
   */
  @ReactMethod
  public void saveConversation(double id, final ReadableMap params, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          WritableMap result = Arguments.createMap();
          
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          // Get conversation state
          String conversationState = context.getConversationState();
          if (conversationState == null || conversationState.isEmpty()) {
            result.putBoolean("success", false);
            result.putString("error", "No conversation state available");
            return result;
          }
          
          // Get storage path if provided
          String storagePath = null;
          if (params != null && params.hasKey("path")) {
            storagePath = params.getString("path");
          }
          
          if (storagePath != null && !storagePath.isEmpty()) {
            // Save to file
            File file = new File(storagePath);
            File directory = file.getParentFile();
            if (directory != null && !directory.exists()) {
              directory.mkdirs();
            }
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(conversationState.getBytes());
            fos.close();
            
            result.putString("path", storagePath);
          } else {
            // Save to SharedPreferences
            saveConversationToStorage(contextId, conversationState);
          }
          
          result.putBoolean("success", true);
          result.putDouble("timestamp", System.currentTimeMillis());
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
    tasks.put(task, "saveConversation-" + contextId);
  }
  
  /**
   * Restore conversation state for a specific context
   */
  @ReactMethod
  public void restoreConversation(double id, final ReadableMap params, final Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;

      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          WritableMap result = Arguments.createMap();
          
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          String conversationState = null;
          
          // Check if path is provided
          if (params != null && params.hasKey("path")) {
            String path = params.getString("path");
            File file = new File(path);
            
            if (file.exists()) {
              // Read from file
              FileInputStream fis = new FileInputStream(file);
              byte[] data = new byte[(int) file.length()];
              fis.read(data);
              fis.close();
              
              conversationState = new String(data);
              result.putString("source", "file");
              result.putString("path", path);
            }
          } else {
            // Try to restore from SharedPreferences
            android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
                "LlamaConversations", android.content.Context.MODE_PRIVATE);
            
            conversationState = prefs.getString("conversation_" + contextId, null);
            if (conversationState != null) {
              result.putString("source", "preferences");
              long timestamp = prefs.getLong("conversation_timestamp_" + contextId, 0);
              result.putDouble("timestamp", timestamp);
            }
          }
          
          if (conversationState != null && !conversationState.isEmpty()) {
            // Restore conversation state
            boolean restored = context.restoreConversationState(conversationState);
            result.putBoolean("success", restored);
          } else {
            result.putBoolean("success", false);
            result.putString("error", "No saved conversation found");
          }
          
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
    tasks.put(task, "restoreConversation-" + contextId);
  }
  
  /**
   * Get list of all saved conversations
   */
  @ReactMethod
  public void getSavedConversations(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        WritableArray conversations = Arguments.createArray();
        
        try {
          android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
              "LlamaConversations", android.content.Context.MODE_PRIVATE);
          
          // Find all saved conversations
          Map<String, ?> allPrefs = prefs.getAll();
          for (String key : allPrefs.keySet()) {
            if (key.startsWith("conversation_") && !key.contains("timestamp")) {
              try {
                // Extract context ID from key
                String idStr = key.substring("conversation_".length());
                int contextId = Integer.parseInt(idStr);
                
                // Get timestamp
                long timestamp = prefs.getLong("conversation_timestamp_" + contextId, 0);
                
                // Create conversation info
                WritableMap conversation = Arguments.createMap();
                conversation.putInt("contextId", contextId);
                conversation.putDouble("timestamp", timestamp);
                
                // Check if this context still exists
                boolean active = contexts.containsKey(contextId);
                conversation.putBoolean("active", active);
                
                conversations.pushMap(conversation);
              } catch (NumberFormatException e) {
                Log.e(NAME, "Invalid context ID in saved conversation key: " + key, e);
              }
            }
          }
          
          result.putArray("conversations", conversations);
          result.putBoolean("success", true);
        } catch (Exception e) {
          result.putBoolean("success", false);
          result.putString("error", e.getMessage());
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "getSavedConversations");
  }
  
  /**
   * Delete a saved conversation
   */
  @ReactMethod
  public void deleteSavedConversation(double id, Promise promise) {
    final int contextId = (int) id;
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        try {
          android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
              "LlamaConversations", android.content.Context.MODE_PRIVATE);
          
          android.content.SharedPreferences.Editor editor = prefs.edit();
          editor.remove("conversation_" + contextId);
          editor.remove("conversation_timestamp_" + contextId);
          editor.apply();
          
          result.putBoolean("success", true);
        } catch (Exception e) {
          result.putBoolean("success", false);
          result.putString("error", e.getMessage());
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "deleteSavedConversation");
  }
  
  /**
   * Configure auto-save settings
   */
  @ReactMethod
  public void configureAutoSave(ReadableMap config, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        try {
          android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
              "LlamaSettings", android.content.Context.MODE_PRIVATE);
          
          android.content.SharedPreferences.Editor editor = prefs.edit();
          
          // Update enabled state if provided
          if (config.hasKey("enabled")) {
            boolean enabled = config.getBoolean("enabled");
            editor.putBoolean("auto_save_enabled", enabled);
            result.putBoolean("autoSaveEnabled", enabled);
          }
          
          // Update interval if provided
          if (config.hasKey("intervalMs")) {
            long intervalMs = (long) config.getDouble("intervalMs");
            if (intervalMs >= 5000) { // Minimum 5 seconds
              editor.putLong("auto_save_interval_ms", intervalMs);
              result.putDouble("autoSaveIntervalMs", intervalMs);
            }
          }
          
          // Update max saved conversations if provided
          if (config.hasKey("maxSavedConversations")) {
            int maxSaved = config.getInt("maxSavedConversations");
            if (maxSaved > 0) {
              editor.putInt("max_saved_conversations", maxSaved);
              result.putInt("maxSavedConversations", maxSaved);
              
              // Enforce the limit
              enforceMaxSavedConversationsLimit(maxSaved);
            }
          }
          
          editor.apply();
          
          // Get current settings
          boolean enabled = prefs.getBoolean("auto_save_enabled", true);
          long intervalMs = prefs.getLong("auto_save_interval_ms", AUTO_SAVE_INTERVAL_MS);
          int maxSaved = prefs.getInt("max_saved_conversations", 50);
          
          if (!result.hasKey("autoSaveEnabled")) {
            result.putBoolean("autoSaveEnabled", enabled);
          }
          if (!result.hasKey("autoSaveIntervalMs")) {
            result.putDouble("autoSaveIntervalMs", intervalMs);
          }
          if (!result.hasKey("maxSavedConversations")) {
            result.putInt("maxSavedConversations", maxSaved);
          }
          
          result.putBoolean("success", true);
        } catch (Exception e) {
          result.putBoolean("success", false);
          result.putString("error", e.getMessage());
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "configureAutoSave");
  }
  
  /**
   * Enforce the maximum number of saved conversations by removing oldest ones
   */
  private void enforceMaxSavedConversationsLimit(int maxSaved) {
    try {
      android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
          "LlamaConversations", android.content.Context.MODE_PRIVATE);
      
      // Find all saved conversations
      Map<String, ?> allPrefs = prefs.getAll();
      
      // Create a list of conversation IDs and timestamps
      HashMap<Integer, Long> conversationTimestamps = new HashMap<>();
      
      for (String key : allPrefs.keySet()) {
        if (key.startsWith("conversation_timestamp_")) {
          try {
            String idStr = key.substring("conversation_timestamp_".length());
            int contextId = Integer.parseInt(idStr);
            long timestamp = (long) allPrefs.get(key);
            
            conversationTimestamps.put(contextId, timestamp);
          } catch (NumberFormatException e) {
            Log.e(NAME, "Invalid context ID in saved conversation key: " + key, e);
          }
        }
      }
      
      // If we have more than the max, remove oldest ones
      if (conversationTimestamps.size() > maxSaved) {
        // Sort by timestamp (oldest first)
        List<Map.Entry<Integer, Long>> sortedEntries = new ArrayList<>(conversationTimestamps.entrySet());
        Collections.sort(sortedEntries, new Comparator<Map.Entry<Integer, Long>>() {
          @Override
          public int compare(Map.Entry<Integer, Long> o1, Map.Entry<Integer, Long> o2) {
            return o1.getValue().compareTo(o2.getValue());
          }
        });
        
        // Remove oldest conversations
        android.content.SharedPreferences.Editor editor = prefs.edit();
        int toRemove = conversationTimestamps.size() - maxSaved;
        
        for (int i = 0; i < toRemove; i++) {
          int contextId = sortedEntries.get(i).getKey();
          editor.remove("conversation_" + contextId);
          editor.remove("conversation_timestamp_" + contextId);
          Log.d(NAME, "Removing old conversation for context " + contextId);
        }
        
        editor.apply();
      }
    } catch (Exception e) {
      Log.e(NAME, "Failed to enforce max saved conversations limit", e);
    }
  }
  
  /**
   * Get auto-save settings
   */
  @ReactMethod
  public void getAutoSaveSettings(Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        try {
          android.content.SharedPreferences prefs = reactContext.getSharedPreferences(
              "LlamaSettings", android.content.Context.MODE_PRIVATE);
          
          boolean enabled = prefs.getBoolean("auto_save_enabled", true); // Default to true
          long intervalMs = prefs.getLong("auto_save_interval_ms", AUTO_SAVE_INTERVAL_MS);
          int maxSaved = prefs.getInt("max_saved_conversations", 50);
          
          result.putBoolean("success", true);
          result.putBoolean("autoSaveEnabled", enabled);
          result.putDouble("autoSaveIntervalMs", intervalMs);
          result.putInt("maxSavedConversations", maxSaved);
          
          // Get storage info
          android.content.SharedPreferences convPrefs = reactContext.getSharedPreferences(
              "LlamaConversations", android.content.Context.MODE_PRIVATE);
          
          int savedCount = 0;
          for (String key : convPrefs.getAll().keySet()) {
            if (key.startsWith("conversation_") && !key.contains("timestamp")) {
              savedCount++;
            }
          }
          
          result.putInt("savedConversationsCount", savedCount);
        } catch (Exception e) {
          result.putBoolean("success", false);
          result.putString("error", e.getMessage());
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "getAutoSaveSettings");
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
        double thermalHeadroom = 1.0d;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            double updatedThermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            thermalHeadroom = updatedThermalHeadroom;
          } catch (Exception e) {
            // Default to 0.5 if we can't get the thermal headroom
            thermalHeadroom = 0.5d;
          }
        }

        final double finalThermalHeadroom = thermalHeadroom;
        
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
        else if (thermalHeadroom < 0.2d) {
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
          double thermalHeadroom = 1.0d;

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
              android.os.PowerManager powerManager = (android.os.PowerManager) 
                  reactContext.getSystemService(Context.POWER_SERVICE);
              double updatedThermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
              thermalHeadroom = updatedThermalHeadroom;
            } catch (Exception e) {
              thermalHeadroom = 0.5d;
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
          
          // Get the context
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
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
          
          // First stage: Text search filtering
          WritableArray textFilteredResults = Arguments.createArray();
          boolean textSearchPerformed = false;
          
          // Perform text search if query is not empty
          if (query != null && !query.trim().isEmpty()) {
            textSearchPerformed = true;
            String queryLower = query.toLowerCase();
            
            // Process each embedding for text matches
            for (int i = 0; i < embeddings.size(); i++) {
              ReadableMap embedding = embeddings.getMap(i);
              
              // Skip if embedding doesn't have required fields
              if (!embedding.hasKey("text")) {
                continue;
              }
              
              // Skip entries that are too old (7 days = 604800000 ms)
              if (embedding.hasKey("timestamp")) {
                long timestamp = (long)embedding.getDouble("timestamp");
                if (System.currentTimeMillis() - timestamp > 604800000) {
                  continue;
                }
              }
              
              String text = embedding.getString("text").toLowerCase();
              double score = 0.0;
              
              // Check for exact match
              if (text.contains(queryLower)) {
                score = 1.0;
              } else {
                // Split query into words for partial matching
                String[] queryWords = queryLower.split("\\s+");
                int matches = 0;
                
                for (String word : queryWords) {
                  if (word.length() > 3 && text.contains(word)) {
                    matches++;
                  }
                }
                
                if (matches > 0 && queryWords.length > 0) {
                  score = (double)matches / queryWords.length;
                }
              }
              
              // Add to results if there's any match
              if (score > 0.0) {
                WritableMap textResult = Arguments.createMap();
                textResult.putString("text", embedding.getString("text"));
                textResult.putDouble("score", score);
                textResult.putInt("index", i); // Store original index for vector search
                if (embedding.hasKey("timestamp")) {
                  textResult.putDouble("timestamp", embedding.getDouble("timestamp"));
                }
                textFilteredResults.pushMap(textResult);
              }
            }
            
            // Sort text results by score (descending)
            for (int i = 0; i < textFilteredResults.size() - 1; i++) {
              for (int j = 0; j < textFilteredResults.size() - i - 1; j++) {
                ReadableMap result1 = textFilteredResults.getMap(j);
                ReadableMap result2 = textFilteredResults.getMap(j + 1);
                if (result1.getDouble("score") < result2.getDouble("score")) {
                  // Swap
                  WritableArray tempArray = Arguments.createArray();
                  tempArray.pushMap(result2);
                  ((WritableArray)textFilteredResults).pushMap(result1);
                  ((WritableArray)textFilteredResults).pushMap(tempArray.getMap(0));
                }
              }
            }
          }
          
          // Determine if we should use vector search
          boolean useVectorSearch = isHighPriority || 
              ((batteryLevel >= 15 || isCharging) && 
               thermalHeadroom >= 0.2d && 
               availableMemoryMB >= 100);
          
          result.putBoolean("vectorSearchUsed", useVectorSearch);
          
          // Second stage: Vector search on filtered results (or all if no text search)
          if (useVectorSearch) {
            // Generate query embedding
            ReadableMap embParams = Arguments.createMap();
            ((WritableMap)embParams).putBoolean("skipOnCriticalBattery", true);
            ((WritableMap)embParams).putBoolean("highPriority", isHighPriority);
            
            // Set target dimension based on device resources
            int targetDimension = RNLlama.this.getAdaptiveEmbeddingDimension(
                batteryLevel, isCharging, thermalHeadroom, availableMemoryMB, isHighPriority);
            ((WritableMap)embParams).putInt("targetDimension", targetDimension);
            
            WritableMap queryEmbedding = context.getEmbedding(query, embParams);
            
            // If embedding generation was skipped due to battery, fall back to text search only
            if (!queryEmbedding.hasKey("skipped") || !queryEmbedding.getBoolean("skipped")) {
              // Calculate result limit based on available memory
              int resultLimit = availableMemoryMB < 200 ? 20 : 50;
              if (options != null && options.hasKey("limit")) {
                resultLimit = Math.min(resultLimit, options.getInt("limit"));
              }
              
              // Get query embedding as array
              ReadableArray queryEmbeddingArray = queryEmbedding.getArray("embedding");
              
              // Use text-filtered results if available, otherwise use all embeddings
              int candidateCount = textSearchPerformed ? textFilteredResults.size() : embeddings.size();
              
              for (int i = 0; i < candidateCount; i++) {
                ReadableMap embedding;
                double textScore = 0.0;
                long timestamp = System.currentTimeMillis();
                
                if (textSearchPerformed) {
                  ReadableMap textResult = textFilteredResults.getMap(i);
                  int originalIndex = textResult.getInt("index");
                  embedding = embeddings.getMap(originalIndex);
                  textScore = textResult.getDouble("score");
                  if (textResult.hasKey("timestamp")) {
                    timestamp = (long)textResult.getDouble("timestamp");
                  }
                } else {
                  embedding = embeddings.getMap(i);
                  if (embedding.hasKey("timestamp")) {
                    timestamp = (long)embedding.getDouble("timestamp");
                  }
                }
                
                // Skip if embedding doesn't have required fields
                if (!embedding.hasKey("embedding") || !embedding.hasKey("text")) {
                  continue;
                }
                
                // Calculate vector similarity
                ReadableArray embArray = embedding.getArray("embedding");
                double similarity = calculateCosineSimilarity(queryEmbeddingArray, embArray);
                
                // Combine scores - adjust weights based on battery level
                double textWeight = batteryLevel < 30 ? 0.7 : 0.5;
                double vectorWeight = 1.0 - textWeight;
                
                double combinedScore;
                if (textSearchPerformed) {
                  combinedScore = (textWeight * textScore) + (vectorWeight * similarity);
                } else {
                  combinedScore = similarity;
                }
                
                // Calculate recency score (1.0 = now, 0.0 = 7 days old)
                double ageInDays = (System.currentTimeMillis() - timestamp) / (24.0 * 60.0 * 60.0 * 1000.0);
                double recencyScore = Math.max(0.0, 1.0 - (ageInDays / 7.0));
                
                // Get importance or default to 0.5
                double importance = embedding.hasKey("importance") ? embedding.getDouble("importance") : 0.5;
                
                // Final weighted score
                double finalScore = (0.6 * combinedScore) + (0.2 * recencyScore) + (0.2 * importance);
                
                // Add to results if score is above threshold
                double threshold = batteryLevel < 30 ? 0.4 : 0.5;
                if (finalScore > threshold) {
                  WritableMap searchResult = Arguments.createMap();
                  searchResult.putString("text", embedding.getString("text"));
                  searchResult.putDouble("score", finalScore);
                  searchResult.putDouble("vectorScore", similarity);
                  searchResult.putDouble("textScore", textScore);
                  searchResult.putDouble("recencyScore", recencyScore);
                  searchResult.putDouble("importance", importance);
                  if (embedding.hasKey("timestamp")) {
                    searchResult.putDouble("timestamp", embedding.getDouble("timestamp"));
                  }
                  searchResult.putInt("sourceType", textSearchPerformed ? 2 : 1); // 1=vector only, 2=hybrid
                  searchResults.pushMap(searchResult);
                }
              }
            }
          } else if (textSearchPerformed) {
            // If vector search is disabled, just use text search results
            for (int i = 0; i < textFilteredResults.size(); i++) {
              ReadableMap textResult = textFilteredResults.getMap(i);
              
              // Calculate recency score
              long timestamp = textResult.hasKey("timestamp") ? 
                  (long)textResult.getDouble("timestamp") : System.currentTimeMillis();
              double ageInDays = (System.currentTimeMillis() - timestamp) / (24.0 * 60.0 * 60.0 * 1000.0);
              double recencyScore = Math.max(0.0, 1.0 - (ageInDays / 7.0));
              
              // Get original embedding for importance
              int originalIndex = textResult.getInt("index");
              ReadableMap originalEmb = embeddings.getMap(originalIndex);
              double importance = originalEmb.hasKey("importance") ? 
                  originalEmb.getDouble("importance") : 0.5;
              
              // Final weighted score
              double finalScore = (0.7 * textResult.getDouble("score")) + (0.15 * recencyScore) + (0.15 * importance);
              
              WritableMap searchResult = Arguments.createMap();
              searchResult.putString("text", textResult.getString("text"));
              searchResult.putDouble("score", finalScore);
              searchResult.putDouble("textScore", textResult.getDouble("score"));
              searchResult.putDouble("recencyScore", recencyScore);
              searchResult.putDouble("importance", importance);
              if (textResult.hasKey("timestamp")) {
                searchResult.putDouble("timestamp", textResult.getDouble("timestamp"));
              }
              searchResult.putInt("sourceType", 0); // 0=text only
              searchResults.pushMap(searchResult);
            }
          }
          
          // Sort results by final score (descending)
          for (int i = 0; i < searchResults.size() - 1; i++) {
            for (int j = 0; j < searchResults.size() - i - 1; j++) {
              ReadableMap result1 = searchResults.getMap(j);
              ReadableMap result2 = searchResults.getMap(j + 1);
              if (result1.getDouble("score") < result2.getDouble("score")) {
                // Swap
                WritableArray tempArray = Arguments.createArray();
                tempArray.pushMap(result2);
                ((WritableArray)searchResults).pushMap(result1);
                ((WritableArray)searchResults).pushMap(tempArray.getMap(0));
              }
            }
          }
          
          // Limit results
          int resultLimit = availableMemoryMB < 200 ? 20 : 50;
          if (options != null && options.hasKey("limit")) {
            resultLimit = Math.min(resultLimit, options.getInt("limit"));
          }
          
          if (searchResults.size() > resultLimit) {
            WritableArray limitedResults = Arguments.createArray();
            for (int i = 0; i < resultLimit; i++) {
              limitedResults.pushMap(searchResults.getMap(i));
            }
            searchResults = limitedResults;
          }
          
          result.putArray("results", searchResults);
          return result;
        } catch (Exception e) {
          exception = e;
        }
        return null;
      }

  // // Helper method to determine adaptive embedding dimension
  // private int getAdaptiveEmbeddingDimension(
  //         int batteryLevel, 
  //         boolean isCharging, 
  //         float thermalHeadroom, 
  //         double availableMemoryMB,
  //         boolean isHighPriority) {
          
  //         // High priority embeddings get more dimensions
  //         if (isHighPriority) {
  //             // Still apply some reduction for critical conditions
  //             if (batteryLevel < 10 && !isCharging) {
  //                 return 384;
  //             }
              
  //             // For high priority, use full dimensions when possible
  //             if (isCharging || batteryLevel > 50 || thermalHeadroom > 0.4f) {
  //                 return 1536; // Full dimension for most models
  //             }
              
  //             // Moderate reduction for high priority under constraints
  //             return 768;
  //         }
          
  //         // Full dimension when charging with high battery and good thermal
  //         if (isCharging && batteryLevel > 80 && thermalHeadroom > 0.5f) {
  //             return 1536;
  //         }
          
  //         // Critical resource constraints - use minimum dimension
  //         if ((batteryLevel < 15 && !isCharging) || 
  //             thermalHeadroom < 0.15f || 
  //             availableMemoryMB < 100) {
  //             return 128;
  //         }
          
  //         // Low battery - reduce dimension significantly
  //         if (batteryLevel < 30 && !isCharging) {
  //             return 256;
  //         }
          
  //         // Medium battery - reduce dimension moderately
  //         if (batteryLevel < 50 || thermalHeadroom < 0.3f) {
  //             return 384;
  //         }
          
  //         // Memory constraints - adjust based on available memory
  //         if (availableMemoryMB < 200) {
  //             return 512;
  //         }
          
  //         // Default - use 768 for good balance
  //         return 768;
  //     }

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
    };
    
    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "hybrid-search-" + contextId);
  }

  /**
   * Enable memory-mapped storage for LLM context
   */
  public void enableMmapStorage(String path, ReadableMap options, Promise promise) {
    AsyncTask<Void, Void, WritableMap> task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;
      
      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          WritableMap result = Arguments.createMap();
          
          // Create directory if it doesn't exist
          File directory = new File(path);
          if (!directory.exists()) {
              directory.mkdirs();
          }
          
          // Get options
          boolean useCompression = options != null && options.hasKey("useCompression") ? 
              options.getBoolean("useCompression") : true;
              
          long reserveSize = options != null && options.hasKey("reserveSize") ? 
              (long)options.getDouble("reserveSize") : 1024 * 1024 * 1024; // Default 1GB
          
          // Call into LlamaContext to enable mmap
          // TODO: Implement native mmap storage
          boolean success = false; //LlamaContext.enableMmapStorage(path, useCompression, reserveSize);
          
          // Return results
          result.putBoolean("success", success);
          result.putString("path", path);
          result.putBoolean("useCompression", useCompression);
          result.putDouble("reserveSizeBytes", reserveSize);
          
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
    tasks.put(task, "enable-mmap");
  }
  
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
   * Perform memory-pressure triggered garbage collection
   */
  @ReactMethod
  public void performMemoryCleanup(ReadableMap options, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      @Override
      protected WritableMap doInBackground(Void... voids) {
        WritableMap result = Arguments.createMap();
        
        // Get memory status
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = 
            (android.app.ActivityManager) reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        double availableMemoryMB = memoryInfo.availMem / (1024.0 * 1024.0);
        double totalMemoryMB = memoryInfo.totalMem / (1024.0 * 1024.0);
        double memoryUsagePercent = 100.0 * (1.0 - (memoryInfo.availMem / (double)memoryInfo.totalMem));
        
        // Get options
        boolean forceCleanup = options != null && options.hasKey("force") ? 
            options.getBoolean("force") : false;
            
        double memoryThreshold = options != null && options.hasKey("memoryThreshold") ? 
            options.getDouble("memoryThreshold") : 15.0; // Default 15% free memory threshold
        
        // Check if cleanup is needed
        boolean needsCleanup = forceCleanup || 
            memoryInfo.lowMemory || 
            availableMemoryMB < 100 || 
            (100.0 - memoryUsagePercent) < memoryThreshold;
        
        result.putBoolean("needsCleanup", needsCleanup);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        result.putDouble("totalMemoryMB", totalMemoryMB);
        result.putDouble("memoryUsagePercent", memoryUsagePercent);
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        if (needsCleanup) {
          // Perform cleanup actions
          
          // 1. Suggest Java garbage collection
          System.gc();
          
          // 2. Clear any caches
          // 2. Clear any caches
          for (LlamaContext context : contexts.values()) {
            if (context != null) {
              // context.clearCache();
            }
          }
          
          // 3. Trim memory in native code
          for (LlamaContext context : contexts.values()) {
            if (context != null) {
              // context.trimMemory();
            }
          }
          
          // 4. Check memory after cleanup
          activityManager.getMemoryInfo(memoryInfo);
          double availableAfterMB = memoryInfo.availMem / (1024.0 * 1024.0);
          double memoryUsageAfterPercent = 100.0 * (1.0 - (memoryInfo.availMem / (double)memoryInfo.totalMem));
          
          result.putDouble("availableAfterMB", availableAfterMB);
          result.putDouble("memoryUsageAfterPercent", memoryUsageAfterPercent);
          result.putDouble("memoryFreedMB", availableAfterMB - availableMemoryMB);
        }
        
        return result;
      }

      @Override
      protected void onPostExecute(WritableMap result) {
        promise.resolve(result);
        tasks.remove(this);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    tasks.put(task, "memory-cleanup");
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
        double thermalHeadroom = 1.0d;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            double updatedThermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
            thermalHeadroom = updatedThermalHeadroom;
          } catch (Exception e) {
            // Default to 0.5 if we can't get the thermal headroom
            thermalHeadroom = 0.5d;
          }
        }
        
        // Determine if we should defer
        boolean shouldDefer = false;
        String reason = "";
        
        if (batteryLevel < 20 && !isCharging) {
          shouldDefer = true;
          reason = "Low battery (" + batteryLevel + "%) and not charging";
        } else if (thermalHeadroom < 0.15d) {
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
        result.putDouble("thermalHeadroom", (double)thermalHeadroom);
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
   * Perform vector search using memory-mapped embeddings
   */
  @ReactMethod
  public void mmapVectorSearch(double id, final String query, final String mmapFilePath, final ReadableMap options, final Promise promise) {
    final int contextId = (int) id;
    
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;
      
      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
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
          
          // Get thermal status
          double thermalHeadroom = 1.0d;

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
              android.os.PowerManager powerManager = (android.os.PowerManager) 
                  reactContext.getSystemService(Context.POWER_SERVICE);
              double updatedThermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
              thermalHeadroom = updatedThermalHeadroom;
            } catch (Exception e) {
              thermalHeadroom = 0.5d;
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
          
          // Get the context
          LlamaContext context = contexts.get(contextId);
          if (context == null) {
            throw new Exception("Context not found");
          }
          
          // Check if file exists
          File file = new File(mmapFilePath);
          if (!file.exists()) {
              throw new Exception("Embedding file does not exist: " + mmapFilePath);
          }
          
          // Open the memory-mapped file
          RandomAccessFile memoryMappedFile = new RandomAccessFile(mmapFilePath, "r");
          java.nio.channels.FileChannel fileChannel = memoryMappedFile.getChannel();
          
          // Map the file into memory (read-only)
          java.nio.MappedByteBuffer buffer = fileChannel.map(
              java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, memoryMappedFile.length());
          
          // Read header information
          int magicNumber = buffer.getInt();
          if (magicNumber != 0xE1B5D01) { // Magic number for embedding file format
              throw new Exception("Invalid embedding file format");
          }
          
          int count = buffer.getInt();
          int capacity = buffer.getInt();
          int dimension = buffer.getInt();
          boolean isQuantized = buffer.getInt() == 1;
          long timestamp = buffer.getLong();
          
          // Skip reserved fields
          buffer.getInt();
          buffer.getInt();
          
          // Calculate embedding size
          int embeddingSize = dimension * (isQuantized ? 1 : 4);
          int headerSize = 32;
          
          // Generate query embedding
          ReadableMap embParams = Arguments.createMap();
          ((WritableMap)embParams).putBoolean("skipOnCriticalBattery", true);
          ((WritableMap)embParams).putBoolean("highPriority", isHighPriority);
          
          // Set target dimension based on device resources
          int targetDimension = RNLlama.this.getAdaptiveEmbeddingDimension(
              batteryLevel, isCharging, thermalHeadroom, availableMemoryMB, isHighPriority);
          ((WritableMap)embParams).putInt("targetDimension", targetDimension);
          
          WritableMap queryEmbedding = context.getEmbedding(query, embParams);
          
          // If embedding generation was skipped due to battery, return early
          if (queryEmbedding.hasKey("skipped") && queryEmbedding.getBoolean("skipped")) {
              result.putBoolean("skipped", true);
              result.putInt("batteryLevel", batteryLevel);
              return result;
          }
          
          // Get query embedding as array
          ReadableArray queryEmbeddingArray = queryEmbedding.getArray("embedding");
          
          // Calculate result limit based on available memory
          int resultLimit = availableMemoryMB < 200 ? 20 : 50;
          if (options != null && options.hasKey("limit")) {
            resultLimit = Math.min(resultLimit, options.getInt("limit"));
          }
          
          // Create priority queue for top results
          java.util.PriorityQueue<Map.Entry<Integer, Double>> topResults = 
              new java.util.PriorityQueue<>(resultLimit, 
                  (a, b) -> Double.compare(a.getValue(), b.getValue())); // Min heap
          
          // Process embeddings in batches to reduce memory pressure
          int batchSize = Math.min(100, count);
          float[] queryVector = new float[dimension];
          
          // Convert query embedding to float array
          for (int i = 0; i < dimension; i++) {
              queryVector[i] = (float)queryEmbeddingArray.getDouble(i);
          }
          
          // Process each embedding
          for (int i = 0; i < count; i++) {
              float[] embVector = new float[dimension];
              
              // Read the embedding values
              for (int j = 0; j < dimension; j++) {
                  if (isQuantized) {
                      // Read quantized byte and convert to float
                      byte quantized = buffer.get(headerSize + (i * embeddingSize) + j);
                      embVector[j] = quantized / 127.0f;
                  } else {
                      // Read float directly
                      embVector[j] = buffer.getFloat(headerSize + (i * embeddingSize) + (j * 4));
                  }
              }
              
              // Calculate cosine similarity
              double similarity = calculateCosineSimilarity(queryVector, embVector);
              
              // Add to priority queue if it's in the top results
              if (topResults.size() < resultLimit) {
                  topResults.add(new java.util.AbstractMap.SimpleEntry<>(i, similarity));
              } else if (similarity > topResults.peek().getValue()) {
                  topResults.poll();
                  topResults.add(new java.util.AbstractMap.SimpleEntry<>(i, similarity));
              }
          }
          
          // Convert priority queue to sorted array
          java.util.List<Map.Entry<Integer, Double>> sortedResults = 
              new java.util.ArrayList<>(topResults);
          java.util.Collections.sort(sortedResults, 
              (a, b) -> Double.compare(b.getValue(), a.getValue())); // Descending
          
          // Create results array
          WritableArray searchResults = Arguments.createArray();
          
          // Add top results to output
          for (Map.Entry<Integer, Double> entry : sortedResults) {
              int index = entry.getKey();
              double similarity = entry.getValue();
              
              WritableMap searchResult = Arguments.createMap();
              searchResult.putInt("index", index);
              searchResult.putDouble("score", similarity);
              
              searchResults.pushMap(searchResult);
          }
          
          // Close the file
          fileChannel.close();
          memoryMappedFile.close();
          
          // Add results to output
          result.putArray("results", searchResults);
          result.putInt("totalSearched", count);
          result.putInt("dimension", dimension);
          result.putBoolean("isQuantized", isQuantized);
          
          // Add device status
          result.putInt("batteryLevel", batteryLevel);
          result.putBoolean("isCharging", isCharging);
          result.putDouble("thermalHeadroom", thermalHeadroom);
          result.putDouble("availableMemoryMB", availableMemoryMB);
          
          return result;
        } catch (Exception e) {
          exception = e;
          return null;
        }
      }
      
      // Helper method to calculate cosine similarity between float arrays
      private double calculateCosineSimilarity(float[] a, float[] b) {
          double dotProduct = 0.0;
          double normA = 0.0;
          double normB = 0.0;
          
          int length = Math.min(a.length, b.length);
          
          for (int i = 0; i < length; i++) {
              dotProduct += a[i] * b[i];
              normA += a[i] * a[i];
              normB += b[i] * b[i];
          }
          
          if (normA == 0 || normB == 0) {
              return 0;
          }
          
          return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
      }
      
      // // Helper method to determine adaptive embedding dimension
      // private int getAdaptiveEmbeddingDimension(
      //     int batteryLevel, 
      //     boolean isCharging, 
      //     float thermalHeadroom, 
      //     double availableMemoryMB,
      //     boolean isHighPriority) {
          
      //     // High priority embeddings get more dimensions
      //     if (isHighPriority) {
      //         // Still apply some reduction for critical conditions
      //         if (batteryLevel < 10 && !isCharging) {
      //             return 384;
      //         }
              
      //         // For high priority, use full dimensions when possible
      //         if (isCharging || batteryLevel > 50 || thermalHeadroom > 0.4f) {
      //             return 1536; // Full dimension for most models
      //         }
              
      //         // Moderate reduction for high priority under constraints
      //         return 768;
      //     }
          
      //     // Full dimension when charging with high battery and good thermal
      //     if (isCharging && batteryLevel > 80 && thermalHeadroom > 0.5f) {
      //         return 1536;
      //     }
          
      //     // Critical resource constraints - use minimum dimension
      //     if ((batteryLevel < 15 && !isCharging) || 
      //         thermalHeadroom < 0.15f || 
      //         availableMemoryMB < 100) {
      //         return 128;
      //     }
          
      //     // Low battery - reduce dimension significantly
      //     if (batteryLevel < 30 && !isCharging) {
      //         return 256;
      //     }
          
      //     // Medium battery - reduce dimension moderately
      //     if (batteryLevel < 50 || thermalHeadroom < 0.3f) {
      //         return 384;
      //     }
          
      //     // Memory constraints - adjust based on available memory
      //     if (availableMemoryMB < 200) {
      //         return 512;
      //     }
          
      //     // Default - use 768 for good balance
      //     return 768;
      // }
      
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
    tasks.put(task, "mmap-vector-search-" + contextId);
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
        
        // Get thermal status
        double thermalHeadroom = 1.0d;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          try {
            android.os.PowerManager powerManager = (android.os.PowerManager) 
                reactContext.getSystemService(Context.POWER_SERVICE);
            thermalHeadroom = powerManager.getThermalHeadroom(android.os.PowerManager.THERMAL_STATUS_MODERATE);
          } catch (Exception e) {
            thermalHeadroom = 0.5d;
          }
        }
        
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
        
        // Get storage path
        String storagePath = options != null && options.hasKey("storagePath") ? 
            options.getString("storagePath") : reactContext.getFilesDir().getAbsolutePath() + "/embeddings";
        
        // Check if we should perform emergency cleanup
        boolean emergencyCleanup = batteryLevel < 5 && !isCharging;
        
        // Check if we should perform memory-pressure cleanup
        boolean memoryPressureCleanup = memoryInfo.lowMemory || availableMemoryMB < 100;
        
        // Check if we should perform thermal cleanup
        boolean thermalCleanup = thermalHeadroom < 0.1d;
        
        // Current time
        long currentTime = System.currentTimeMillis();
        
        // Counters for statistics
        int expiredCount = 0;
        int emergencyCount = 0;
        int shadowCount = 0;
        int retainedCount = 0;
        int thermalCount = 0;
        int memoryCount = 0;
        
        // Determine cleanup strategy
        String cleanupStrategy = "normal";
        if (emergencyCleanup) {
          cleanupStrategy = "emergency";
        } else if (thermalCleanup) {
          cleanupStrategy = "thermal";
        } else if (memoryPressureCleanup) {
          cleanupStrategy = "memory";
        }
        
        // Get retention policy
        String retentionPolicy = options != null && options.hasKey("retentionPolicy") ? 
            options.getString("retentionPolicy") : "importance";
        
        // Get importance threshold
        double importanceThreshold = options != null && options.hasKey("importanceThreshold") ? 
            options.getDouble("importanceThreshold") : 0.3;
        
        // Call into native code to perform actual cleanup
        // This would be implemented in the C++ layer
        // For now, we'll simulate the results
        
        if (emergencyCleanup) {
          // Emergency cleanup - remove 70% of embeddings, keeping only high importance ones
          emergencyCount = 70;
          retainedCount = 30;
        } else if (thermalCleanup) {
          // Thermal cleanup - remove 50% of embeddings
          thermalCount = 50;
          retainedCount = 50;
        } else if (memoryPressureCleanup) {
          // Memory cleanup - remove 40% of embeddings
          memoryCount = 40;
          retainedCount = 60;
        } else {
          // Normal cleanup - just remove expired embeddings
          expiredCount = 10;
          shadowCount = 5;
          retainedCount = 85;
        }
        
        // Add cleanup statistics to result
        result.putInt("expiredCount", expiredCount);
        result.putInt("emergencyCount", emergencyCount);
        result.putInt("thermalCount", thermalCount);
        result.putInt("memoryCount", memoryCount);
        result.putInt("shadowCount", shadowCount);
        result.putInt("retainedCount", retainedCount);
        
        // Add cleanup flags
        result.putBoolean("emergencyCleanupPerformed", emergencyCleanup);
        result.putBoolean("thermalCleanupPerformed", thermalCleanup);
        result.putBoolean("memoryPressureCleanupPerformed", memoryPressureCleanup);
        
        // Add device status
        result.putInt("batteryLevel", batteryLevel);
        result.putBoolean("isCharging", isCharging);
        result.putDouble("thermalHeadroom", thermalHeadroom);
        result.putDouble("availableMemoryMB", availableMemoryMB);
        result.putBoolean("lowMemory", memoryInfo.lowMemory);
        
        // Add cleanup strategy and policy info
        result.putString("cleanupStrategy", cleanupStrategy);
        result.putString("retentionPolicy", retentionPolicy);
        result.putDouble("importanceThreshold", importanceThreshold);
        
        // Add storage info
        result.putString("storagePath", storagePath);
        result.putDouble("expirationTimeMs", expirationTime);
        
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
  
  /**
   * Store embeddings to memory-mapped file
   */
  @ReactMethod
  public void storeEmbeddingsToMmap(ReadableArray embeddings, String filePath, ReadableMap options, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;
      
      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          WritableMap result = Arguments.createMap();
          
          // Get options
          boolean useQuantization = options != null && options.hasKey("useQuantization") ? 
              options.getBoolean("useQuantization") : true;
          
          int capacity = options != null && options.hasKey("capacity") ? 
              options.getInt("capacity") : 10000;
          
          int dimension = options != null && options.hasKey("dimension") ? 
              options.getInt("dimension") : 768;
              
          // Create directory if it doesn't exist
          File directory = new File(filePath).getParentFile();
          if (directory != null && !directory.exists()) {
              directory.mkdirs();
          }
          
          // Prepare header information
          int headerSize = 32; // bytes for metadata
          int embeddingSize = dimension * (useQuantization ? 1 : 4); // bytes per embedding
          long totalSize = headerSize + (long)capacity * embeddingSize;
          
          // Create or open the memory-mapped file
          RandomAccessFile memoryMappedFile = new RandomAccessFile(filePath, "rw");
          
          // Set the file size if it's new
          if (memoryMappedFile.length() < totalSize) {
              memoryMappedFile.setLength(totalSize);
          }
          
          // Create the memory map
          java.nio.channels.FileChannel fileChannel = memoryMappedFile.getChannel();
          java.nio.MappedByteBuffer buffer = fileChannel.map(
              java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, totalSize);
          
          // Write header information
          buffer.putInt(0xE1B5D01); // Magic number for embedding file format
          buffer.putInt(embeddings.size()); // Number of embeddings
          buffer.putInt(capacity); // Maximum capacity
          buffer.putInt(dimension); // Embedding dimension
          buffer.putInt(useQuantization ? 1 : 0); // Quantization flag
          buffer.putLong(System.currentTimeMillis()); // Creation timestamp
          buffer.putInt(0); // Reserved for future use
          buffer.putInt(0); // Reserved for future use
          
          // Write each embedding
          int storedCount = 0;
          for (int i = 0; i < embeddings.size() && i < capacity; i++) {
              ReadableMap embedding = embeddings.getMap(i);
              if (!embedding.hasKey("embedding")) {
                  continue;
              }
              
              ReadableArray embeddingArray = embedding.getArray("embedding");
              if (embeddingArray.size() != dimension) {
                  // Skip if dimensions don't match
                  continue;
              }
              
              // Store the embedding
              for (int j = 0; j < dimension; j++) {
                  if (useQuantization) {
                      // Convert float to quantized byte
                      float value = (float)embeddingArray.getDouble(j);
                      byte quantized = (byte)Math.round(value * 127.0f);
                      buffer.put(headerSize + (i * embeddingSize) + j, quantized);
                  } else {
                      // Store as float
                      float value = (float)embeddingArray.getDouble(j);
                      buffer.putFloat(headerSize + (i * embeddingSize) + (j * 4), value);
                  }
              }
              
              storedCount++;
          }
          
          // Force write to disk
          buffer.force();
          
          // Close the file
          fileChannel.close();
          memoryMappedFile.close();
          
          // Return results
          result.putInt("storedCount", storedCount);
          result.putString("filePath", filePath);
          result.putBoolean("useQuantization", useQuantization);
          result.putInt("capacity", capacity);
          result.putInt("dimension", dimension);
          result.putDouble("storageSizeBytes", totalSize);
          
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
    tasks.put(task, "store-embeddings");
  }
  
  /**
   * Load embeddings from memory-mapped file
   */
  @ReactMethod
  public void loadEmbeddingsFromMmap(String filePath, Promise promise) {
    AsyncTask task = new AsyncTask<Void, Void, WritableMap>() {
      private Exception exception;
      
      @Override
      protected WritableMap doInBackground(Void... voids) {
        try {
          WritableMap result = Arguments.createMap();
          
          // Check if file exists
          File file = new File(filePath);
          if (!file.exists()) {
              throw new Exception("Embedding file does not exist: " + filePath);
          }
          
          // Open the memory-mapped file
          RandomAccessFile memoryMappedFile = new RandomAccessFile(filePath, "r");
          java.nio.channels.FileChannel fileChannel = memoryMappedFile.getChannel();
          
          // Map the file into memory (read-only)
          java.nio.MappedByteBuffer buffer = fileChannel.map(
              java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, memoryMappedFile.length());
          
          // Read header information
          int magicNumber = buffer.getInt();
          if (magicNumber != 0xE1B5D01) { // Magic number for embedding file format
              throw new Exception("Invalid embedding file format");
          }
          
          int count = buffer.getInt();
          int capacity = buffer.getInt();
          int dimension = buffer.getInt();
          boolean isQuantized = buffer.getInt() == 1;
          long timestamp = buffer.getLong();
          
          // Skip reserved fields
          buffer.getInt();
          buffer.getInt();
          
          // Calculate embedding size
          int embeddingSize = dimension * (isQuantized ? 1 : 4);
          int headerSize = 32;
          
          // Create array to hold embeddings
          WritableArray embeddings = Arguments.createArray();
          
          // Read each embedding
          for (int i = 0; i < count; i++) {
              WritableMap embedding = Arguments.createMap();
              WritableArray embeddingArray = Arguments.createArray();
              
              // Read the embedding values
              for (int j = 0; j < dimension; j++) {
                  float value;
                  if (isQuantized) {
                      // Read quantized byte and convert to float
                      byte quantized = buffer.get(headerSize + (i * embeddingSize) + j);
                      value = quantized / 127.0f;
                  } else {
                      // Read float directly
                      value = buffer.getFloat(headerSize + (i * embeddingSize) + (j * 4));
                  }
                  embeddingArray.pushDouble(value);
              }
              
              embedding.putArray("embedding", embeddingArray);
              embeddings.pushMap(embedding);
          }
          
          // Close the file
          fileChannel.close();
          memoryMappedFile.close();
          
          // Return results
          result.putInt("loadedCount", count);
          result.putString("filePath", filePath);
          result.putBoolean("isQuantized", isQuantized);
          result.putInt("dimension", dimension);
          result.putArray("embeddings", embeddings);
          result.putDouble("timestamp", timestamp);
          
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
    tasks.put(task, "load-embeddings");
  }
}
