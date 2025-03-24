package com.rnllama;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.util.Log;
import android.os.Build;
import android.content.res.AssetManager;

import java.lang.StringBuilder;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.IOException;

public class LlamaContext {
  public static final String NAME = "RNLlamaContext";

  private static String loadedLibrary = "";

  private static class NativeLogCallback {
    DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    public NativeLogCallback(ReactApplicationContext reactContext) {
      this.eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
    }

    void emitNativeLog(String level, String text) {
      WritableMap event = Arguments.createMap();
      event.putString("level", level);
      event.putString("text", text);
      eventEmitter.emit("@RNLlama_onNativeLog", event);
    }
  }

  static void toggleNativeLog(ReactApplicationContext reactContext, boolean enabled) {
    if (LlamaContext.isArchNotSupported()) {
      throw new IllegalStateException("Only 64-bit architectures are supported");
    }
    if (enabled) {
      setupLog(new NativeLogCallback(reactContext));
    } else {
      unsetLog();
    }
  }

  private int id;
  private ReactApplicationContext reactContext;
  private long context;
  private WritableMap modelDetails;
  private int jobId = -1;
  private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

  public LlamaContext(int id, ReactApplicationContext reactContext, ReadableMap params) {
    if (LlamaContext.isArchNotSupported()) {
      throw new IllegalStateException("Only 64-bit architectures are supported");
    }
    if (!params.hasKey("model")) {
      throw new IllegalArgumentException("Missing required parameter: model");
    }
    eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
    this.id = id;
    this.context = initContext(
      // String model,
      params.getString("model"),
      // String chat_template,
      params.hasKey("chat_template") ? params.getString("chat_template") : "",
      // String reasoning_format,
      params.hasKey("reasoning_format") ? params.getString("reasoning_format") : "none",
      // boolean embedding,
      params.hasKey("embedding") ? params.getBoolean("embedding") : false,
      // int embd_normalize,
      params.hasKey("embd_normalize") ? params.getInt("embd_normalize") : -1,
      // int n_ctx,
      params.hasKey("n_ctx") ? params.getInt("n_ctx") : 512,
      // int n_batch,
      params.hasKey("n_batch") ? params.getInt("n_batch") : 512,
      // int n_ubatch,
      params.hasKey("n_ubatch") ? params.getInt("n_ubatch") : 512,
      // int n_threads,
      params.hasKey("n_threads") ? params.getInt("n_threads") : 0,
      // int n_gpu_layers, // TODO: Support this
      params.hasKey("n_gpu_layers") ? params.getInt("n_gpu_layers") : 0,
      // boolean flash_attn,
      params.hasKey("flash_attn") ? params.getBoolean("flash_attn") : false,
      // String cache_type_k,
      params.hasKey("cache_type_k") ? params.getString("cache_type_k") : "f16",
      // String cache_type_v,
      params.hasKey("cache_type_v") ? params.getString("cache_type_v") : "f16",
      // boolean use_mlock,
      params.hasKey("use_mlock") ? params.getBoolean("use_mlock") : true,
      // boolean use_mmap,
      params.hasKey("use_mmap") ? params.getBoolean("use_mmap") : true,
      //boolean vocab_only,
      params.hasKey("vocab_only") ? params.getBoolean("vocab_only") : false,
      // String lora,
      params.hasKey("lora") ? params.getString("lora") : "",
      // float lora_scaled,
      params.hasKey("lora_scaled") ? (float) params.getDouble("lora_scaled") : 1.0f,
      // ReadableArray lora_adapters,
      params.hasKey("lora_list") ? params.getArray("lora_list") : null,
      // float rope_freq_base,
      params.hasKey("rope_freq_base") ? (float) params.getDouble("rope_freq_base") : 0.0f,
      // float rope_freq_scale
      params.hasKey("rope_freq_scale") ? (float) params.getDouble("rope_freq_scale") : 0.0f,
      // int pooling_type,
      params.hasKey("pooling_type") ? params.getInt("pooling_type") : -1,
      // LoadProgressCallback load_progress_callback
      params.hasKey("use_progress_callback") ? new LoadProgressCallback(this) : null
    );
    if (this.context == -1) {
      throw new IllegalStateException("Failed to initialize context");
    }
    this.modelDetails = loadModelDetails(this.context);
    this.reactContext = reactContext;
  }

  public void interruptLoad() {
    interruptLoad(this.context);
  }

  public long getContext() {
    return context;
  }

  public WritableMap getModelDetails() {
    return modelDetails;
  }

  public String getLoadedLibrary() {
    return loadedLibrary;
  }

  // Conversation state management
  private String conversationState = null;

  /**
   * Check if this context has conversation state
   */
  public boolean hasConversationState() {
    return conversationState != null && !conversationState.isEmpty();
  }

  /**
   * Get the current conversation state as a serialized string
   */
  public String getConversationState() {
    try {
      // Always create a fresh state to ensure we have the latest data
      WritableMap state = Arguments.createMap();
      state.putInt("contextId", this.id);
      state.putDouble("timestamp", System.currentTimeMillis());

      // Save session tokens to a temporary file
      String tempSessionPath = reactContext.getCacheDir() + "/temp_session_" + this.id + ".bin";
      int tokenCount = saveSession(this.context, tempSessionPath, -1); // -1 means all tokens
      
      Log.d(NAME, "Saved session with " + tokenCount + " tokens");
      
      // Create a session info object
      WritableMap session = Arguments.createMap();
      session.putInt("n_tokens", tokenCount);
      session.putString("path", tempSessionPath);
      
      // Add session info to state
      state.putMap("session", session);
      
      // If we have an existing state, preserve messages
      WritableArray messages = Arguments.createArray();
      
      if (conversationState != null && !conversationState.isEmpty()) {
        try {
          org.json.JSONObject existingState = new org.json.JSONObject(conversationState);
          
          // If we already have structured messages, use them
          if (existingState.has("messages")) {
            org.json.JSONArray messagesJson = existingState.getJSONArray("messages");
            
            for (int i = 0; i < messagesJson.length(); i++) {
              org.json.JSONObject msgJson = messagesJson.getJSONObject(i);
              WritableMap msg = Arguments.createMap();
              
              // Copy all properties from the JSON object to the WritableMap
              java.util.Iterator<String> keys = msgJson.keys();
              while (keys.hasNext()) {
                String key = keys.next();
                Object value = msgJson.get(key);
                
                if (value instanceof String) {
                  msg.putString(key, (String) value);
                } else if (value instanceof Integer) {
                  msg.putInt(key, (Integer) value);
                } else if (value instanceof Double) {
                  msg.putDouble(key, (Double) value);
                } else if (value instanceof Boolean) {
                  msg.putBoolean(key, (Boolean) value);
                }
              }
              
              messages.pushMap(msg);
            }
          } 
          // If we have a prompt but no structured messages, try to extract messages from prompt
          else if (existingState.has("prompt")) {
            String prompt = existingState.getString("prompt");
            extractMessagesFromPrompt(prompt, messages);
          }
          
          // Preserve chat template if it exists
          if (existingState.has("chatTemplate")) {
            state.putString("chatTemplate", existingState.getString("chatTemplate"));
          }
        } catch (org.json.JSONException e) {
          Log.w(NAME, "Could not parse existing conversation state: " + e.getMessage());
        }
      }
      
      // Always add messages array to state, even if empty
      state.putArray("messages", messages);

      // Convert to JSON string
      conversationState = state.toString();
      Log.d(NAME, "Created conversation state with session data");
      
      return conversationState;
    } catch (Exception e) {
      Log.e(NAME, "Failed to create conversation state", e);
      return conversationState; // Return existing state if we failed to create a new one
    }
  }
  
  /**
   * Extract structured messages from a raw prompt string
   */
  public void extractMessagesFromPrompt(String prompt, WritableArray messages) {
    if (prompt == null || prompt.isEmpty()) {
      return;
    }
    
    try {
      // Check if this is a special format with header_id tokens
      if (prompt.contains("<|start_header_id|>") && prompt.contains("<|end_header_id|>")) {
        // Split by end-of-turn token
        String[] turns = prompt.split("<\\|eot_id\\|>");
        
        for (String turn : turns) {
          if (turn.trim().isEmpty()) {
            continue;
          }
          
          // Find the role marker
          int startHeaderPos = turn.indexOf("<|start_header_id|>");
          if (startHeaderPos >= 0) {
            int endHeaderPos = turn.indexOf("<|end_header_id|>");
            
            if (endHeaderPos > startHeaderPos) {
              // Extract the role
              String role = turn.substring(
                  startHeaderPos + "<|start_header_id|>".length(),
                  endHeaderPos
              ).trim();
              
              // Extract the content (everything after end_header_id)
              String content = turn.substring(endHeaderPos + "<|end_header_id|>".length()).trim();
              
              // Create message if we have valid role and content
              if (!role.isEmpty()) {
                WritableMap msg = Arguments.createMap();
                msg.putString("role", role);
                msg.putString("content", content);
                messages.pushMap(msg);
              }
            }
          } else {
            // If there's content before the first header, treat it as system message
            if (!turn.trim().isEmpty() && messages.size() == 0) {
              WritableMap msg = Arguments.createMap();
              msg.putString("role", "system");
              msg.putString("content", turn.trim());
              messages.pushMap(msg);
            }
          }
        }
        
        // If we successfully extracted messages, return
        if (messages.size() > 0) {
          return;
        }
      }
      
      // Fall back to the existing parsing logic for other formats
      // Common patterns in prompts
      String[] userMarkers = {"[USER]:", "User:", "Human:"};
      String[] assistantMarkers = {"[ASSISTANT]:", "Assistant:", "AI:"};
      String[] systemMarkers = {"[SYSTEM]:", "System:"};
      String[] endMarkers = {"\n\n"};
      
      // Try to identify if this is a JSON array of messages
      if (prompt.trim().startsWith("[") && prompt.trim().endsWith("]")) {
        try {
          org.json.JSONArray jsonArray = new org.json.JSONArray(prompt);
          for (int i = 0; i < jsonArray.length(); i++) {
            org.json.JSONObject msgJson = jsonArray.getJSONObject(i);
            WritableMap msg = Arguments.createMap();
            
            if (msgJson.has("role") && msgJson.has("content")) {
              msg.putString("role", msgJson.getString("role"));
              msg.putString("content", msgJson.getString("content"));
              messages.pushMap(msg);
            }
          }
          return; // Successfully parsed JSON array
        } catch (org.json.JSONException e) {
          // Not valid JSON, continue with other parsing methods
        }
      }
      
      // Split the prompt into chunks based on markers
      java.util.List<String> chunks = new java.util.ArrayList<>();
      java.util.List<String> roles = new java.util.ArrayList<>();
      
      int lastIndex = 0;
      String currentRole = null;
      
      // Find all user markers
      for (String marker : userMarkers) {
        int index = prompt.indexOf(marker, lastIndex);
        while (index != -1) {
          if (currentRole != null && lastIndex < index) {
            chunks.add(prompt.substring(lastIndex, index));
            roles.add(currentRole);
          }
          lastIndex = index + marker.length();
          currentRole = "user";
          index = prompt.indexOf(marker, lastIndex);
        }
      }
      
      // Find all assistant markers
      for (String marker : assistantMarkers) {
        int index = prompt.indexOf(marker, lastIndex);
        while (index != -1) {
          if (currentRole != null && lastIndex < index) {
            chunks.add(prompt.substring(lastIndex, index));
            roles.add(currentRole);
          }
          lastIndex = index + marker.length();
          currentRole = "assistant";
          index = prompt.indexOf(marker, lastIndex);
        }
      }
      
      // Find all system markers
      for (String marker : systemMarkers) {
        int index = prompt.indexOf(marker, lastIndex);
        while (index != -1) {
          if (currentRole != null && lastIndex < index) {
            chunks.add(prompt.substring(lastIndex, index));
            roles.add(currentRole);
          }
          lastIndex = index + marker.length();
          currentRole = "system";
          index = prompt.indexOf(marker, lastIndex);
        }
      }
      
      // Add the last chunk
      if (currentRole != null && lastIndex < prompt.length()) {
        chunks.add(prompt.substring(lastIndex));
        roles.add(currentRole);
      }
      
      // If we couldn't identify any structured messages, treat the whole prompt as a user message
      if (chunks.isEmpty()) {
        WritableMap userMsg = Arguments.createMap();
        userMsg.putString("role", "user");
        userMsg.putString("content", prompt);
        messages.pushMap(userMsg);
        return;
      }
      
      // Process the chunks into messages
      for (int i = 0; i < chunks.size(); i++) {
        String content = chunks.get(i);
        String role = roles.get(i);
        
        // Clean up content by removing end markers
        for (String marker : endMarkers) {
          content = content.replace(marker, "");
        }
        
        // Trim whitespace
        content = content.trim();
        
        // Create message
        WritableMap msg = Arguments.createMap();
        msg.putString("role", role);
        msg.putString("content", content);
        messages.pushMap(msg);
      }
    } catch (Exception e) {
      Log.w(NAME, "Failed to extract messages from prompt: " + e.getMessage(), e);
      
      // Fallback: treat the whole prompt as a user message
      WritableMap userMsg = Arguments.createMap();
      userMsg.putString("role", "user");
      userMsg.putString("content", prompt);
      messages.pushMap(userMsg);
    }
  }

  /**
   * Get the structured messages from the current conversation state
   * @return WritableArray of message objects with role and content
   */
  public WritableArray getConversationMessages() {
    WritableArray messages = Arguments.createArray();
    
    if (conversationState == null || conversationState.isEmpty()) {
      return messages;
    }
    
    try {
      org.json.JSONObject jsonState = new org.json.JSONObject(conversationState);
      if (jsonState.has("messages")) {
        org.json.JSONArray messagesJson = jsonState.getJSONArray("messages");
        
        for (int i = 0; i < messagesJson.length(); i++) {
          org.json.JSONObject msgJson = messagesJson.getJSONObject(i);
          WritableMap msg = Arguments.createMap();
          
          // Copy all properties from the JSON object to the WritableMap
          java.util.Iterator<String> keys = msgJson.keys();
          while (keys.hasNext()) {
            String key = keys.next();
            Object value = msgJson.get(key);
            
            if (value instanceof String) {
              msg.putString(key, (String) value);
            } else if (value instanceof Integer) {
              msg.putInt(key, (Integer) value);
            } else if (value instanceof Double) {
              msg.putDouble(key, (Double) value);
            } else if (value instanceof Boolean) {
              msg.putBoolean(key, (Boolean) value);
            }
          }
          
          messages.pushMap(msg);
        }
      } else if (jsonState.has("prompt")) {
        // If we only have a prompt, try to extract messages
        String prompt = jsonState.getString("prompt");
        extractMessagesFromPrompt(prompt, messages);
      }
    } catch (org.json.JSONException e) {
      Log.w(NAME, "Could not parse conversation state: " + e.getMessage());
    }
    
    return messages;
  }

  /**
   * Restore conversation from serialized state
   * @return true if successful
   */
  public boolean restoreConversationState(String state) {
    if (state == null || state.isEmpty()) {
      Log.d(NAME, "Cannot restore conversation: state is null or empty");
      return false;
    }

    try {
      // Set restoration flag
      restoringConversation = true;
      
      // Store the state
      conversationState = state;

      // Try to parse the state to verify it's valid
      try {
        org.json.JSONObject jsonState = new org.json.JSONObject(state);
        int contextId = jsonState.getInt("contextId");
        long timestamp = jsonState.getLong("timestamp");

        // Calculate age in hours
        long ageHours = (System.currentTimeMillis() - timestamp) / (60 * 60 * 1000);

        Log.d(NAME, "Restoring conversation for context " + contextId +
              " (age: " + ageHours + " hours, current context: " + this.id + ")");

        // If the state contains messages, log that too
        if (jsonState.has("messages")) {
          Log.d(NAME, "Conversation contains messages data");
        }

        // Restore session if available
        if (jsonState.has("session")) {
          org.json.JSONObject session = jsonState.getJSONObject("session");
          Log.d(NAME, "Found session info in state");
          
          if (session.has("n_tokens") && session.has("path")) {
            int tokens = session.getInt("n_tokens");
            String path = session.getString("path");
            
            Log.d(NAME, "Attempting to restore session with " + tokens + " tokens from " + path);
            
            // Check if the session file exists
            File sessionFile = new File(path);
            if (sessionFile.exists()) {
              long fileSize = sessionFile.length();                                                                                                
              if (fileSize < 100) { // Arbitrary small size that's definitely too small                                                            
                  Log.w(NAME, "Session file is too small (" + fileSize + " bytes), likely corrupted");                                             
                  sessionFile.delete();                                                                                                            
              } else { 
                try {                                                                                                                                    
                  // Load the session                                                                                                                  
                  WritableMap loadResult = loadSession(this.context, path);                                                                            
                  if (loadResult.hasKey("tokens_loaded")) {                                                                                            
                      int tokensLoaded = loadResult.getInt("tokens_loaded");                                                                           
                      Log.d(NAME, "Successfully restored " + tokensLoaded + " tokens from session");                                                   
                  } else if (loadResult.hasKey("success") && loadResult.getBoolean("success")) {                                                       
                      Log.d(NAME, "Successfully restored session (token count unknown)");                                                              
                  } else {                                                                                                                             
                      Log.w(NAME, "Session loaded but no tokens_loaded in result");                                                                    
                  }
                  
                  // Emit event that conversation was restored
                  emitConversationRestored();
                  return true;
                } catch (Exception e) {                                                                                                                  
                    Log.e(NAME, "Failed to load session: " + e.getMessage());                                                                            
                    // Delete corrupted session file                                                                                                     
                    try {                                                                                                                                
                        File corruptedFile = new File(path);                                                                                               
                        if (corruptedFile.exists()) {                                                                                                      
                            boolean deleted = corruptedFile.delete();                                                                                      
                            Log.d(NAME, "Deleted corrupted session file: " + deleted);                                                                   
                        }                                                                                                                                
                    } catch (Exception ex) {                                                                                                             
                        Log.e(NAME, "Failed to delete corrupted session file", ex);                                                                      
                    }
                    
                    // Reset restoration flag on failure
                    restoringConversation = false;
                    return false;
                }   
              } 
            } else {
              Log.w(NAME, "Session file does not exist: " + path);
            }
          }
        }
        
        // If we got here without returning, we need to emit the event
        // This handles cases where there's no session to restore
        emitConversationRestored();
        return true;
      } catch (org.json.JSONException e) {
        Log.w(NAME, "Conversation state is not valid JSON: " + e.getMessage());
        // Reset restoration flag
        restoringConversation = false;
        // Still return true since we stored the state
        return true;
      }
    } catch (Exception e) {
      Log.e(NAME, "Failed to restore conversation state", e);
      // Reset restoration flag on failure
      restoringConversation = false;
      return false;
    }
  }

  public WritableMap getFormattedChatWithJinja(String messages, String chatTemplate, ReadableMap params) {
    String jsonSchema = params.hasKey("json_schema") ? params.getString("json_schema") : "";
    String tools = params.hasKey("tools") ? params.getString("tools") : "";
    Boolean parallelToolCalls = params.hasKey("parallel_tool_calls") ? params.getBoolean("parallel_tool_calls") : false;
    String toolChoice = params.hasKey("tool_choice") ? params.getString("tool_choice") : "";

    // Update conversation state when formatting chat
    try {
      // Parse the messages string to extract structured messages
      WritableMap state = Arguments.createMap();
      state.putInt("contextId", this.id);
      state.putDouble("timestamp", System.currentTimeMillis());
      
      // Store the raw messages string for backward compatibility
      if (chatTemplate != null && !chatTemplate.isEmpty()) {
        state.putString("chatTemplate", chatTemplate);
      }
      
      // Try to parse messages as JSON array
      WritableArray messagesArray = Arguments.createArray();
      try {
        org.json.JSONArray jsonArray = new org.json.JSONArray(messages);
        for (int i = 0; i < jsonArray.length(); i++) {
          org.json.JSONObject msgJson = jsonArray.getJSONObject(i);
          WritableMap msg = Arguments.createMap();
          
          // Copy all properties from the JSON object to the WritableMap
          java.util.Iterator<String> keys = msgJson.keys();
          while (keys.hasNext()) {
            String key = keys.next();
            Object value = msgJson.get(key);
            
            if (value instanceof String) {
              msg.putString(key, (String) value);
            } else if (value instanceof Integer) {
              msg.putInt(key, (Integer) value);
            } else if (value instanceof Double) {
              msg.putDouble(key, (Double) value);
            } else if (value instanceof Boolean) {
              msg.putBoolean(key, (Boolean) value);
            }
          }
          
          messagesArray.pushMap(msg);
        }
        state.putArray("messages", messagesArray);
      } catch (org.json.JSONException e) {
        // If not valid JSON array, store as raw string and extract messages
        state.putString("rawMessages", messages);
        extractMessagesFromPrompt(messages, messagesArray);
        state.putArray("messages", messagesArray);
      }

      // Store the conversation state
      conversationState = state.toString();
    } catch (Exception e) {
      Log.e(NAME, "Failed to update conversation state", e);
    }

    return getFormattedChatWithJinja(
      this.context,
      messages,
      chatTemplate == null ? "" : chatTemplate,
      jsonSchema,
      tools,
      parallelToolCalls,
      toolChoice
    );
  }

  public String getFormattedChat(String messages, String chatTemplate) {
    return getFormattedChat(this.context, messages, chatTemplate == null ? "" : chatTemplate);
  }

  private void emitLoadProgress(int progress) {
    WritableMap event = Arguments.createMap();
    event.putInt("contextId", LlamaContext.this.id);
    event.putInt("progress", progress);
    eventEmitter.emit("@RNLlama_onInitContextProgress", event);
  }

  private static class LoadProgressCallback {
    LlamaContext context;

    public LoadProgressCallback(LlamaContext context) {
      this.context = context;
    }

    void onLoadProgress(int progress) {
      context.emitLoadProgress(progress);
    }
  }

  // Flag to track if conversation restoration is in progress
  private boolean restoringConversation = false;
  
  /**
   * Check if a conversation is currently being restored
   */
  public boolean isRestoringConversation() {
    return restoringConversation;
  }

  private void emitConversationRestored() {
    WritableMap event = Arguments.createMap();
    event.putInt("contextId", LlamaContext.this.id);
    Log.d(NAME, "Emitting conversation restored event for context " + LlamaContext.this.id);
    
    // Include the messages in the event
    event.putArray("messages", getConversationMessages());
    event.putBoolean("success", true);
    
    // Include timestamp if available
    try {
      if (conversationState != null) {
        org.json.JSONObject jsonState = new org.json.JSONObject(conversationState);
        if (jsonState.has("timestamp")) {
          event.putDouble("timestamp", jsonState.getDouble("timestamp"));
        }
        
        // Include token count if available
        if (jsonState.has("session") && jsonState.getJSONObject("session").has("n_tokens")) {
          event.putInt("tokenCount", jsonState.getJSONObject("session").getInt("n_tokens"));
        }
      }
    } catch (org.json.JSONException e) {
      // Ignore parsing errors
    }
    
    eventEmitter.emit("@RNLlama_onConversationRestored", event);
    
    // Reset restoration flag
    restoringConversation = false;
  }

  private void emitPartialCompletion(WritableMap tokenResult) {
    WritableMap event = Arguments.createMap();
    event.putInt("contextId", LlamaContext.this.id);
    event.putMap("tokenResult", tokenResult);
    eventEmitter.emit("@RNLlama_onToken", event);
  }

  private static class PartialCompletionCallback {
    LlamaContext context;
    boolean emitNeeded;

    public PartialCompletionCallback(LlamaContext context, boolean emitNeeded) {
      this.context = context;
      this.emitNeeded = emitNeeded;
    }

    void onPartialCompletion(WritableMap tokenResult) {
      if (!emitNeeded) return;
      context.emitPartialCompletion(tokenResult);
    }
  }

  public WritableMap loadSession(String path) {
    if (path == null || path.isEmpty()) {
      throw new IllegalArgumentException("File path is empty");
    }
    File file = new File(path);
    if (!file.exists()) {
      throw new IllegalArgumentException("File does not exist: " + path);
    }
    WritableMap result = loadSession(this.context, path);
    if (result.hasKey("error")) {
      throw new IllegalStateException(result.getString("error"));
    }
    return result;
  }

  public WritableMap completion(ReadableMap params) {
    if (!params.hasKey("prompt")) {
      throw new IllegalArgumentException("Missing required parameter: prompt");
    }

    // Update conversation state with prompt
    try {
      WritableMap state = Arguments.createMap();
      state.putInt("contextId", this.id);
      state.putDouble("timestamp", System.currentTimeMillis());
      
      // Get the prompt
      String prompt = params.getString("prompt");
      
      // Extract structured messages from the prompt
      WritableArray messagesArray = Arguments.createArray();
      extractMessagesFromPrompt(prompt, messagesArray);
      
      // If we have an existing state, try to merge messages
      if (conversationState != null && !conversationState.isEmpty()) {
        try {
          org.json.JSONObject existingState = new org.json.JSONObject(conversationState);
          if (existingState.has("messages")) {
            org.json.JSONArray existingMessages = existingState.getJSONArray("messages");
            
            // If the prompt is likely a continuation, append it to the last message
            // or add it as a new message
            if (messagesArray.size() == 1 && 
                existingMessages.length() > 0 && 
                prompt.length() < 200) { // Heuristic for a short continuation
              
              org.json.JSONObject lastMessage = existingMessages.getJSONObject(existingMessages.length() - 1);
              String lastRole = lastMessage.getString("role");
              
              // If the last message was from the user, this is likely a continuation
              if ("user".equals(lastRole)) {
                WritableArray mergedMessages = Arguments.createArray();
                
                // Copy all existing messages except the last one
                for (int i = 0; i < existingMessages.length() - 1; i++) {
                  org.json.JSONObject msgJson = existingMessages.getJSONObject(i);
                  WritableMap msg = Arguments.createMap();
                  
                  java.util.Iterator<String> keys = msgJson.keys();
                  while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = msgJson.get(key);
                    
                    if (value instanceof String) {
                      msg.putString(key, (String) value);
                    } else if (value instanceof Integer) {
                      msg.putInt(key, (Integer) value);
                    } else if (value instanceof Double) {
                      msg.putDouble(key, (Double) value);
                    } else if (value instanceof Boolean) {
                      msg.putBoolean(key, (Boolean) value);
                    }
                  }
                  
                  mergedMessages.pushMap(msg);
                }
                
                // Merge the last message with the new prompt
                WritableMap mergedLastMessage = Arguments.createMap();
                mergedLastMessage.putString("role", "user");
                mergedLastMessage.putString("content", 
                    lastMessage.getString("content") + "\n" + 
                    messagesArray.getMap(0).getString("content"));
                mergedMessages.pushMap(mergedLastMessage);
                
                // Use the merged messages
                messagesArray = mergedMessages;
              } else {
                // If the last message was from the assistant, add the new message
                WritableArray combinedMessages = Arguments.createArray();
                
                // Copy all existing messages
                for (int i = 0; i < existingMessages.length(); i++) {
                  org.json.JSONObject msgJson = existingMessages.getJSONObject(i);
                  WritableMap msg = Arguments.createMap();
                  
                  java.util.Iterator<String> keys = msgJson.keys();
                  while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = msgJson.get(key);
                    
                    if (value instanceof String) {
                      msg.putString(key, (String) value);
                    } else if (value instanceof Integer) {
                      msg.putInt(key, (Integer) value);
                    } else if (value instanceof Double) {
                      msg.putDouble(key, (Double) value);
                    } else if (value instanceof Boolean) {
                      msg.putBoolean(key, (Boolean) value);
                    }
                  }
                  
                  combinedMessages.pushMap(msg);
                }
                
                // Add the new message
                combinedMessages.pushMap(messagesArray.getMap(0));
                
                // Use the combined messages
                messagesArray = combinedMessages;
              }
            } else {
              // For more complex cases, just use the newly extracted messages
              // This could be improved with more sophisticated merging logic
            }
          }
          
          // Preserve chat template if it exists
          if (existingState.has("chatTemplate")) {
            state.putString("chatTemplate", existingState.getString("chatTemplate"));
          }
        } catch (org.json.JSONException e) {
          Log.w(NAME, "Could not parse existing conversation state: " + e.getMessage());
        }
      }
      
      // Store the structured messages
      state.putArray("messages", messagesArray);
      
      // Store the raw prompt for backward compatibility
      state.putString("prompt", prompt);

      // Store the conversation state
      conversationState = state.toString();
    } catch (Exception e) {
      Log.e(NAME, "Failed to update conversation state", e);
    }

    double[][] logit_bias = new double[0][0];
    if (params.hasKey("logit_bias")) {
      ReadableArray logit_bias_array = params.getArray("logit_bias");
      logit_bias = new double[logit_bias_array.size()][];
      for (int i = 0; i < logit_bias_array.size(); i++) {
        ReadableArray logit_bias_row = logit_bias_array.getArray(i);
        logit_bias[i] = new double[logit_bias_row.size()];
        for (int j = 0; j < logit_bias_row.size(); j++) {
          logit_bias[i][j] = logit_bias_row.getDouble(j);
        }
      }
    }

    WritableMap result = doCompletion(
      this.context,
      // String prompt,
      params.getString("prompt"),
      // int chat_format,
      params.hasKey("chat_format") ? params.getInt("chat_format") : 0,
      // String grammar,
      params.hasKey("grammar") ? params.getString("grammar") : "",
      // String json_schema,
      params.hasKey("json_schema") ? params.getString("json_schema") : "",
      // boolean grammar_lazy,
      params.hasKey("grammar_lazy") ? params.getBoolean("grammar_lazy") : false,
      // ReadableArray grammar_triggers,
      params.hasKey("grammar_triggers") ? params.getArray("grammar_triggers") : null,
      // ReadableArray preserved_tokens,
      params.hasKey("preserved_tokens") ? params.getArray("preserved_tokens") : null,
      // float temperature,
      params.hasKey("temperature") ? (float) params.getDouble("temperature") : 0.7f,
      // int n_threads,
      params.hasKey("n_threads") ? params.getInt("n_threads") : 0,
      // int n_predict,
      params.hasKey("n_predict") ? params.getInt("n_predict") : -1,
      // int n_probs,
      params.hasKey("n_probs") ? params.getInt("n_probs") : 0,
      // int penalty_last_n,
      params.hasKey("penalty_last_n") ? params.getInt("penalty_last_n") : 64,
      // float penalty_repeat,
      params.hasKey("penalty_repeat") ? (float) params.getDouble("penalty_repeat") : 1.00f,
      // float penalty_freq,
      params.hasKey("penalty_freq") ? (float) params.getDouble("penalty_freq") : 0.00f,
      // float penalty_present,
      params.hasKey("penalty_present") ? (float) params.getDouble("penalty_present") : 0.00f,
      // float mirostat,
      params.hasKey("mirostat") ? (float) params.getDouble("mirostat") : 0.00f,
      // float mirostat_tau,
      params.hasKey("mirostat_tau") ? (float) params.getDouble("mirostat_tau") : 5.00f,
      // float mirostat_eta,
      params.hasKey("mirostat_eta") ? (float) params.getDouble("mirostat_eta") : 0.10f,
      // int top_k,
      params.hasKey("top_k") ? params.getInt("top_k") : 40,
      // float top_p,
      params.hasKey("top_p") ? (float) params.getDouble("top_p") : 0.95f,
      // float min_p,
      params.hasKey("min_p") ? (float) params.getDouble("min_p") : 0.05f,
      // float xtc_threshold,
      params.hasKey("xtc_threshold") ? (float) params.getDouble("xtc_threshold") : 0.00f,
      // float xtc_probability,
      params.hasKey("xtc_probability") ? (float) params.getDouble("xtc_probability") : 0.00f,
      // float typical_p,
      params.hasKey("typical_p") ? (float) params.getDouble("typical_p") : 1.00f,
      // int seed,
      params.hasKey("seed") ? params.getInt("seed") : -1,
      // String[] stop,
      params.hasKey("stop") ? params.getArray("stop").toArrayList().toArray(new String[0]) : new String[0],
      // boolean ignore_eos,
      params.hasKey("ignore_eos") ? params.getBoolean("ignore_eos") : false,
      // double[][] logit_bias,
      logit_bias,
      // float dry_multiplier,
      params.hasKey("dry_multiplier") ? (float) params.getDouble("dry_multiplier") : 0.00f,
      // float dry_base,
      params.hasKey("dry_base") ? (float) params.getDouble("dry_base") : 1.75f,
      // int dry_allowed_length,
      params.hasKey("dry_allowed_length") ? params.getInt("dry_allowed_length") : 2,
      // int dry_penalty_last_n,
      params.hasKey("dry_penalty_last_n") ? params.getInt("dry_penalty_last_n") : -1,
      // float top_n_sigma,
      params.hasKey("top_n_sigma") ? (float) params.getDouble("top_n_sigma") : -1.0f,
      // String[] dry_sequence_breakers, when undef, we use the default definition from common.h
      params.hasKey("dry_sequence_breakers") ? params.getArray("dry_sequence_breakers").toArrayList().toArray(new String[0]) : new String[]{"\n", ":", "\"", "*"},
      // PartialCompletionCallback partial_completion_callback
      new PartialCompletionCallback(
        this,
        params.hasKey("emit_partial_completion") ? params.getBoolean("emit_partial_completion") : false
      )
    );
    if (result.hasKey("error")) {
      throw new IllegalStateException(result.getString("error"));
    }
    return result;
  }

  public void stopCompletion() {
    stopCompletion(this.context);
  }

  public boolean isPredicting() {
    return isPredicting(this.context);
  }

  public WritableMap tokenize(String text) {
    WritableMap result = Arguments.createMap();
    result.putArray("tokens", tokenize(this.context, text));
    return result;
  }

  public String detokenize(ReadableArray tokens) {
    int[] toks = new int[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      toks[i] = (int) tokens.getDouble(i);
    }
    return detokenize(this.context, toks);
  }

  public WritableMap getEmbedding(String text, ReadableMap params) {
    if (isEmbeddingEnabled(this.context) == false) {
      throw new IllegalStateException("Embedding is not enabled");
    }
    WritableMap result = embedding(
      this.context,
      text,
      // int embd_normalize,
      params.hasKey("embd_normalize") ? params.getInt("embd_normalize") : -1
    );
    if (result.hasKey("error")) {
      throw new IllegalStateException(result.getString("error"));
    }
    return result;
  }

  public String bench(int pp, int tg, int pl, int nr) {
    return bench(this.context, pp, tg, pl, nr);
  }

  public int applyLoraAdapters(ReadableArray loraAdapters) {
    int result = applyLoraAdapters(this.context, loraAdapters);
    if (result != 0) {
      throw new IllegalStateException("Failed to apply lora adapters");
    }
    return result;
  }

  public void removeLoraAdapters() {
    removeLoraAdapters(this.context);
  }

  public WritableArray getLoadedLoraAdapters() {
    return getLoadedLoraAdapters(this.context);
  }

  public void release() {
    freeContext(context);
  }

  static {
    Log.d(NAME, "Primary ABI: " + Build.SUPPORTED_ABIS[0]);

    String cpuFeatures = LlamaContext.getCpuFeatures();
    Log.d(NAME, "CPU features: " + cpuFeatures);
    boolean hasFp16 = cpuFeatures.contains("fp16") || cpuFeatures.contains("fphp");
    boolean hasDotProd = cpuFeatures.contains("dotprod") || cpuFeatures.contains("asimddp");
    boolean hasSve = cpuFeatures.contains("sve");
    boolean hasI8mm = cpuFeatures.contains("i8mm");
    boolean isAtLeastArmV82 = cpuFeatures.contains("asimd") && cpuFeatures.contains("crc32") && cpuFeatures.contains("aes");
    boolean isAtLeastArmV84 = cpuFeatures.contains("dcpop") && cpuFeatures.contains("uscat");
    Log.d(NAME, "- hasFp16: " + hasFp16);
    Log.d(NAME, "- hasDotProd: " + hasDotProd);
    Log.d(NAME, "- hasSve: " + hasSve);
    Log.d(NAME, "- hasI8mm: " + hasI8mm);
    Log.d(NAME, "- isAtLeastArmV82: " + isAtLeastArmV82);
    Log.d(NAME, "- isAtLeastArmV84: " + isAtLeastArmV84);

    // TODO: Add runtime check for cpu features
    if (LlamaContext.isArm64V8a()) {
      if (hasDotProd && hasI8mm) {
        Log.d(NAME, "Loading librnllama_v8_2_dotprod_i8mm.so");
        System.loadLibrary("rnllama_v8_2_dotprod_i8mm");
        loadedLibrary = "rnllama_v8_2_dotprod_i8mm";
      } else if (hasDotProd) {
        Log.d(NAME, "Loading librnllama_v8_2_dotprod.so");
        System.loadLibrary("rnllama_v8_2_dotprod");
        loadedLibrary = "rnllama_v8_2_dotprod";
      } else if (hasI8mm) {
        Log.d(NAME, "Loading librnllama_v8_2_i8mm.so");
        System.loadLibrary("rnllama_v8_2_i8mm");
        loadedLibrary = "rnllama_v8_2_i8mm";
      } else if (hasFp16) {
        Log.d(NAME, "Loading librnllama_v8_2.so");
        System.loadLibrary("rnllama_v8_2");
        loadedLibrary = "rnllama_v8_2";
      } else {
        Log.d(NAME, "Loading default librnllama_v8.so");
        System.loadLibrary("rnllama_v8");
        loadedLibrary = "rnllama_v8";
      }
      //  Log.d(NAME, "Loading librnllama_v8_7.so with runtime feature detection");
      //  System.loadLibrary("rnllama_v8_7");
    } else if (LlamaContext.isX86_64()) {
      Log.d(NAME, "Loading librnllama_x86_64.so");
      System.loadLibrary("rnllama_x86_64");
      loadedLibrary = "rnllama_x86_64";
    } else {
      Log.d(NAME, "ARM32 is not supported, skipping loading library");
    }
  }

  private static boolean isArm64V8a() {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a");
  }

  private static boolean isX86_64() {
    return Build.SUPPORTED_ABIS[0].equals("x86_64");
  }

  private static boolean isArchNotSupported() {
    return isArm64V8a() == false && isX86_64() == false;
  }

  private static String getCpuFeatures() {
    File file = new File("/proc/cpuinfo");
    StringBuilder stringBuilder = new StringBuilder();
    try {
      BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        if (line.startsWith("Features")) {
          stringBuilder.append(line);
          break;
        }
      }
      bufferedReader.close();
      return stringBuilder.toString();
    } catch (IOException e) {
      Log.w(NAME, "Couldn't read /proc/cpuinfo", e);
      return "";
    }
  }

  protected static native WritableMap modelInfo(
    String model,
    String[] skip
  );
  protected static native long initContext(
    String model,
    String chat_template,
    String reasoning_format,
    boolean embedding,
    int embd_normalize,
    int n_ctx,
    int n_batch,
    int n_ubatch,
    int n_threads,
    int n_gpu_layers, // TODO: Support this
    boolean flash_attn,
    String cache_type_k,
    String cache_type_v,
    boolean use_mlock,
    boolean use_mmap,
    boolean vocab_only,
    String lora,
    float lora_scaled,
    ReadableArray lora_list,
    float rope_freq_base,
    float rope_freq_scale,
    int pooling_type,
    LoadProgressCallback load_progress_callback
  );
  protected static native void interruptLoad(long contextPtr);
  protected static native WritableMap loadModelDetails(
    long contextPtr
  );
  protected static native WritableMap getFormattedChatWithJinja(
    long contextPtr,
    String messages,
    String chatTemplate,
    String jsonSchema,
    String tools,
    boolean parallelToolCalls,
    String toolChoice
  );
  protected static native String getFormattedChat(
    long contextPtr,
    String messages,
    String chatTemplate
  );
  protected static native WritableMap loadSession(
    long contextPtr,
    String path
  );
  protected static native int saveSession(
    long contextPtr,
    String path,
    int size
  );
  protected static native WritableMap doCompletion(
    long context_ptr,
    String prompt,
    int chat_format,
    String grammar,
    String json_schema,
    boolean grammar_lazy,
    ReadableArray grammar_triggers,
    ReadableArray preserved_tokens,
    float temperature,
    int n_threads,
    int n_predict,
    int n_probs,
    int penalty_last_n,
    float penalty_repeat,
    float penalty_freq,
    float penalty_present,
    float mirostat,
    float mirostat_tau,
    float mirostat_eta,
    int top_k,
    float top_p,
    float min_p,
    float xtc_threshold,
    float xtc_probability,
    float typical_p,
    int seed,
    String[] stop,
    boolean ignore_eos,
    double[][] logit_bias,
    float   dry_multiplier,
    float   dry_base,
    int dry_allowed_length,
    int dry_penalty_last_n,
    float top_n_sigma,
    String[] dry_sequence_breakers,
    PartialCompletionCallback partial_completion_callback
  );
  protected static native void stopCompletion(long contextPtr);
  protected static native boolean isPredicting(long contextPtr);
  protected static native WritableArray tokenize(long contextPtr, String text);
  protected static native String detokenize(long contextPtr, int[] tokens);
  protected static native boolean isEmbeddingEnabled(long contextPtr);
  protected static native WritableMap embedding(
    long contextPtr,
    String text,
    int embd_normalize
  );
  protected static native String bench(long contextPtr, int pp, int tg, int pl, int nr);
  protected static native int applyLoraAdapters(long contextPtr, ReadableArray loraAdapters);
  protected static native void removeLoraAdapters(long contextPtr);
  protected static native WritableArray getLoadedLoraAdapters(long contextPtr);
  protected static native void freeContext(long contextPtr);
  protected static native void setupLog(NativeLogCallback logCallback);
  protected static native void unsetLog();
}
